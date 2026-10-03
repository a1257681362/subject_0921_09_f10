package com.evops.rectify;

import com.evops.common.BusinessException;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.SiteService;
import com.evops.rectify.dto.RectifyDetailView;
import com.evops.rectify.dto.RectifyDispatchRequest;
import com.evops.rectify.dto.RectifyReinspectRequest;
import com.evops.rectify.dto.RectifyRepairRequest;
import com.evops.rectify.dto.RectifyReportRequest;
import com.evops.rectify.entity.RectifyCc;
import com.evops.rectify.entity.RectifyOrder;
import com.evops.rectify.entity.RectifyPhoto;
import com.evops.rectify.service.RectifyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F6 异常上报整改端到端（真实 H2 + 真实事务，无 Shiro，经手人显式传）。
 * 覆盖：上报（照片可选）→ 派单给期限 → 回传 → 复勘驳回重处理/通过销项、
 * 超期逐级抄送（幂等）、照片关闭前冻结、点位历史异常、事件版本留底。
 */
@SpringBootTest
class RectifyFlowTest {

    @Autowired private RectifyService rectifyService;
    @Autowired private SiteService siteService;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    private Site registerSite() {
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("RECT-SITE"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝北");
        sr.setBusinessDistrict("整改测试商圈");
        sr.setOperatorName("tester");
        return siteService.register(sr);
    }

    private RectifyReportRequest reportReq(Site site, String type) {
        RectifyReportRequest req = new RectifyReportRequest();
        req.setSiteNo(site.getSiteNo());
        req.setAbnormalType(type);
        req.setAbnormalDesc("上周三大风，三块牌面翘边");
        req.setReporterName("inspector1");
        return req;
    }

    private MockMultipartFile[] photos(String name) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return new MockMultipartFile[] {
                new MockMultipartFile("photos", name, "image/png", bos.toByteArray())
        };
    }

    private RectifyDispatchRequest dispatchReq() {
        RectifyDispatchRequest req = new RectifyDispatchRequest();
        req.setVendorName("顺风维护商");
        req.setVendorContact("王师傅 13800000000");
        req.setOperatorName("dispatcher");
        return req;
    }

    // ---- 上报 ----

    @Test
    void report_withoutPhoto_createsReportedOrderWithEvent() {
        Site site = registerSite();
        RectifyOrder order = rectifyService.report(reportReq(site, RectifyEnums.TYPE_PANEL_LIFT), null);

        assertTrue(order.getRectifyNo().startsWith("RC"));
        assertEquals(RectifyEnums.STATUS_REPORTED, order.getStatus());
        assertEquals(1, order.getVersionNo());
        assertEquals("inspector1", order.getReporterName());

        RectifyDetailView detail = rectifyService.detail(order.getRectifyNo());
        assertEquals(1, detail.getEvents().size());
        assertEquals(RectifyEnums.EVENT_REPORT, detail.getEvents().get(0).getEventType());
        assertNotNull(detail.getEvents().get(0).getSnapshot());
        assertTrue(detail.getPhotos().isEmpty());
    }

    @Test
    void report_withPhoto_storesEvidenceAndCanReadBack() throws Exception {
        Site site = registerSite();
        RectifyOrder order = rectifyService.report(
                reportReq(site, RectifyEnums.TYPE_PANEL_LIFT), photos("lift-01.png"));

        RectifyDetailView detail = rectifyService.detail(order.getRectifyNo());
        assertEquals(1, detail.getPhotos().size());
        RectifyPhoto photo = detail.getPhotos().get(0);
        assertEquals(RectifyEnums.PHASE_REPORT, photo.getPhase());
        assertEquals(64, photo.getFingerprintSha256().length());
        byte[] bytes = rectifyService.readPhotoBytes(photo);
        assertTrue(bytes.length > 0);
    }

