package com.evops.credit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.credit.CreditEnums;
import com.evops.credit.entity.CreditOccupation;
import com.evops.credit.entity.Customer;
import com.evops.credit.mapper.CreditOccupationMapper;
import com.evops.credit.mapper.CustomerMapper;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.SiteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 授信占用域：上刊凭报价单快照占用、撤刊/对冲释放，余额不足拦在提交口。
 *
 * 占用口径：客户 occupied_amount 只认数据库条件更新这一处——
 * “在档 且 占用+本次&lt;=额度”才放行，并发超额由条件更新兜底，0 行即拒。
 */
@Service
public class CreditService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CustomerMapper customerMapper;
    private final CreditOccupationMapper occupationMapper;
    private final SiteMapper siteMapper;

    public CreditService(CustomerMapper customerMapper,
                         CreditOccupationMapper occupationMapper,
                         SiteMapper siteMapper) {
        this.customerMapper = customerMapper;
        this.occupationMapper = occupationMapper;
        this.siteMapper = siteMapper;
    }

    /**
     * 上刊提交口占用。客户与金额由调用方（网关）从报价单快照解析后传入。
     * 原子条件更新保证不超额、不在冻结户上占用。
     */
    @Transactional(rollbackFor = Exception.class)
    public CreditOccupation occupy(PostingOrder order, Customer customer, String quoteNo,
                                   BigDecimal amount) {
        BigDecimal inc = CustomerService.normalizeMoney(amount);

        Long reused = occupationMapper.selectCount(new LambdaQueryWrapper<CreditOccupation>()
                .eq(CreditOccupation::getQuoteNo, quoteNo));
        if (reused != null && reused > 0) {
            throw new BusinessException("报价单 " + quoteNo + " 已被授信上刊单占用，一张报价单最多背一张上刊单");
        }

        int updated = customerMapper.update(null, new LambdaUpdateWrapper<Customer>()
                .eq(Customer::getId, customer.getId())
                .eq(Customer::getCreditStatus, CreditEnums.CUSTOMER_ACTIVE)
                .apply("occupied_amount + {0} <= credit_limit", inc)
                .setSql("occupied_amount = occupied_amount + " + inc.toPlainString()));
        if (updated == 0) {
            Customer fresh = customerMapper.selectById(customer.getId());
            if (fresh == null) {
                throw new BusinessException("客户不存在，无法占用授信");
            }
            if (CreditEnums.CUSTOMER_FROZEN.equals(fresh.getCreditStatus())) {
                throw new BusinessException("客户「" + fresh.getCustomerName() + "」已冻结，不能新占用授信");
            }
            BigDecimal available = fresh.getCreditLimit().subtract(fresh.getOccupiedAmount());
            throw new BusinessException("客户「" + fresh.getCustomerName() + "」可用额度不足：额度 "
                    + fresh.getCreditLimit().toPlainString() + "，已占用 "
                    + fresh.getOccupiedAmount().toPlainString() + "，可用 " + available.toPlainString()
                    + "，本次需 " + inc.toPlainString());
        }

        String siteNo = null;
        if (order.getSiteId() != null) {
            Site site = siteMapper.selectById(order.getSiteId());
            siteNo = site == null ? null : site.getSiteNo();
        }
        CreditOccupation occ = new CreditOccupation();
        occ.setCustomerId(customer.getId());
        occ.setCustomerName(customer.getCustomerName());
        occ.setOrderId(order.getId());
        occ.setOrderNo(order.getOrderNo());
        occ.setQuoteNo(quoteNo);
        occ.setAmount(inc);
        occ.setSettledAmount(BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
        occ.setStatus(CreditEnums.OCC_ACTIVE);
        occ.setSettleStatus(CreditEnums.SETTLE_UNSETTLED);
        occ.setBizPeriod(order.getBizPeriod());
        occ.setSiteNo(siteNo);
        occ.setCreativeName(order.getCreativeName());
        occupationMapper.insert(occ);
        return occ;
    }

    /**
     * 撤刊/对冲释放。非授信单（无占用行）直接跳过。
     * 释放当时仍在额的部分 = 金额 - 已核销（挂起期间回款已还过额度，不重复释放）。
     *
     * @param reversed true=对冲更正（释放但不转应收）；false=落架撤刊（未核销额转应收）
     */
    @Transactional(rollbackFor = Exception.class)
    public CreditOccupation release(PostingOrder order, boolean reversed, String operatorName) {
        CreditOccupation occ = byOrderId(order.getId());
        if (occ == null) {
            return null;
        }
        if (!CreditEnums.OCC_ACTIVE.equals(occ.getStatus())) {
            // 已关闭的占用不重复释放
            return occ;
        }
        if (reversed && occ.getSettledAmount().signum() > 0) {
            throw new BusinessException("单据 " + order.getOrderNo() + " 已有回款核销 "
                    + occ.getSettledAmount().toPlainString()
                    + "，不能直接对冲；请先走落架撤刊或退款冲销");
        }

        BigDecimal locked = occ.getAmount().subtract(occ.getSettledAmount());
        if (locked.signum() > 0) {
            int updated = customerMapper.update(null, new LambdaUpdateWrapper<Customer>()
                    .eq(Customer::getId, occ.getCustomerId())
                    .apply("occupied_amount - {0} >= 0", locked)
                    .setSql("occupied_amount = occupied_amount - " + locked.toPlainString()));
            if (updated == 0) {
                // 占用额数据不自洽的兜底，正常不会走到
                throw new BusinessException("客户占用额异常，无法释放单据 " + order.getOrderNo());
            }
        }

        CreditOccupation patch = new CreditOccupation();
        patch.setId(occ.getId());
        patch.setStatus(reversed ? CreditEnums.OCC_REVERSED : CreditEnums.OCC_RELEASED);
        patch.setReleasedTime(LocalDateTime.now());
        occupationMapper.updateById(patch);
        occ.setStatus(patch.getStatus());
        occ.setReleasedTime(patch.getReleasedTime());
        return occ;
    }

    public CreditOccupation byOrderId(Long orderId) {
        return occupationMapper.selectOne(new LambdaQueryWrapper<CreditOccupation>()
                .eq(CreditOccupation::getOrderId, orderId));
    }

    public CreditOccupation requireByOrderNo(String orderNo) {
        CreditOccupation occ = occupationMapper.selectOne(new LambdaQueryWrapper<CreditOccupation>()
                .eq(CreditOccupation::getOrderNo, orderNo == null ? "" : orderNo.trim()));
        if (occ == null) {
            throw new BusinessException("单号 " + orderNo + " 没有授信占用记录");
        }
        return occ;
    }

    /** 占用/应收明细翻查：销售限名下客户；可按客户/期间/占用状态/核销状态过滤。 */
    public List<CreditOccupation> list(Viewer viewer, String customerNo, String period,
                                       String status, String settleStatus) {
        LambdaQueryWrapper<CreditOccupation> wrapper = new LambdaQueryWrapper<>();
        if (viewer.isScoped()) {
            List<String> names = scopeCustomerNames(viewer);
            if (names.isEmpty()) {
                wrapper.apply("1 = 0"); // 名下没有客户，查不出任何占用
            } else {
                wrapper.in(CreditOccupation::getCustomerName, names);
            }
        }
        if (customerNo != null && !customerNo.trim().isEmpty()) {
            Customer customer = customerOf(customerNo);
            viewer.checkOwn(customer.getOwnerSales());
            wrapper.eq(CreditOccupation::getCustomerId, customer.getId());
        }
        if (period != null && !period.trim().isEmpty()) {
            wrapper.eq(CreditOccupation::getBizPeriod, period.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(CreditOccupation::getStatus, status.trim());
        }
        if (settleStatus != null && !settleStatus.trim().isEmpty()) {
            wrapper.eq(CreditOccupation::getSettleStatus, settleStatus.trim());
        }
        return occupationMapper.selectList(wrapper.orderByDesc(CreditOccupation::getId));
    }

    /** 月末占用明细：按固化归属期间 yyyy-MM 拉每户每单。 */
    public List<CreditOccupation> monthReport(String period, Viewer viewer) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new BusinessException("归属期间格式必须为 yyyy-MM");
        }
        return list(viewer, null, period, null, null);
    }

    /** 财务月末导出 CSV（BOM，Excel 打开不乱码）。 */
    public String occupationCsv(String period, Viewer viewer) {
        List<CreditOccupation> rows = monthReport(period, viewer);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("归属期间,客户编号,客户名称,归属销售,上刊单号,报价单号,点位,画面,")
                .append("占用金额,已核销金额,未核销金额,占用状态,核销状态,撤刊时间\n");
        for (CreditOccupation o : rows) {
            Customer c = customerMapper.selectById(o.getCustomerId());
            BigDecimal outstanding = o.getAmount().subtract(o.getSettledAmount());
            csv.append(esc(o.getBizPeriod())).append(',')
                    .append(esc(c == null ? "" : c.getCustomerNo())).append(',')
                    .append(esc(o.getCustomerName())).append(',')
                    .append(esc(c == null ? "" : c.getOwnerSales())).append(',')
                    .append(esc(o.getOrderNo())).append(',')
                    .append(esc(o.getQuoteNo())).append(',')
                    .append(esc(o.getSiteNo())).append(',')
                    .append(esc(o.getCreativeName())).append(',')
                    .append(o.getAmount().toPlainString()).append(',')
                    .append(o.getSettledAmount().toPlainString()).append(',')
                    .append(outstanding.toPlainString()).append(',')
                    .append(esc(CreditEnums.occupationStatusLabel(o.getStatus()))).append(',')
                    .append(esc(CreditEnums.settleStatusLabel(o.getSettleStatus()))).append(',')
                    .append(o.getReleasedTime() == null ? "" : o.getReleasedTime().format(TS))
                    .append('\n');
        }
        return csv.toString();
    }

    private Customer customerOf(String customerNo) {
        Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getCustomerNo, customerNo.trim()));
        if (customer == null) {
            throw new BusinessException("客户不存在: " + customerNo);
        }
        return customer;
    }

    /** 销售视角下名下客户名集合（占用表按客户名冗余列过滤，避免再绕客户表）。 */
    private List<String> scopeCustomerNames(Viewer viewer) {
        return customerMapper.selectList(new LambdaQueryWrapper<Customer>()
                        .eq(Customer::getOwnerSales, viewer.getUsername()))
                .stream().map(Customer::getCustomerName)
                .collect(java.util.stream.Collectors.toList());
    }

    static String esc(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
