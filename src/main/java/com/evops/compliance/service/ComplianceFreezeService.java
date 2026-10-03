package com.evops.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.compliance.ComplianceEnums;
import com.evops.compliance.dto.FreezePreviewView;
import com.evops.compliance.dto.FrozenOrderItem;
import com.evops.compliance.entity.IndustryCategory;
import com.evops.credit.entity.Customer;
import com.evops.credit.mapper.CustomerMapper;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.entity.OrderEvent;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.mapper.OrderEventMapper;
import com.evops.ledger.mapper.PostingOrderMapper;
import com.evops.ratecard.entity.Quote;
import com.evops.ratecard.mapper.QuoteMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在途单合规冻结域：受限客户名下挂起中的授信上刊单，合规岗先预览圈定范围，
 * 确认后一键批量冻结；冻结只打标与留底，不动授信占用、不释放档期。
 *
 * 圈定口径（冻结/解冻一致）：
 * 客户 t_customer.industry_category_code 命中类目，且单据 status=HUNG、credit_used=1
 * （授信单才可追溯客户；普通上刊单不带客户，不在受限管控面内），
 * compliance_frozen=0（冻结）/1（解冻）。
 */
@Service
public class ComplianceFreezeService {

    private final PostingOrderMapper orderMapper;
    private final CustomerMapper customerMapper;
    private final OrderEventMapper eventMapper;
    private final IndustryCategoryService categoryService;
    private final QuoteMapper quoteMapper;
    private final ObjectMapper objectMapper;

    public ComplianceFreezeService(PostingOrderMapper orderMapper,
                                   CustomerMapper customerMapper,
                                   OrderEventMapper eventMapper,
                                   IndustryCategoryService categoryService,
                                   QuoteMapper quoteMapper,
                                   ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.customerMapper = customerMapper;
        this.eventMapper = eventMapper;
        this.categoryService = categoryService;
        this.quoteMapper = quoteMapper;
        this.objectMapper = objectMapper;
    }

    /** 冻结预览：类目必须是当前受限在档条目，返回该类目客户全部在途挂起授信单。 */
    public FreezePreviewView previewFreeze(String categoryCode) {
        IndustryCategory category = categoryService.requireByCode(categoryCode);
        if (!ComplianceEnums.CATEGORY_ACTIVE.equals(category.getStatus())
                || !Integer.valueOf(1).equals(category.getRestricted())) {
            throw new BusinessException("类目「" + category.getCategoryName()
                    + "」当前不是受限状态，没有需要冻结的在途单");
        }
        List<PostingOrder> hung = inFlightOrders(category.getCategoryCode(), 0);
        return buildView(category, hung);
    }

    /** 解冻预览：类目解除受限后，圈出此前冻结的单供一键恢复。 */
    public FreezePreviewView previewUnfreeze(String categoryCode) {
        IndustryCategory category = categoryService.requireByCode(categoryCode);
        List<PostingOrder> frozen = inFlightOrders(category.getCategoryCode(), 1);
        return buildView(category, frozen);
    }

