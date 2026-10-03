package com.evops.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.compliance.ComplianceEnums;
import com.evops.compliance.dto.FreezePreviewView;
import com.evops.compliance.entity.CategoryListVersion;
import com.evops.compliance.entity.ComplianceFreezeBatch;
import com.evops.compliance.entity.ComplianceFreezeRecord;
import com.evops.compliance.mapper.ComplianceFreezeBatchMapper;
import com.evops.compliance.mapper.ComplianceFreezeRecordMapper;
import com.evops.compliance.support.ComplianceCustomerGateway;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.mapper.PostingOrderMapper;
import com.evops.ledger.service.OrderService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批量冻结在途单：已建档客户后来进了名单的，按类目集把挂起中的在途单批量冻结。
 *
 * 口径：
 * - 匹配按单上行业类目快照（普通单/授信单通吃）：挂起中且合规状态正常的单；
 * - 曾被同类目解冻特批的单不再重复冻结；冻结动作走原子条件更新，重复触发/并发都幂等；
 * - 一批一记录：批次（类目集、命中客户数、单据数、经手人）+ 逐单记录行（处置状态跟随）；
 * - 处置出口：解冻（特批放行）/ 强制落架（下画），都留事件与记录。
 */
@Service
public class ComplianceFreezeService {

    private final PostingOrderMapper orderMapper;
    private final ComplianceFreezeBatchMapper batchMapper;
    private final ComplianceFreezeRecordMapper recordMapper;
    private final CategoryListService categoryListService;
    private final OrderService orderService;
    private final ObjectProvider<ComplianceCustomerGateway> customerGatewayProvider;

    public ComplianceFreezeService(PostingOrderMapper orderMapper,
                                   ComplianceFreezeBatchMapper batchMapper,
                                   ComplianceFreezeRecordMapper recordMapper,
                                   CategoryListService categoryListService,
                                   OrderService orderService,
                                   ObjectProvider<ComplianceCustomerGateway> customerGatewayProvider) {
        this.orderMapper = orderMapper;
        this.batchMapper = batchMapper;
        this.recordMapper = recordMapper;
        this.categoryListService = categoryListService;
        this.orderService = orderService;
        this.customerGatewayProvider = customerGatewayProvider;
    }

    /** 命中预览：类目集留空=当前生效版全量类目。 */
    public FreezePreviewView preview(Collection<String> categories, Viewer viewer) {
        viewer.requireListAdmin();
        Set<String> cats = resolveCategories(categories);
        FreezePreviewView view = new FreezePreviewView();
        view.setCategoryBasis(basisOf(cats));
        List<PostingOrder> hits = findFreezable(cats);
        Map<Long, ComplianceCustomerGateway.CustomerBrief> custMap = customersOf(cats);
        List<FreezePreviewView.HitOrder> orders = new ArrayList<>();
        Set<Long> customerIds = new LinkedHashSet<>();
        for (PostingOrder o : hits) {
            FreezePreviewView.HitOrder hit = new FreezePreviewView.HitOrder();
            hit.setOrderNo(o.getOrderNo());
            hit.setIndustryCategory(o.getIndustryCategory());
            hit.setCreativeName(o.getCreativeName());
            hit.setWeekStart(o.getWeekStart());
            hit.setWeekEnd(o.getWeekEnd());
            hit.setOperatorName(o.getOperatorName());
            ComplianceCustomerGateway.CustomerBrief brief = custMap.get(o.getCustomerId());
            hit.setCustomerName(brief != null ? brief.getCustomerName()
                    : (o.getCustomerId() != null ? "客户id=" + o.getCustomerId() : "（普通单）"));
            if (o.getCustomerId() != null) {
                customerIds.add(o.getCustomerId());
            }
            orders.add(hit);
        }
        view.setOrders(orders);
        view.setOrderCount(orders.size());
        view.setCustomerCount(customerIds.size());
        return view;
    }

