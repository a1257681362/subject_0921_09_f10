package com.evops.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.OrderEditRequest;
import com.evops.ledger.dto.ReceiptRequest;
import com.evops.ledger.dto.RollbackRequest;
import com.evops.ledger.entity.OrderEvent;
import com.evops.ledger.entity.OrderReceipt;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.entity.SlotBooking;
import com.evops.ledger.mapper.OrderEventMapper;
import com.evops.ledger.mapper.OrderReceiptMapper;
import com.evops.ledger.mapper.PostingOrderMapper;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.mapper.SlotBookingMapper;
import com.evops.ledger.support.Operators;
import com.evops.ledger.support.PostingComplianceGateway;
import com.evops.ledger.support.PostingCreditGateway;
import com.evops.ledger.support.PostingInstallGateway;
import com.evops.ledger.support.WeekRanges;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 上刊单核心流程：
 * 上刊（按周占档）→ 挂起；落架 / 对冲（不抹记录，补对冲单并释放档期）→ 关闭；
 * 关闭后的单据拒绝任何修改与静默回退；每一步写事件留底（经手人、发生日、快照）。
 */
@Service
public class OrderService {

    private final PostingOrderMapper orderMapper;
    private final SlotBookingMapper slotMapper;
    private final OrderEventMapper eventMapper;
    private final OrderReceiptMapper receiptMapper;
    private final SiteMapper siteMapper;
    private final ObjectMapper objectMapper;
    /** 授信域可选实现：无授信模块时取不到，上刊流程与 F1 完全一致（普通单不占额度） */
    private final ObjectProvider<PostingCreditGateway> creditGatewayProvider;
    /** 安装派单域可选实现：无安装模块时取不到，上刊流程与 F1 完全一致 */
    private final ObjectProvider<PostingInstallGateway> installGatewayProvider;
    /** 受限行业名单域可选实现：无合规模块时取不到，普通单不做类目校验 */
    private final ObjectProvider<PostingComplianceGateway> complianceGatewayProvider;

    public OrderService(PostingOrderMapper orderMapper, SlotBookingMapper slotMapper,
                        OrderEventMapper eventMapper, OrderReceiptMapper receiptMapper,
                        SiteMapper siteMapper, ObjectMapper objectMapper,
                        ObjectProvider<PostingCreditGateway> creditGatewayProvider,
                        ObjectProvider<PostingInstallGateway> installGatewayProvider,
                        ObjectProvider<PostingComplianceGateway> complianceGatewayProvider) {
        this.orderMapper = orderMapper;
        this.slotMapper = slotMapper;
        this.eventMapper = eventMapper;
        this.receiptMapper = receiptMapper;
        this.siteMapper = siteMapper;
        this.objectMapper = objectMapper;
        this.creditGatewayProvider = creditGatewayProvider;
        this.installGatewayProvider = installGatewayProvider;
        this.complianceGatewayProvider = complianceGatewayProvider;
    }

    /** 上刊：单号唯一、按周占档（同点位同周冲突拒绝）、归属月按发生日固化。 */
    @Transactional(rollbackFor = Exception.class)
    public PostingOrder createPosting(OrderCreateRequest request) {
        Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>()
                .eq(Site::getSiteNo, request.getSiteNo().trim()));
        if (site == null) {
            throw new BusinessException("点位不存在: " + request.getSiteNo());
        }
        LocalDate occurDate = request.getOccurDate() != null ? request.getOccurDate() : LocalDate.now();
        LocalDate weekStart = WeekRanges.mondayOf(occurDate);
        LocalDate weekEnd = WeekRanges.sundayOf(occurDate);
        String operator = Operators.resolve(request.getOperatorName());

        // F10 行业类目：普通单必填并在提交口校验受限名单（命中当场拒，单据档期都不落）；
        // 授信单取客户档案类目，由授信网关在占用额度前校验并回写快照
        boolean creditPosting = request.getQuoteNo() != null && !request.getQuoteNo().trim().isEmpty();
        String declaredCategory = request.getIndustryCategory() == null ? null : request.getIndustryCategory().trim();
        if (!creditPosting) {
            if (declaredCategory == null || declaredCategory.isEmpty()) {
                throw new BusinessException("普通上刊单必须填报行业类目（受限行业名单校验需要）");
            }
            PostingComplianceGateway complianceGateway = complianceGatewayProvider.getIfAvailable();
            if (complianceGateway != null) {
                complianceGateway.requireCategoryAllowed(declaredCategory);
            }
        }

