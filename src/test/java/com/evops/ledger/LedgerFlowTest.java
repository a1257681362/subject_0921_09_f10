package com.evops.ledger;

import com.evops.common.BusinessException;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.OrderEditRequest;
import com.evops.ledger.dto.ReceiptRequest;
import com.evops.ledger.dto.RollbackRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.OrderEvent;
import com.evops.ledger.entity.OrderReceipt;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.entity.SlotBooking;
import com.evops.ledger.mapper.SlotBookingMapper;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.ReceiptService;
import com.evops.ledger.service.SchemaVersionService;
import com.evops.ledger.service.SiteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F1 底账核心口径端到端测试（真实 H2 + 真实事务）。 */
@SpringBootTest
class LedgerFlowTest {

    @Autowired private SiteService siteService;
    @Autowired private OrderService orderService;
    @Autowired private ReceiptService receiptService;
    @Autowired private SlotBookingMapper slotMapper;
    @Autowired private SchemaVersionService schemaVersionService;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    private Site registerSite(String no) {
        SiteCreateRequest req = new SiteCreateRequest();
        req.setSiteNo(no);
        req.setSizeSpec("18m×6m");
        req.setOrientation("朝北");
        req.setBusinessDistrict("王府井");
        req.setOperatorName("tester");
        return siteService.register(req);
    }