    @Test
    void report_unknownSiteOrBadType_rejected() {
        RectifyReportRequest badSite = reportReq(registerSite(), RectifyEnums.TYPE_PANEL_LIFT);
        badSite.setSiteNo("NO-SUCH-SITE");
        assertThrows(BusinessException.class, () -> rectifyService.report(badSite, null));

        Site site = registerSite();
        assertThrows(BusinessException.class,
                () -> rectifyService.report(reportReq(site, "UFO"), null));
    }

    // ---- 派单 / 状态机 ----

    @Test
    void dispatch_setsVendorDeadlineAndBumpsVersion() {
        Site site = registerSite();
        RectifyOrder order = rectifyService.report(reportReq(site, RectifyEnums.TYPE_LIGHT_FAULT), null);
        order = rectifyService.dispatch(order.getRectifyNo(), dispatchReq());

        assertEquals(RectifyEnums.STATUS_DISPATCHED, order.getStatus());
        assertEquals(2, order.getVersionNo());
        assertEquals("顺风维护商", order.getVendorName());
        assertNotNull(order.getDispatchTime());
        assertNotNull(order.getDeadline());
        assertTrue(order.getDeadline().isAfter(order.getDispatchTime()));
    }

    @Test
    void illegalTransitions_rejected() {
        Site site = registerSite();
        RectifyOrder order = rectifyService.report(reportReq(site, RectifyEnums.TYPE_OTHER), null);

        // 已上报状态不能直接回传/复勘
        RectifyRepairRequest repair = new RectifyRepairRequest();
        repair.setRepairResult("已修好");
        repair.setOperatorName("vendor1");
        assertThrows(BusinessException.class,
                () -> rectifyService.repair(order.getRectifyNo(), repair, null));
        assertThrows(BusinessException.class,
                () -> rectifyService.reinspectPass(order.getRectifyNo(), new RectifyReinspectRequest()));

        // 派过一次不能重复派
        rectifyService.dispatch(order.getRectifyNo(), dispatchReq());
        final String no = order.getRectifyNo();
        assertThrows(BusinessException.class,
                () -> rectifyService.dispatch(no, dispatchReq()));
    }

    // ---- 回传 / 复勘 / 销项 ----

    @Test
    void repairThenReinspectPass_closesAndFreezes() throws Exception {
        Site site = registerSite();
        RectifyOrder order = rectifyService.report(
                reportReq(site, RectifyEnums.TYPE_PANEL_DAMAGE), photos("lift-before.png"));
        rectifyService.dispatch(order.getRectifyNo(), dispatchReq());

        RectifyRepairRequest repair = new RectifyRepairRequest();
        repair.setRepairResult("更换破损牌面，加固压条");
        repair.setOperatorName("vendor1");
        order = rectifyService.repair(order.getRectifyNo(), repair, photos("fixed-01.png"));
        assertEquals(RectifyEnums.STATUS_REPAIRED, order.getStatus());
        assertEquals(3, order.getVersionNo());

        RectifyReinspectRequest pass = new RectifyReinspectRequest();
        pass.setComment("现场复勘合格");
        pass.setOperatorName("inspector1");
        order = rectifyService.reinspectPass(order.getRectifyNo(), pass);
        assertEquals(RectifyEnums.STATUS_CLOSED, order.getStatus());
        assertEquals(4, order.getVersionNo());
        assertNotNull(order.getClosedTime());

        RectifyDetailView detail = rectifyService.detail(order.getRectifyNo());
        assertEquals(4, detail.getEvents().size());
        // 上报 + 整改 两阶段照片各一张
        assertEquals(2, detail.getPhotos().size());

        // 关闭即冻结：回传/复勘/派单/补传照片全部拒收
        final String closedNo = order.getRectifyNo();
        assertThrows(BusinessException.class,
                () -> rectifyService.repair(closedNo, repair, null));
        assertThrows(BusinessException.class,
                () -> rectifyService.reinspectPass(closedNo, pass));
    }