    /**
     * 批量冻结：一个事务建批次、逐单原子置冻结并写事件、插记录行、回写批次计数。
     * 重复触发同类目：已冻结/已特批解冻的单自动跳过，幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public ComplianceFreezeBatch freeze(Collection<String> categories, String reason, Viewer viewer) {
        viewer.requireListAdmin();
        Set<String> cats = resolveCategories(categories);
        if (cats.isEmpty()) {
            throw new BusinessException("没有可冻结的受限类目（生效名单为空且未指定类目）");
        }
        Map<String, String> basisMap = categoryListService.effectiveBasisMap();
        CategoryListVersion effective = categoryListService.currentEffective();
        List<PostingOrder> hits = findFreezable(cats);
        Map<Long, ComplianceCustomerGateway.CustomerBrief> custMap = customersOf(cats);

        ComplianceFreezeBatch batch = new ComplianceFreezeBatch();
        batch.setBatchNo(generateBatchNo());
        batch.setListVersionId(effective != null ? effective.getId() : null);
        batch.setListVersionNo(effective != null ? effective.getVersionNo() : null);
        batch.setCategories(String.join(",", cats));
        batch.setCustomerCount(0);
        batch.setOrderCount(0);
        batch.setReason(reason == null || reason.trim().isEmpty() ? "受限行业名单命中，批量冻结在途单" : reason.trim());
        batch.setOperatorName(viewer.getUsername());
        batchMapper.insert(batch);

        int frozen = 0;
        Set<Long> customerIds = new LinkedHashSet<>();
        for (PostingOrder order : hits) {
            String basis = basisMap.getOrDefault(order.getIndustryCategory(), "受限行业名单");
            boolean done = orderService.markComplianceFrozen(order, batch.getBatchNo(),
                    order.getIndustryCategory(), basis, viewer.getUsername());
            if (!done) {
                continue;
            }
            ComplianceFreezeRecord record = new ComplianceFreezeRecord();
            record.setBatchId(batch.getId());
            record.setBatchNo(batch.getBatchNo());
            record.setOrderId(order.getId());
            record.setOrderNo(order.getOrderNo());
            record.setCustomerId(order.getCustomerId());
            ComplianceCustomerGateway.CustomerBrief brief = custMap.get(order.getCustomerId());
            if (brief != null) {
                record.setCustomerNo(brief.getCustomerNo());
                record.setCustomerName(brief.getCustomerName());
            }
            record.setIndustryCategory(order.getIndustryCategory());
            record.setStatus(ComplianceEnums.RECORD_FROZEN);
            recordMapper.insert(record);
            frozen++;
            if (order.getCustomerId() != null) {
                customerIds.add(order.getCustomerId());
            }
        }

        ComplianceFreezeBatch patch = new ComplianceFreezeBatch();
        patch.setId(batch.getId());
        patch.setOrderCount(frozen);
        patch.setCustomerCount(customerIds.size());
        batchMapper.updateById(patch);
        batch.setOrderCount(frozen);
        batch.setCustomerCount(customerIds.size());
        return batch;
    }

    /** 解冻：单据恢复 NORMAL（事件留底），冻结记录置 RELEASED（同类目特批，之后不再重复冻结）。 */
    @Transactional(rollbackFor = Exception.class)
    public void unfreeze(String orderNo, String reason, Viewer viewer) {
        viewer.requireListAdmin();
        PostingOrder order = orderService.requireByNo(orderNo);
        orderService.markComplianceUnfrozen(order, viewer.getUsername(), reason);
        disposeRecord(order.getId(), ComplianceEnums.RECORD_RELEASED, viewer.getUsername(), reason);
    }

    /** 强制落架：冻结单下画（走正常落架主流程，事件注明合规强制），冻结记录置 FORCE_CLOSED。 */
    @Transactional(rollbackFor = Exception.class)
    public void forceTakeDown(String orderNo, String reason, Viewer viewer) {
        viewer.requireListAdmin();
        PostingOrder order = orderService.forceTakeDown(orderNo, viewer.getUsername(), reason);
        disposeRecord(order.getId(), ComplianceEnums.RECORD_FORCE_CLOSED, viewer.getUsername(), reason);
    }

    public List<ComplianceFreezeBatch> listBatches(Viewer viewer) {
        viewer.requireListAdmin();
        return batchMapper.selectList(new LambdaQueryWrapper<ComplianceFreezeBatch>()
                .orderByDesc(ComplianceFreezeBatch::getId));
    }