    /** 一键冻结：条件更新 0→1 防重复，逐单写 COMPLIANCE_FREEZE 事件（依据+操作人留底）。 */
    @Transactional(rollbackFor = Exception.class)
    public FreezePreviewView executeFreeze(String categoryCode, String remark, Viewer viewer) {
        viewer.requireCompliance();
        FreezePreviewView preview = previewFreeze(categoryCode);
        IndustryCategory category = preview.getCategory();
        String reason = "合规冻结：客户行业类目「" + category.getCategoryName() + "（"
                + category.getCategoryCode() + "）」命中受限行业名单，依据：" + category.getLegalBasis()
                + "；冻结操作人 " + viewer.getUsername()
                + (remark != null && !remark.trim().isEmpty() ? "；备注：" + remark.trim() : "");

        int frozen = 0;
        for (FrozenOrderItem item : preview.getOrders()) {
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<PostingOrder>()
                    .eq(PostingOrder::getOrderNo, item.getOrderNo())
                    .eq(PostingOrder::getStatus, LedgerEnums.ORDER_HUNG)
                    .eq(PostingOrder::getComplianceFrozen, 0)
                    .set(PostingOrder::getComplianceFrozen, 1)
                    .set(PostingOrder::getFreezeReason, reason));
            if (updated > 0) {
                frozen++;
                PostingOrder order = orderMapper.selectOne(new LambdaQueryWrapper<PostingOrder>()
                        .eq(PostingOrder::getOrderNo, item.getOrderNo()));
                recordEvent(order, LedgerEnums.EVENT_COMPLIANCE_FREEZE, reason, viewer.getUsername());
            }
        }
        preview.setAffectedCount(frozen);
        return preview;
    }

    /** 一键解冻：1→0，逐单写 COMPLIANCE_UNFREEZE 事件；freezeReason 保留不清空。 */
    @Transactional(rollbackFor = Exception.class)
    public FreezePreviewView executeUnfreeze(String categoryCode, String remark, Viewer viewer) {
        viewer.requireCompliance();
        FreezePreviewView preview = previewUnfreeze(categoryCode);
        IndustryCategory category = preview.getCategory();
        String detail = "合规解冻：类目「" + category.getCategoryName() + "（" + category.getCategoryCode()
                + "）」已解除受限/经合规核对，在途单恢复可操作；解冻操作人 " + viewer.getUsername()
                + (remark != null && !remark.trim().isEmpty() ? "；备注：" + remark.trim() : "");

        int unfrozen = 0;
        for (FrozenOrderItem item : preview.getOrders()) {
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<PostingOrder>()
                    .eq(PostingOrder::getOrderNo, item.getOrderNo())
                    .eq(PostingOrder::getStatus, LedgerEnums.ORDER_HUNG)
                    .eq(PostingOrder::getComplianceFrozen, 1)
                    .set(PostingOrder::getComplianceFrozen, 0));
            if (updated > 0) {
                unfrozen++;
                PostingOrder order = orderMapper.selectOne(new LambdaQueryWrapper<PostingOrder>()
                        .eq(PostingOrder::getOrderNo, item.getOrderNo()));
                recordEvent(order, LedgerEnums.EVENT_COMPLIANCE_UNFREEZE, detail, viewer.getUsername());
            }
        }
        preview.setAffectedCount(unfrozen);
        return preview;
    }

    // ---- 上刊页预检 ----

    /**
     * 按报价单号预检：报价客户 → 行业类目 → 是否受限。
     * 返回 blocked/category/legalBasis/customerName 供页面红字提示；真正硬拦在授信上刊事务里。
     * 直接走 CustomerMapper（不经 credit 域 Service），保持 compliance↔credit 依赖单向。
     */
    public Map<String, Object> checkQuoteOrder(String quoteNo) {
        Map<String, Object> result = new LinkedHashMap<>();
        String qno = quoteNo.trim();
        Quote quote = quoteMapper.selectOne(new LambdaQueryWrapper<Quote>().eq(Quote::getQuoteNo, qno));
        if (quote == null) {
            throw new BusinessException("报价单不存在: " + qno);
        }
        result.put("quoteNo", qno);
        result.put("customerName", quote.getCustomerName());
        Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getCustomerName, quote.getCustomerName()));
        if (customer == null) {
            result.put("blocked", false);
            result.put("customerFiled", false);
            return result;
        }
        result.put("customerFiled", true);
        result.put("customerNo", customer.getCustomerNo());
        result.put("categoryCode", customer.getIndustryCategoryCode());
        result.put("categoryName", customer.getIndustryCategoryName());
        IndustryCategory hit = categoryService.findRestricted(customer.getIndustryCategoryCode());
        result.put("blocked", hit != null);
        if (hit != null) {
            result.put("legalBasis", hit.getLegalBasis());
            result.put("message", IndustryCategoryService.rejectMessage(hit, "授信上刊提交"));
        }
        return result;
    }

    // ---- 内部 ----

    private List<PostingOrder> inFlightOrders(String categoryCode, int frozenFlag) {
        List<Customer> customers = customerMapper.selectList(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getIndustryCategoryCode, categoryCode));
        if (customers.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> customerIds = new ArrayList<>();
        for (Customer c : customers) {
            customerIds.add(c.getId());
        }
        return orderMapper.selectList(new LambdaQueryWrapper<PostingOrder>()
                .in(PostingOrder::getCustomerId, customerIds)
                .eq(PostingOrder::getStatus, LedgerEnums.ORDER_HUNG)
                .eq(PostingOrder::getCreditUsed, 1)
                .eq(PostingOrder::getComplianceFrozen, frozenFlag)
                .orderByAsc(PostingOrder::getId));
    }

    private FreezePreviewView buildView(IndustryCategory category, List<PostingOrder> orders) {
        Map<Long, Customer> customers = loadCustomers(orders);
        List<FrozenOrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PostingOrder o : orders) {
            Customer c = customers.get(o.getCustomerId());
            FrozenOrderItem item = new FrozenOrderItem();
            item.setOrderNo(o.getOrderNo());
            item.setCustomerId(o.getCustomerId());
            item.setCustomerName(c != null ? c.getCustomerName() : null);
            item.setIndustryCategoryCode(category.getCategoryCode());
            item.setIndustryCategoryName(category.getCategoryName());
            item.setSiteId(o.getSiteId());
            item.setCreativeName(o.getCreativeName());
            item.setBizPeriod(o.getBizPeriod());
            item.setOccupiedAmount(o.getOccupiedAmount());
            items.add(item);
            if (o.getOccupiedAmount() != null) {
                total = total.add(o.getOccupiedAmount());
            }
        }
        FreezePreviewView view = new FreezePreviewView();
        view.setCategory(category);
        view.setTotalCount(items.size());
        view.setTotalAmount(total);
        view.setOrders(items);
        return view;
    }

    /** 执行后把实际处理单数回传（预览与执行之间无新增则与预览一致）。 */
    private Map<Long, Customer> loadCustomers(List<PostingOrder> orders) {
        Map<Long, Customer> map = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (PostingOrder o : orders) {
            if (o.getCustomerId() != null && !ids.contains(o.getCustomerId())) {
                ids.add(o.getCustomerId());
            }
        }
        if (!ids.isEmpty()) {
            for (Customer c : customerMapper.selectBatchIds(ids)) {
                map.put(c.getId(), c);
            }
        }
        return map;
    }

    private void recordEvent(PostingOrder order, String eventType, String detail, String operator) {
        OrderEvent event = new OrderEvent();
        event.setOrderId(order.getId());
        event.setOrderNo(order.getOrderNo());
        event.setRootOrderId(order.getRootOrderId());
        event.setEventType(eventType);
        event.setVersionNo(order.getVersionNo());
        event.setOccurDate(LocalDate.now());
        event.setOperatorName(operator);
        event.setDetail(detail);
        try {
            event.setSnapshot(objectMapper.writeValueAsString(order));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("冻结事件快照写入失败", e);
        }
        event.setEventTime(LocalDateTime.now());
        eventMapper.insert(event);
    }
}