    @Test
    void reinspectReject_requiresReasonAndReturnsToDispatchedWithNewDeadline() {
        Site site = registerSite();
        RectifyOrder order = rectifyService.report(reportReq(site, RectifyEnums.TYPE_PANEL_LIFT), null);
        rectifyService.dispatch(order.getRectifyNo(), dispatchReq());

        RectifyRepairRequest repair = new RectifyRepairRequest();
        repair.setRepairResult("压了一下");
        repair.setOperatorName("vendor1");
        order = rectifyService.repair(order.getRectifyNo(), repair, null);

        // 驳回不写原因直接拒
        RectifyReinspectRequest noReason = new RectifyReinspectRequest();
        noReason.setOperatorName("inspector1");
        final String repairedNo = order.getRectifyNo();
        assertThrows(BusinessException.class,
                () -> rectifyService.reinspectReject(repairedNo, noReason));

        RectifyReinspectRequest reject = new RectifyReinspectRequest();
        reject.setComment("南侧第三块仍翘边，返工");
        reject.setOperatorName("inspector1");
        LocalDateTime oldDeadline = order.getDeadline();
        order = rectifyService.reinspectReject(order.getRectifyNo(), reject);

        assertEquals(RectifyEnums.STATUS_DISPATCHED, order.getStatus());
        assertEquals(4, order.getVersionNo());
        assertNotNull(order.getDeadline());
        assertNotEquals(oldDeadline, order.getDeadline());

        // 返工后再回传、复勘通过销项
        order = rectifyService.repair(order.getRectifyNo(), repair, null);
        assertEquals(RectifyEnums.STATUS_REPAIRED, order.getStatus());
        order = rectifyService.reinspectPass(order.getRectifyNo(), noReason);
        assertEquals(RectifyEnums.STATUS_CLOSED, order.getStatus());
    }

    // ---- 超期逐级抄送 ----

    @Test
    void escalation_goesUpLevelByLevel_andIsIdempotent() {
        Site site = registerSite();
        RectifyOrder order = rectifyService.report(reportReq(site, RectifyEnums.TYPE_STRUCTURE_RISK), null);
        order = rectifyService.dispatch(order.getRectifyNo(), dispatchReq());
        final String no = order.getRectifyNo();

        // 期限没过：本单没有任何抄送
        rectifyService.escalateDue(LocalDateTime.now());
        assertTrue(rectifyService.detail(no).getCcs().isEmpty());

        // 刚过期限：抄第 1 级（维护主管，阶梯 afterHours=0）
        LocalDateTime atLevel1 = order.getDeadline().plusMinutes(1);
        rectifyService.escalateDue(atLevel1);
        // 重复扫描不重发
        rectifyService.escalateDue(atLevel1.plusMinutes(10));

        RectifyDetailView detail = rectifyService.detail(no);
        assertEquals(1, detail.getCcs().size());
        assertEquals(1, detail.getCcs().get(0).getCcLevel());
        assertEquals("维护主管", detail.getCcs().get(0).getCcTarget());
        assertTrue(detail.getEvents().stream()
                .anyMatch(e -> RectifyEnums.EVENT_ESCALATE.equals(e.getEventType())));

        // 超过期限 50 小时：补抄到第 2 级（运营经理，48h）
        rectifyService.escalateDue(order.getDeadline().plusHours(50));
        // 超过期限 200 小时：补抄到顶（分管总监，120h），一次补齐剩余台阶
        rectifyService.escalateDue(order.getDeadline().plusHours(200));
        detail = rectifyService.detail(no);
        assertEquals(3, detail.getCcs().size());
        List<Integer> levels = detail.getCcs().stream()
                .map(RectifyCc::getCcLevel).collect(Collectors.toList());
        assertEquals(java.util.Arrays.asList(1, 2, 3), levels);
        assertEquals(3, rectifyService.requireByNo(no).getCcLevel());

        // 回传后状态离开 DISPATCHED，不再被抄送扫描捞到
        RectifyRepairRequest repair = new RectifyRepairRequest();
        repair.setRepairResult("结构加固完成");
        repair.setOperatorName("vendor1");
        rectifyService.repair(no, repair, null);
        int ccCountAfterRepair = rectifyService.detail(no).getCcs().size();
        rectifyService.escalateDue(order.getDeadline().plusHours(400));
        assertEquals(ccCountAfterRepair, rectifyService.detail(no).getCcs().size());
    }