    public ComplianceFreezeBatch requireBatch(String batchNo, Viewer viewer) {
        viewer.requireListAdmin();
        ComplianceFreezeBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<ComplianceFreezeBatch>()
                .eq(ComplianceFreezeBatch::getBatchNo, batchNo == null ? "" : batchNo.trim()));
        if (batch == null) {
            throw new BusinessException("冻结批次不存在: " + batchNo);
        }
        return batch;
    }

    public List<ComplianceFreezeRecord> recordsOf(Long batchId, Viewer viewer) {
        viewer.requireListAdmin();
        return recordMapper.selectList(new LambdaQueryWrapper<ComplianceFreezeRecord>()
                .eq(ComplianceFreezeRecord::getBatchId, batchId)
                .orderByAsc(ComplianceFreezeRecord::getId));
    }

    // ---- 内部 ----

    /** 类目集解析：留空=当前生效版全量类目；显式指定则去空白去重。 */
    private Set<String> resolveCategories(Collection<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return categoryListService.effectiveItems().stream()
                    .map(com.evops.compliance.entity.CategoryListItem::getCategoryName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return categories.stream()
                .filter(c -> c != null && !c.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, String> basisOf(Set<String> cats) {
        Map<String, String> effective = categoryListService.effectiveBasisMap();
        Map<String, String> map = new HashMap<>();
        for (String cat : cats) {
            map.put(cat, effective.getOrDefault(cat, "（不在当前生效名单内）"));
        }
        return map;
    }

    /**
     * 可冻结在途单：挂起中 + 合规状态正常 + 类目命中 + 未被同类目解冻特批过。
     * 真正置冻结时还有原子条件更新兜底（并发/重复触发幂等）。
     */
    private List<PostingOrder> findFreezable(Set<String> cats) {
        if (cats.isEmpty()) {
            return new ArrayList<>();
        }
        return orderMapper.selectList(new LambdaQueryWrapper<PostingOrder>()
                .eq(PostingOrder::getStatus, LedgerEnums.ORDER_HUNG)
                .eq(PostingOrder::getComplianceStatus, LedgerEnums.COMPLIANCE_NORMAL)
                .in(PostingOrder::getIndustryCategory, cats)
                .notExists("SELECT 1 FROM t_compliance_freeze_record r"
                        + " WHERE r.order_id = t_posting_order.id"
                        + " AND r.status = '" + ComplianceEnums.RECORD_RELEASED + "'"
                        + " AND r.industry_category = t_posting_order.industry_category")
                .orderByAsc(PostingOrder::getId));
    }

    /** 按类目查建档客户（记录留痕/预览展示）；无授信模块实现时返回空映射。 */
    private Map<Long, ComplianceCustomerGateway.CustomerBrief> customersOf(Set<String> cats) {
        ComplianceCustomerGateway gateway = customerGatewayProvider.getIfAvailable();
        if (gateway == null || cats.isEmpty()) {
            return new HashMap<>();
        }
        return gateway.findByCategories(cats).stream()
                .collect(Collectors.toMap(ComplianceCustomerGateway.CustomerBrief::getCustomerId,
                        Function.identity(), (a, b) -> a));
    }

    private void disposeRecord(Long orderId, String targetStatus, String operator, String reason) {
        ComplianceFreezeRecord record = recordMapper.selectOne(new LambdaQueryWrapper<ComplianceFreezeRecord>()
                .eq(ComplianceFreezeRecord::getOrderId, orderId)
                .eq(ComplianceFreezeRecord::getStatus, ComplianceEnums.RECORD_FROZEN)
                .orderByDesc(ComplianceFreezeRecord::getId)
                .last("LIMIT 1"));
        if (record == null) {
            return;
        }
        ComplianceFreezeRecord patch = new ComplianceFreezeRecord();
        patch.setId(record.getId());
        patch.setStatus(targetStatus);
        patch.setDisposeOperator(operator);
        patch.setDisposeTime(LocalDateTime.now());
        patch.setDisposeReason(reason == null || reason.trim().isEmpty() ? null : reason.trim());
        recordMapper.updateById(patch);
    }

    private String generateBatchNo() {
        long seq = batchMapper.nextBatchSeq();
        return String.format("FR%010d", seq);
    }
}