    private OrderCreateRequest postingReq(String siteNo, String creative, LocalDate occurDate) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(siteNo);
        req.setCreativeName(creative);
        req.setOccurDate(occurDate);
        req.setOperatorName("tester");
        return req;
    }

    private ActionRequest action(LocalDate date, String reason) {
        ActionRequest req = new ActionRequest();
        req.setOccurDate(date);
        req.setOperatorName("tester");
        req.setReason(reason);
        return req;
    }

    @Test
    void 点位编号重复存不进去() {
        String no = uniqueNo("S");
        registerSite(no);
        BusinessException ex = assertThrows(BusinessException.class, () -> registerSite(no));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void 点位档案四要素齐全才可挂牌() {
        Site site = registerSite(uniqueNo("S"));
        Site loaded = siteService.getById(site.getId());
        assertEquals("18m×6m", loaded.getSizeSpec());
        assertEquals("朝北", loaded.getOrientation());
        assertEquals("王府井", loaded.getBusinessDistrict());
        assertEquals("LISTED", loaded.getStatus());
        assertEquals("tester", loaded.getOperatorName());
    }

    @Test
    void 单号唯一且按周占档_同屏同周冲突拒绝() {
        String siteNo = uniqueNo("S");
        registerSite(siteNo);
        PostingOrder o1 = orderService.createPosting(postingReq(siteNo, "画面甲", LocalDate.of(2026, 9, 14)));
        // 9/14（周一）与 9/18（周五）同属 2026-09-14 这一周，第二张必须被拒
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createPosting(postingReq(siteNo, "画面乙", LocalDate.of(2026, 9, 18))));
        assertTrue(ex.getMessage().contains("档期"));
        // 下周（9/21）同屏可挂另一张
        PostingOrder o2 = orderService.createPosting(postingReq(siteNo, "画面丙", LocalDate.of(2026, 9, 21)));
        assertNotEquals(o1.getOrderNo(), o2.getOrderNo());
        assertTrue(o1.getOrderNo().startsWith("PO"));
        assertEquals(LocalDate.of(2026, 9, 21), o2.getWeekStart());
    }

    @Test
    void 跨月单按发生日归属不随查询日漂移() {
        String s1 = uniqueNo("S");
        String s2 = uniqueNo("S");
        registerSite(s1);
        registerSite(s2);
        // 2026-09-30 周三：归属 9 月，档期周 09-28~10-04
        PostingOrder sep = orderService.createPosting(postingReq(s1, "九月底上刊", LocalDate.of(2026, 9, 30)));
        // 另一块屏 2026-10-01 周四：同属一个 ISO 周，但归属 10 月
        PostingOrder oct = orderService.createPosting(postingReq(s2, "十月初上刊", LocalDate.of(2026, 10, 1)));
        assertEquals("2026-09", sep.getBizPeriod());
        assertEquals("2026-10", oct.getBizPeriod());
        assertEquals(sep.getWeekStart(), oct.getWeekStart());

        List<String> sepHits = orderService.search("2026-09", null, null, null)
                .stream().map(PostingOrder::getOrderNo).collect(Collectors.toList());
        List<String> octHits = orderService.search("2026-10", null, null, null)
                .stream().map(PostingOrder::getOrderNo).collect(Collectors.toList());
        assertTrue(sepHits.contains(sep.getOrderNo()) && !sepHits.contains(oct.getOrderNo()));
        assertTrue(octHits.contains(oct.getOrderNo()) && !octHits.contains(sep.getOrderNo()));
    }

    @Test
    void 落架关闭后不能静默修改且重复落架拒绝() {
        String siteNo = uniqueNo("S");
        registerSite(siteNo);
        PostingOrder order = orderService.createPosting(postingReq(siteNo, "画面", LocalDate.of(2026, 9, 14)));

        orderService.takeDown(order.getOrderNo(), action(LocalDate.of(2026, 9, 20), "档期结束"));
        assertEquals("TAKEN_DOWN", orderService.requireByNo(order.getOrderNo()).getStatus());

        OrderEditRequest edit = new OrderEditRequest();
        edit.setCreativeName("偷改画面");
        edit.setOperatorName("tester");
        BusinessException editEx = assertThrows(BusinessException.class,
                () -> orderService.edit(order.getOrderNo(), edit));
        assertTrue(editEx.getMessage().contains("已关闭"));

        BusinessException downEx = assertThrows(BusinessException.class,
                () -> orderService.takeDown(order.getOrderNo(), action(null, null)));
        assertTrue(downEx.getMessage().contains("已关闭"));
    }

    @Test
    void 改错补对冲单_原单不抹_档期释放可重上_根链可查() {
        String siteNo = uniqueNo("S");
        registerSite(siteNo);
        PostingOrder original = orderService.createPosting(postingReq(siteNo, "错画面", LocalDate.of(2026, 9, 14)));
        Long rootId = original.getId();

        PostingOrder offset = orderService.offset(original.getOrderNo(), action(LocalDate.of(2026, 9, 15), "画面版本挂错"));

        // 原单不抹：记录还在，状态已对冲
        PostingOrder reloaded = orderService.requireByNo(original.getOrderNo());
        assertEquals("OFFSET", reloaded.getStatus());
        assertEquals("OFFSET", offset.getOrderKind());
        assertEquals(rootId, offset.getRootOrderId());
        assertEquals(original.getId(), offset.getOffsetOfOrderId());
        assertEquals("2026-09", offset.getBizPeriod());

        // 档期已释放：同屏同周可重新上刊
        PostingOrder retry = orderService.createPosting(postingReq(siteNo, "正确画面", LocalDate.of(2026, 9, 15)));
        assertEquals(reloaded.getWeekStart(), retry.getWeekStart());
        SlotBooking slot = slotMapper.selectOne(new LambdaQueryWrapper<SlotBooking>()
                .eq(SlotBooking::getSiteId, reloaded.getSiteId())
                .eq(SlotBooking::getWeekStart, reloaded.getWeekStart()));
        assertEquals("ACTIVE", slot.getStatus());
        assertEquals(retry.getId(), slot.getOrderId());

        // 根链：原单 + 对冲单 + 重上单（重上单是另一根）中，原单链含两张
        List<PostingOrder> chain = orderService.chain(rootId);
        assertEquals(2, chain.size());
        Set<String> nos = chain.stream().map(PostingOrder::getOrderNo).collect(Collectors.toSet());
        assertTrue(nos.contains(original.getOrderNo()) && nos.contains(offset.getOrderNo()));
    }

    @Test
    void 外部回执给对冲单号也回溯根单() {
        String siteNo = uniqueNo("S");
        registerSite(siteNo);
        PostingOrder original = orderService.createPosting(postingReq(siteNo, "画面", LocalDate.of(2026, 9, 14)));
        PostingOrder offset = orderService.offset(original.getOrderNo(), action(LocalDate.of(2026, 9, 15), "更正"));

        ReceiptRequest req = new ReceiptRequest();
        req.setReceiptNo(uniqueNo("R"));
        req.setOrderNo(offset.getOrderNo()); // 回执指向对冲单
        req.setSource("监测平台");
        req.setContent("上刊照片回执");
        req.setOperatorName("tester");
        OrderReceipt receipt = receiptService.attach(req);

        assertEquals(offset.getId(), receipt.getOrderId());
        assertEquals(original.getId(), receipt.getRootOrderId());
        assertEquals(original.getOrderNo(), receipt.getRootOrderNo());
        // 从原单侧也能翻到这条回执
        List<OrderReceipt> all = receiptService.listByRoot(original.getId());
        assertTrue(all.stream().anyMatch(r -> r.getReceiptNo().equals(receipt.getReceiptNo())));
    }

    @Test
    void 回执编号重复存不进去() {
        String siteNo = uniqueNo("S");
        registerSite(siteNo);
        PostingOrder order = orderService.createPosting(postingReq(siteNo, "画面", LocalDate.of(2026, 9, 14)));
        String rno = uniqueNo("R");
        ReceiptRequest r1 = new ReceiptRequest();
        r1.setReceiptNo(rno);
        r1.setOrderNo(order.getOrderNo());
        r1.setOperatorName("tester");
        receiptService.attach(r1);
        assertThrows(BusinessException.class, () -> receiptService.attach(r1));
    }

    @Test
    void 版本选择与回退留痕_关闭单拒绝回退() {
        String siteNo = uniqueNo("S");
        registerSite(siteNo);
        PostingOrder order = orderService.createPosting(postingReq(siteNo, "画面v1", LocalDate.of(2026, 9, 14)));

        OrderEditRequest edit = new OrderEditRequest();
        edit.setCreativeName("画面v2");
        edit.setOperatorName("tester");
        orderService.edit(order.getOrderNo(), edit);
        assertEquals("画面v2", orderService.requireByNo(order.getOrderNo()).getCreativeName());

        RollbackRequest rb = new RollbackRequest();
        rb.setTargetVersion(1);
        rb.setOccurDate(LocalDate.of(2026, 9, 16));
        rb.setOperatorName("tester");
        rb.setReason("客户确认用回第一版");
        PostingOrder rolled = orderService.rollback(order.getOrderNo(), rb);
        assertEquals("画面v1", rolled.getCreativeName());
        assertEquals(3, rolled.getVersionNo()); // 回退不覆盖历史，另起新版本

        List<OrderEvent> events = orderService.events(order.getId());
        List<String> types = events.stream().map(OrderEvent::getEventType).collect(Collectors.toList());
        assertTrue(types.contains("CREATE") && types.contains("EDIT") && types.contains("ROLLBACK"));
        OrderEvent create = events.get(0);
        assertNotNull(create.getSnapshot());
        assertEquals("tester", create.getOperatorName());
        assertEquals(LocalDate.of(2026, 9, 14), create.getOccurDate());

        // 回退到非历史版本拒绝
        RollbackRequest bad = new RollbackRequest();
        bad.setTargetVersion(3);
        assertThrows(BusinessException.class, () -> orderService.rollback(order.getOrderNo(), bad));

        // 关闭后拒绝回退
        orderService.takeDown(order.getOrderNo(), action(LocalDate.of(2026, 9, 20), null));
        RollbackRequest afterClose = new RollbackRequest();
        afterClose.setTargetVersion(1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.rollback(order.getOrderNo(), afterClose));
        assertTrue(ex.getMessage().contains("已关闭"));
    }

    @Test
    void 落架释放档期后同周可重新上刊() {
        String siteNo = uniqueNo("S");
        registerSite(siteNo);
        PostingOrder o1 = orderService.createPosting(postingReq(siteNo, "档期甲", LocalDate.of(2026, 9, 7)));
        orderService.takeDown(o1.getOrderNo(), action(LocalDate.of(2026, 9, 8), "提前下画"));
        PostingOrder o2 = orderService.createPosting(postingReq(siteNo, "档期乙", LocalDate.of(2026, 9, 9)));
        assertEquals(o1.getWeekStart(), o2.getWeekStart());
        assertEquals("HUNG", o2.getStatus());
    }

    @Test
    void 底账版本登记包含f1起步版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("base-0"));
        assertTrue(versions.contains("f1-ledger-1"));
    }
}