        PostingOrder order = new PostingOrder();
        order.setOrderNo(generateOrderNo());
        order.setOrderKind(LedgerEnums.KIND_POSTING);
        order.setSiteId(site.getId());
        order.setCreativeName(request.getCreativeName().trim());
        order.setWeekStart(weekStart);
        order.setWeekEnd(weekEnd);
        order.setOccurDate(occurDate);
        // 归属期间随发生日落死，之后查询日怎么变都不漂移
        order.setBizPeriod(WeekRanges.periodOf(occurDate));
        order.setStatus(LedgerEnums.ORDER_HUNG);
        order.setVersionNo(1);
        // 默认普通单不占授信；引用报价单时由授信网关回写为 1（显式置 0，避免只靠库默认值导致实体为 null）
        order.setCreditUsed(0);
        // 合规状态默认正常；普通单类目快照随单落库固化
        order.setComplianceStatus(LedgerEnums.COMPLIANCE_NORMAL);
        if (!creditPosting) {
            order.setIndustryCategory(declaredCategory);
        }
        order.setOperatorName(operator);
        order.setRemark(request.getRemark());
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("单号冲突，请重试");
        }
        // 根单指向自身；后续对冲单共享该根
        order.setRootOrderId(order.getId());
        orderMapper.updateById(order);

        occupySlot(site.getId(), weekStart, weekEnd, order);

        // 引用报价单即为授信单：提交口校验并占用客户额度，余额不足/冻结直接整体回滚（单据、档期都不落）
        if (request.getQuoteNo() != null && !request.getQuoteNo().trim().isEmpty()) {
            PostingCreditGateway gateway = creditGatewayProvider.getIfAvailable();
            if (gateway == null) {
                throw new BusinessException("授信模块未启用，不能引用报价单占用额度");
            }
            gateway.occupyFor(order, request.getQuoteNo(), operator);
        }

        recordEvent(order, LedgerEnums.EVENT_CREATE, occurDate, operator, null,
                "开单上刊，画面「" + order.getCreativeName() + "」挂起"
                        + (order.getQuoteNo() != null ? "，授信占用报价单 " + order.getQuoteNo() : ""));

        // 上刊确认：自动生成安装派单并按资质+档期匹配（无安装模块时取不到实现，跳过；无合格空闲队落待匹配，不阻断上刊）
        PostingInstallGateway installGateway = installGatewayProvider.getIfAvailable();
        if (installGateway != null) {
            installGateway.onPostingConfirmed(order, operator);
        }
        return order;
    }

    /** 落架：仅挂起中的单可落架；落架即关闭，之后不能再改。合规冻结中的单只能走合规强制落架。 */
    @Transactional(rollbackFor = Exception.class)
    public PostingOrder takeDown(String orderNo, ActionRequest request) {
        PostingOrder order = requireByNo(orderNo);
        requireHung(order, "落架");
        requireNotFrozen(order, "落架");
        LocalDate occurDate = request.getOccurDate() != null ? request.getOccurDate() : LocalDate.now();
        String operator = Operators.resolve(request.getOperatorName());
        return doTakeDown(order, occurDate, operator,
                request.getReason() != null ? request.getReason() : "画面落架");
    }

    /** 落架主流程：关闭单据、释放档期、授信释放转应收、写事件。 */
    private PostingOrder doTakeDown(PostingOrder order, LocalDate occurDate, String operator, String detail) {
        order.setStatus(LedgerEnums.ORDER_TAKEN_DOWN);
        order.setClosedTime(LocalDateTime.now());
        orderMapper.updateById(order);
        releaseSlot(order);
        // 授信单落架：撤刊即释放额度，未核销额转应收继续按单核销；普通单无占用行，内部直接跳过
        releaseCredit(order, false, operator);
        recordEvent(order, LedgerEnums.EVENT_TAKE_DOWN, occurDate, operator, null, detail);
        return order;
    }

    /**
     * 对冲：原单不抹不删，置“已对冲”并释放当周档期；
     * 另补一张对冲单（root 指回原单根单），两张单的事件互相留痕。
     */
    @Transactional(rollbackFor = Exception.class)
    public PostingOrder offset(String orderNo, ActionRequest request) {
        PostingOrder original = requireByNo(orderNo);
        requireHung(original, "对冲");
        requireNotFrozen(original, "对冲");
        LocalDate occurDate = request.getOccurDate() != null ? request.getOccurDate() : LocalDate.now();
        String operator = Operators.resolve(request.getOperatorName());
        String reason = request.getReason() != null && !request.getReason().trim().isEmpty()
                ? request.getReason().trim() : "业务更正";

        original.setStatus(LedgerEnums.ORDER_OFFSET);
        original.setOffsetReason(reason);
        original.setClosedTime(LocalDateTime.now());
        orderMapper.updateById(original);
        releaseSlot(original);
        // 授信单对冲：释放额度但属更正，不形成应收（REVERSED）；已有回款核销的单禁止对冲
        releaseCredit(original, true, operator);
        recordEvent(original, LedgerEnums.EVENT_OFFSET, occurDate, operator, null,
                "原单已对冲，补对冲单，原因：" + reason);

        PostingOrder offsetOrder = new PostingOrder();
        offsetOrder.setOrderNo(generateOrderNo());
        offsetOrder.setOrderKind(LedgerEnums.KIND_OFFSET);
        offsetOrder.setRootOrderId(original.getRootOrderId());
        offsetOrder.setOffsetOfOrderId(original.getId());
        offsetOrder.setSiteId(original.getSiteId());
        offsetOrder.setCreativeName(original.getCreativeName());
        offsetOrder.setWeekStart(original.getWeekStart());
        offsetOrder.setWeekEnd(original.getWeekEnd());
        offsetOrder.setOccurDate(occurDate);
        offsetOrder.setBizPeriod(WeekRanges.periodOf(occurDate));
        // 对冲单开立即完成的更正凭证，不占档期，不再处于挂起
        offsetOrder.setStatus(LedgerEnums.ORDER_TAKEN_DOWN);
        offsetOrder.setVersionNo(1);
        offsetOrder.setOperatorName(operator);
        offsetOrder.setOffsetReason(reason);
        offsetOrder.setClosedTime(LocalDateTime.now());
        offsetOrder.setRemark("对冲单，原单 " + original.getOrderNo());
        try {
            orderMapper.insert(offsetOrder);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("单号冲突，请重试");
        }
        recordEvent(offsetOrder, LedgerEnums.EVENT_CREATE, occurDate, operator,
                original.getOrderNo(), "对冲补单，对冲原单 " + original.getOrderNo() + "，原因：" + reason);

        // 原单对冲更正：联动取消其未完工的安装派单并释放队档期（已完工保留；无安装模块跳过）
        PostingInstallGateway installGateway = installGatewayProvider.getIfAvailable();
        if (installGateway != null) {
            installGateway.onPostingOffset(original, operator);
        }
        return offsetOrder;
    }

    /** 挂起中单据的留痕更正（画面名/备注）。关闭后的单据拒绝修改。 */
    @Transactional(rollbackFor = Exception.class)
    public PostingOrder edit(String orderNo, OrderEditRequest request) {
        PostingOrder order = requireByNo(orderNo);
        requireHung(order, "修改");
        requireNotFrozen(order, "修改");
        String operator = Operators.resolve(request.getOperatorName());

        order.setCreativeName(request.getCreativeName().trim());
        order.setRemark(request.getRemark());
        order.setVersionNo(order.getVersionNo() + 1);
        orderMapper.updateById(order);
        recordEvent(order, LedgerEnums.EVENT_EDIT, LocalDate.now(), operator, null,
                "单据更正，版本升至 v" + order.getVersionNo());
        return order;
    }

    /**
     * 版本选择与回退：把挂起中单据恢复到某个历史版本快照；
     * 回退不删历史，另写一条 ROLLBACK 事件并升版本。关闭后的单据拒绝回退。
     */
    @Transactional(rollbackFor = Exception.class)
    public PostingOrder rollback(String orderNo, RollbackRequest request) {
        PostingOrder order = requireByNo(orderNo);
        requireHung(order, "版本回退");
        requireNotFrozen(order, "版本回退");
        int target = request.getTargetVersion();
        if (target >= order.getVersionNo()) {
            throw new BusinessException("目标版本 v" + target + " 不是历史版本，当前版本 v" + order.getVersionNo());
        }
        OrderEvent targetEvent = eventMapper.selectOne(new LambdaQueryWrapper<OrderEvent>()
                .eq(OrderEvent::getOrderId, order.getId())
                .eq(OrderEvent::getVersionNo, target)
                .orderByDesc(OrderEvent::getId)
                .last("LIMIT 1"));
        if (targetEvent == null || targetEvent.getSnapshot() == null) {
            throw new BusinessException("找不到版本 v" + target + " 的留底快照，无法回退");
        }
        PostingOrder snapshot;
        try {
            snapshot = objectMapper.readValue(targetEvent.getSnapshot(), PostingOrder.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("版本快照解析失败", e);
        }
        LocalDate occurDate = request.getOccurDate() != null ? request.getOccurDate() : LocalDate.now();
        String operator = Operators.resolve(request.getOperatorName());

        order.setCreativeName(snapshot.getCreativeName());
        order.setRemark(snapshot.getRemark());
        order.setVersionNo(order.getVersionNo() + 1);
        orderMapper.updateById(order);
        recordEvent(order, LedgerEnums.EVENT_ROLLBACK, occurDate, operator, null,
                "回退至 v" + target + " 的留底内容，版本升至 v" + order.getVersionNo()
                        + (request.getReason() != null ? "；" + request.getReason() : ""));
        return order;
    }

    public PostingOrder requireByNo(String orderNo) {
        PostingOrder order = orderMapper.selectOne(new LambdaQueryWrapper<PostingOrder>()
                .eq(PostingOrder::getOrderNo, orderNo.trim()));
        if (order == null) {
            throw new BusinessException("单据不存在: " + orderNo);
        }
        return order;
    }

    /** 供回执回溯根单使用。 */
    public PostingOrder requireById(Long id) {
        PostingOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("单据不存在: id=" + id);
        }
        return order;
    }

    /** 翻查：归属月 / 点位 / 状态 / 单号 / 根单链 / 合规状态。归属月查的是固化列，不受查询日影响。 */
    public List<PostingOrder> search(String period, String siteNo, String status, String orderNo, String compliance) {
        LambdaQueryWrapper<PostingOrder> wrapper = new LambdaQueryWrapper<>();
        if (period != null && !period.trim().isEmpty()) {
            wrapper.eq(PostingOrder::getBizPeriod, period.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(PostingOrder::getStatus, status.trim());
        }
        if (compliance != null && !compliance.trim().isEmpty()) {
            wrapper.eq(PostingOrder::getComplianceStatus, compliance.trim());
        }
        if (orderNo != null && !orderNo.trim().isEmpty()) {
            wrapper.like(PostingOrder::getOrderNo, orderNo.trim());
        }
        if (siteNo != null && !siteNo.trim().isEmpty()) {
            Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>()
                    .eq(Site::getSiteNo, siteNo.trim()));
            if (site == null) {
                return java.util.Collections.emptyList();
            }
            wrapper.eq(PostingOrder::getSiteId, site.getId());
        }
        wrapper.orderByDesc(PostingOrder::getId);
        return orderMapper.selectList(wrapper);
    }

    /** 同根单链：原单与它的对冲单一起翻出来。 */
    public List<PostingOrder> chain(Long rootOrderId) {
        return orderMapper.selectList(new LambdaQueryWrapper<PostingOrder>()
                .eq(PostingOrder::getRootOrderId, rootOrderId)
                .orderByAsc(PostingOrder::getId));
    }

    public List<OrderEvent> events(Long orderId) {
        return eventMapper.selectList(new LambdaQueryWrapper<OrderEvent>()
                .eq(OrderEvent::getOrderId, orderId)
                .orderByAsc(OrderEvent::getId));
    }

    // ---- 内部方法 ----

    private void requireHung(PostingOrder order, String action) {
        if (!LedgerEnums.ORDER_HUNG.equals(order.getStatus())) {
            throw new BusinessException("单据 " + order.getOrderNo() + " 已关闭（"
                    + statusText(order.getStatus()) + "），不能" + action
                    + "；如需更正请另开对冲单");
        }
    }

    /** F10：合规冻结中的单，普通操作（落架/对冲/更正/回退）一律拦，只能由合规岗处置。 */
    private void requireNotFrozen(PostingOrder order, String action) {
        if (LedgerEnums.COMPLIANCE_FROZEN.equals(order.getComplianceStatus())) {
            throw new BusinessException("单据 " + order.getOrderNo() + " 合规冻结中，不能" + action
                    + "；请联系合规岗处置（解冻或强制落架）");
        }
    }

    // ---- F10 合规冻结处置（由合规模块编排调用） ----

    /**
     * 合规批量冻结：原子条件更新置 FROZEN（已冻结/已关闭的单跳过，并发与重复触发都幂等），
     * 成功写 COMPLIANCE_FREEZE 事件留底。返回是否真正冻结（false=该单已不可冻，跳过）。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markComplianceFrozen(PostingOrder order, String batchNo, String category, String basis, String operator) {
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<PostingOrder>()
                .eq(PostingOrder::getId, order.getId())
                .eq(PostingOrder::getStatus, LedgerEnums.ORDER_HUNG)
                .eq(PostingOrder::getComplianceStatus, LedgerEnums.COMPLIANCE_NORMAL)
                .set(PostingOrder::getComplianceStatus, LedgerEnums.COMPLIANCE_FROZEN));
        if (updated == 0) {
            return false;
        }
        order.setComplianceStatus(LedgerEnums.COMPLIANCE_FROZEN);
        recordEvent(order, LedgerEnums.EVENT_COMPLIANCE_FREEZE, LocalDate.now(), operator, null,
                "合规冻结（批次 " + batchNo + "）：行业类目「" + category + "」在受限名单内，依据：" + basis);
        return true;
    }

    /** 合规解冻：恢复 NORMAL 并写事件留底；不在冻结状态直接拒。 */
    @Transactional(rollbackFor = Exception.class)
    public void markComplianceUnfrozen(PostingOrder order, String operator, String reason) {
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<PostingOrder>()
                .eq(PostingOrder::getId, order.getId())
                .eq(PostingOrder::getComplianceStatus, LedgerEnums.COMPLIANCE_FROZEN)
                .set(PostingOrder::getComplianceStatus, LedgerEnums.COMPLIANCE_NORMAL));
        if (updated == 0) {
            throw new BusinessException("单据 " + order.getOrderNo() + " 不在合规冻结状态，不能解冻");
        }
        order.setComplianceStatus(LedgerEnums.COMPLIANCE_NORMAL);
        recordEvent(order, LedgerEnums.EVENT_COMPLIANCE_UNFREEZE, LocalDate.now(), operator, null,
                "合规解冻" + (reason != null && !reason.trim().isEmpty() ? "：" + reason.trim() : ""));
    }

    /**
     * 合规强制落架：冻结单的处置出口之一。走正常落架主流程（释放档期、授信释放转应收），
     * 事件注明合规强制；单据关闭后合规状态保留 FROZEN 留痕（关闭即冻结，不再影响任何操作）。
     */
    @Transactional(rollbackFor = Exception.class)
    public PostingOrder forceTakeDown(String orderNo, String operator, String reason) {
        PostingOrder order = requireByNo(orderNo);
        requireHung(order, "强制落架");
        if (!LedgerEnums.COMPLIANCE_FROZEN.equals(order.getComplianceStatus())) {
            throw new BusinessException("单据 " + order.getOrderNo() + " 不在合规冻结状态，无需强制落架，请走正常落架");
        }
        return doTakeDown(order, LocalDate.now(), operator,
                "合规强制落架" + (reason != null && !reason.trim().isEmpty() ? "：" + reason.trim() : ""));
    }

    private String statusText(String status) {
        if (LedgerEnums.ORDER_TAKEN_DOWN.equals(status)) {
            return "已落架";
        }
        if (LedgerEnums.ORDER_OFFSET.equals(status)) {
            return "已对冲";
        }
        return status;
    }

    private String generateOrderNo() {
        long seq = orderMapper.nextOrderSeq();
        return String.format("PO%010d", seq);
    }

    /** 按周占档：同一屏同一周唯一 ACTIVE；释放过的行用条件更新抢回，并发冲突走唯一约束兜底。 */
    private void occupySlot(Long siteId, LocalDate weekStart, LocalDate weekEnd, PostingOrder order) {
        SlotBooking existing = slotMapper.selectOne(new LambdaQueryWrapper<SlotBooking>()
                .eq(SlotBooking::getSiteId, siteId)
                .eq(SlotBooking::getWeekStart, weekStart));
        if (existing == null) {
            SlotBooking slot = new SlotBooking();
            slot.setSiteId(siteId);
            slot.setWeekStart(weekStart);
            slot.setWeekEnd(weekEnd);
            slot.setStatus(LedgerEnums.SLOT_ACTIVE);
            slot.setOrderId(order.getId());
            slot.setOrderNo(order.getOrderNo());
            try {
                slotMapper.insert(slot);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("该点位 " + weekStart + " 当周档期已被占用，同屏同周不能挂两张画面");
            }
            return;
        }
        if (LedgerEnums.SLOT_ACTIVE.equals(existing.getStatus())) {
            throw new BusinessException("该点位 " + weekStart + " 当周档期已被单 "
                    + existing.getOrderNo() + " 占用，同屏同周不能挂两张画面");
        }
        int updated = slotMapper.update(null, new LambdaUpdateWrapper<SlotBooking>()
                .eq(SlotBooking::getId, existing.getId())
                .eq(SlotBooking::getStatus, LedgerEnums.SLOT_RELEASED)
                .set(SlotBooking::getStatus, LedgerEnums.SLOT_ACTIVE)
                .set(SlotBooking::getOrderId, order.getId())
                .set(SlotBooking::getOrderNo, order.getOrderNo()));
        if (updated == 0) {
            throw new BusinessException("该点位 " + weekStart + " 当周档期刚被占用，请刷新后重试");
        }
    }

    private void releaseSlot(PostingOrder order) {
        slotMapper.update(null, new LambdaUpdateWrapper<SlotBooking>()
                .eq(SlotBooking::getSiteId, order.getSiteId())
                .eq(SlotBooking::getWeekStart, order.getWeekStart())
                .eq(SlotBooking::getOrderId, order.getId())
                .eq(SlotBooking::getStatus, LedgerEnums.SLOT_ACTIVE)
                .set(SlotBooking::getStatus, LedgerEnums.SLOT_RELEASED));
    }

    /** 授信单撤刊/对冲释放额度；普通单或授信模块缺失时不做任何事，保持 F1 原行为。 */
    private void releaseCredit(PostingOrder order, boolean reversed, String operator) {
        PostingCreditGateway gateway = creditGatewayProvider.getIfAvailable();
        if (gateway == null) {
            return;
        }
        if (order.getCreditUsed() != null && order.getCreditUsed() == 1) {
            gateway.releaseFor(order, reversed, operator);
        }
    }

    private void recordEvent(PostingOrder order, String eventType, LocalDate occurDate,
                             String operator, String relatedOrderNo, String detail) {
        OrderEvent event = new OrderEvent();
        event.setOrderId(order.getId());
        event.setOrderNo(order.getOrderNo());
        event.setRootOrderId(order.getRootOrderId());
        event.setEventType(eventType);
        event.setVersionNo(order.getVersionNo());
        event.setOccurDate(occurDate);
        event.setOperatorName(operator);
        event.setRelatedOrderNo(relatedOrderNo);
        event.setDetail(detail);
        event.setSnapshot(toSnapshot(order));
        event.setEventTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private String toSnapshot(PostingOrder order) {
        try {
            return objectMapper.writeValueAsString(order);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("单据快照写入失败", e);
        }
    }
}