    // ---- 留证照片冻结 ----

    @Test
    void photosCannotBeDeletedBeforeClose_butCanAfterWithEvent() throws Exception {
        Site site = registerSite();
        RectifyOrder order = rectifyService.report(
                reportReq(site, RectifyEnums.TYPE_PANEL_LIFT), photos("a.png"));
        Long photoId = rectifyService.detail(order.getRectifyNo()).getPhotos().get(0).getId();

        // 关闭前删：直接拒
        assertThrows(BusinessException.class,
                () -> rectifyService.deletePhoto(photoId, "inspector1"));

        // 关闭前可以补传现场照片
        List<RectifyPhoto> added = rectifyService.addPhotos(
                order.getRectifyNo(), RectifyEnums.PHASE_REPORT, photos("b.png"), "inspector1");
        assertEquals(1, added.size());

        // 走完流程关闭
        rectifyService.dispatch(order.getRectifyNo(), dispatchReq());
        RectifyRepairRequest repair = new RectifyRepairRequest();
        repair.setRepairResult("已修复");
        repair.setOperatorName("vendor1");
        rectifyService.repair(order.getRectifyNo(), repair, null);
        RectifyReinspectRequest closer = new RectifyReinspectRequest();
        closer.setOperatorName("inspector1");
        rectifyService.reinspectPass(order.getRectifyNo(), closer);

        // 关闭后可删，但留 PHOTO_DELETE 事件
        rectifyService.deletePhoto(photoId, "inspector1");
        RectifyDetailView detail = rectifyService.detail(order.getRectifyNo());
        assertTrue(detail.getPhotos().stream().noneMatch(p -> p.getId().equals(photoId)));
        assertTrue(detail.getEvents().stream()
                .anyMatch(e -> RectifyEnums.EVENT_PHOTO_DELETE.equals(e.getEventType())));
    }

    // ---- 点位历史异常 ----

    @Test
    void siteHistory_listsAllRectifyOrdersIncludingClosed() {
        Site site = registerSite();
        RectifyOrder first = rectifyService.report(reportReq(site, RectifyEnums.TYPE_PANEL_LIFT), null);
        rectifyService.dispatch(first.getRectifyNo(), dispatchReq());
        RectifyRepairRequest repair = new RectifyRepairRequest();
        repair.setRepairResult("修好");
        repair.setOperatorName("vendor1");
        rectifyService.repair(first.getRectifyNo(), repair, null);
        RectifyReinspectRequest closer = new RectifyReinspectRequest();
        closer.setOperatorName("inspector1");
        rectifyService.reinspectPass(first.getRectifyNo(), closer);

        RectifyOrder second = rectifyService.report(reportReq(site, RectifyEnums.TYPE_LIGHT_FAULT), null);

        List<RectifyOrder> history = rectifyService.listBySite(site.getSiteNo());
        assertEquals(2, history.size());
        // 时间倒序：最新一单在前
        assertEquals(second.getRectifyNo(), history.get(0).getRectifyNo());
        assertEquals(RectifyEnums.STATUS_CLOSED, history.get(1).getStatus());
        assertEquals(2, history.stream().map(RectifyOrder::getRectifyNo).collect(Collectors.toSet()).size());

        // 翻查过滤也对得上
        List<RectifyOrder> closedOnly = rectifyService.list(
                RectifyEnums.STATUS_CLOSED, site.getSiteNo(), null, null);
        assertEquals(1, closedOnly.size());
        assertEquals(first.getRectifyNo(), closedOnly.get(0).getRectifyNo());
    }
}
