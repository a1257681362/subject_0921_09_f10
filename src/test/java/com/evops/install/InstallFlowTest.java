package com.evops.install;

import com.evops.common.BusinessException;
import com.evops.install.dto.ArriveRequest;
import com.evops.install.dto.CertRegisterRequest;
import com.evops.install.dto.CertRevokeRequest;
import com.evops.install.dto.CompleteRequest;
import com.evops.install.dto.InstallDetailView;
import com.evops.install.dto.ManualMatchRequest;
import com.evops.install.dto.RescheduleRequest;
import com.evops.install.dto.TeamCreateRequest;
import com.evops.install.dto.TeamMonthStat;
import com.evops.install.entity.InstallCert;
import com.evops.install.entity.InstallNotice;
import com.evops.install.entity.InstallOrder;
import com.evops.install.entity.InstallTeam;
import com.evops.install.mapper.InstallOrderMapper;
import com.evops.install.service.InstallDispatchService;
import com.evops.install.service.InstallTeamService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.SiteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F8 安装派单与资质匹配端到端（真实 H2 + 真实事务，无 Shiro，经手人显式传）。
 * 类级 {@link Transactional}：每个用例结束回滚，安装队/派单互不串库，保证「第一个合格空闲队」
 * 这类全局自动匹配的断言确定可靠（照片落盘 target 不回滚，无妨）。
 * 覆盖：上刊确认自动派单、资质（高空证有效期）+档期匹配、一队同一时段只排一个点位、
 * 到场拍照必传、完工回执联动上刊单与准时/延误、改期通知到队、对冲联动取消、月末按队结算。
 */
@SpringBootTest
@Transactional
class InstallFlowTest {

    @Autowired private OrderService orderService;
    @Autowired private SiteService siteService;
    @Autowired private InstallTeamService teamService;
    @Autowired private InstallDispatchService dispatchService;
    @Autowired private InstallOrderMapper installOrderMapper;

    /**
     * 清掉其他测试类遗留的待匹配派单（其建的安装队随各自事务回滚已不存在，这些单永远派不出去）。
     * 仅删 PENDING、保留本用例 keepIds 的单；本用例事务回滚后不影响其它测试，保证补扫计数确定。
     */
    private void clearForeignPending(long... keepIds) {
        LambdaQueryWrapper<InstallOrder> w = new LambdaQueryWrapper<InstallOrder>()
                .eq(InstallOrder::getStatus, InstallEnums.STATUS_PENDING);
        for (long id : keepIds) {
            w.ne(InstallOrder::getId, id);
        }
        installOrderMapper.delete(w);
    }

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    private Site registerSite(String workType) {
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("IS"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝北");
        sr.setBusinessDistrict("安装测试商圈");
        sr.setWorkType(workType);
        sr.setOperatorName("tester");
        return siteService.register(sr);
    }

    private PostingOrder post(Site site, String creative, LocalDate occurDate) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setCreativeName(creative);
        req.setOccurDate(occurDate);
        req.setOperatorName("tester");
        return orderService.createPosting(req);
    }

    private InstallTeam createTeam(String name) {
        TeamCreateRequest t = new TeamCreateRequest();
        t.setTeamName(uniqueNo(name));
        t.setLeaderName("李队长");
        t.setContact("13800000000");
        t.setOperatorName("dispatcher");
        return teamService.create(t);
    }

    private InstallCert giveHeightCert(InstallTeam team, LocalDate expiry) {
        CertRegisterRequest c = new CertRegisterRequest();
        c.setTeamNo(team.getTeamNo());
        c.setCertType(InstallEnums.CERT_WORK_AT_HEIGHT);
        c.setCertNo(uniqueNo("CERT"));
        c.setCertName("高处作业操作证");
        c.setIssueDate(LocalDate.of(2025, 1, 1));
        c.setExpiryDate(expiry);
        c.setOperatorName("dispatcher");
        return teamService.registerCert(c);
    }

    private MockMultipartFile[] photos(String name) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return new MockMultipartFile[] {
                new MockMultipartFile("photos", name, "image/png", bos.toByteArray())
        };
    }

    private InstallOrder dispatchOf(PostingOrder posting) {
        return dispatchService.getByOrderNo(posting.getOrderNo());
    }

    private PostingOrder reload(PostingOrder posting) {
        return orderService.requireByNo(posting.getOrderNo());
    }

    private ManualMatchRequest matchReq(String teamNo, LocalDateTime start, LocalDateTime end) {
        ManualMatchRequest mm = new ManualMatchRequest();
        mm.setTeamNo(teamNo);
        mm.setPlanStart(start);
        mm.setPlanEnd(end);
        mm.setOperatorName("dispatcher");
        return mm;
    }

    /** 在派单当前档期内到场（带照片）并完工，按传入时刻判准时。 */
    private InstallOrder goFinish(InstallOrder d, LocalDateTime arriveAt, LocalDateTime finishAt,
                                  String result, boolean expectOnTime) throws Exception {
        ArriveRequest arrive = new ArriveRequest();
        arrive.setOperatorName("installer1");
        arrive.setArriveTime(arriveAt);
        d = dispatchService.arrive(d.getDispatchNo(), arrive, photos("site.png"));

        CompleteRequest done = new CompleteRequest();
        done.setFinishResult(result);
        done.setOperatorName("installer1");
        done.setFinishTime(finishAt);
        d = dispatchService.complete(d.getDispatchNo(), done, null);
        assertEquals(expectOnTime ? 1 : 0, d.getOnTime());
        return d;
    }

    // ---- 自动派单 ----

    @Test
    void 上刊确认自动生成派单_无队待匹配_有合格队补扫派成并回写上刊单() {
        Site normal = registerSite(null);
        PostingOrder p0 = post(normal, "画面A", LocalDate.of(2026, 10, 12));
        InstallOrder d0 = dispatchOf(p0);
        assertTrue(d0.getDispatchNo().startsWith("ID"));
        assertEquals(InstallEnums.STATUS_PENDING, d0.getStatus());
        assertEquals("NORMAL", d0.getWorkType());
        assertEquals(InstallEnums.STATUS_PENDING, reload(p0).getInstallStatus());

        InstallTeam team = createTeam("先锋安装队");
        clearForeignPending(d0.getId());
        assertEquals(1, dispatchService.autoMatchPending());
        d0 = dispatchService.requireByNo(d0.getDispatchNo());
        assertEquals(InstallEnums.STATUS_ASSIGNED, d0.getStatus());
        assertEquals(team.getId(), d0.getTeamId());
        assertEquals(1, d0.getAutoMatched());
        assertNotNull(d0.getPlanStart());
        assertNotNull(d0.getArrivalDeadline());
        assertEquals(InstallEnums.STATUS_ASSIGNED, reload(p0).getInstallStatus());
        assertEquals(d0.getId(), reload(p0).getInstallDispatchId());

        InstallDetailView detail = dispatchService.detail(d0.getDispatchNo());
        List<String> types = detail.getEvents().stream()
                .map(e -> e.getEventType()).collect(Collectors.toList());
        assertTrue(types.contains(InstallEnums.EVENT_AUTO_CREATE));
        assertTrue(types.contains(InstallEnums.EVENT_MATCH));
        assertNotNull(detail.getEvents().get(0).getSnapshot());
    }

    @Test
    void 上刊时已有合格队_立即自动派成() {
        InstallTeam team = createTeam("即时安装队");
        Site normal = registerSite(null);
        PostingOrder posting = post(normal, "画面即派", LocalDate.of(2026, 10, 13));
        InstallOrder d = dispatchOf(posting);
        assertEquals(InstallEnums.STATUS_ASSIGNED, d.getStatus());
        assertEquals(team.getId(), d.getTeamId());
    }

    // ---- 高空资质硬门槛 ----

    @Test
    void 高空证过期接不了高空点位_补有效证后才派成() {
        InstallTeam noCertTeam = createTeam("无高空证队");
        Site high = registerSite(InstallEnums.WORK_HIGH);
        PostingOrder posting = post(high, "高空画面", LocalDate.of(2026, 10, 12));
        InstallOrder d = dispatchOf(posting);
        assertEquals(InstallEnums.STATUS_PENDING, d.getStatus());
        assertEquals("HIGH_ALTITUDE", d.getWorkType());
        clearForeignPending(d.getId());
        assertEquals(0, dispatchService.autoMatchPending());

        ManualMatchRequest mm = matchReq(noCertTeam.getTeamNo(), null, null);
        BusinessException noCert = assertThrows(BusinessException.class,
                () -> dispatchService.manualMatch(d.getDispatchNo(), mm));
        assertTrue(noCert.getMessage().contains("高空作业证"));

        // 过期证（到期日早于计划作业日）：仍派不出去
        giveHeightCert(noCertTeam, d.getPlanStart().toLocalDate().minusDays(1));
        assertEquals(0, dispatchService.autoMatchPending());
        BusinessException expired = assertThrows(BusinessException.class,
                () -> dispatchService.manualMatch(d.getDispatchNo(), mm));
        assertTrue(expired.getMessage().contains("过期"));

        // 覆盖计划作业日的有效证：人工补匹配派成
        giveHeightCert(noCertTeam, d.getPlanStart().toLocalDate());
        InstallOrder matched = dispatchService.manualMatch(d.getDispatchNo(), mm);
        assertEquals(InstallEnums.STATUS_ASSIGNED, matched.getStatus());
        assertEquals(0, matched.getAutoMatched());
    }

    @Test
    void 高空证到期当日仍有效_次日才过期() {
        InstallTeam team = createTeam("临界证队");
        giveHeightCert(team, LocalDate.of(2026, 10, 12)); // 计划日正是到期日
        Site high = registerSite(InstallEnums.WORK_HIGH);
        PostingOrder posting = post(high, "临界高空", LocalDate.of(2026, 10, 12)); // 周一
        InstallOrder d = dispatchOf(posting);
        assertEquals(LocalDate.of(2026, 10, 12), d.getPlanStart().toLocalDate());
        assertEquals(InstallEnums.STATUS_ASSIGNED, d.getStatus());
    }

    @Test
    void 吊销高空证或停用队_新高空单调不出去() {
        InstallTeam team = createTeam("会被吊销队");
        InstallCert cert = giveHeightCert(team, LocalDate.of(2030, 12, 31));
        CertRevokeRequest revoke = new CertRevokeRequest();
        revoke.setReason("违规暂扣");
        revoke.setOperatorName("dispatcher");
        teamService.revokeCert(cert.getId(), revoke);

        Site high = registerSite(InstallEnums.WORK_HIGH);
        PostingOrder p1 = post(high, "吊销后", LocalDate.of(2026, 10, 19));
        assertEquals(InstallEnums.STATUS_PENDING, dispatchOf(p1).getStatus());

        giveHeightCert(team, LocalDate.of(2030, 12, 31));
        teamService.disable(team.getTeamNo(), "dispatcher");
        assertEquals(0, dispatchService.autoMatchPending());
        teamService.enable(team.getTeamNo(), "dispatcher");
        clearForeignPending(dispatchOf(p1).getId());
        assertEquals(1, dispatchService.autoMatchPending());
        assertEquals(InstallEnums.STATUS_ASSIGNED, dispatchOf(p1).getStatus());
    }

    // ---- 一队同一时段只排一个点位 ----

    @Test
    void 一个队同一时段只排一个点位_首尾相接可以_重叠拒绝() {
        InstallTeam team = createTeam("档期队");
        giveHeightCert(team, LocalDate.of(2030, 12, 31));

        post(registerSite(InstallEnums.WORK_HIGH), "A点位", LocalDate.of(2026, 10, 12));
        PostingOrder pb = post(registerSite(null), "B点位", LocalDate.of(2026, 10, 13));
        // 同周两张单默认档期都是周一 09:00~13:00：第一张占走队，第二张队忙留待匹配
        InstallOrder db = dispatchOf(pb);
        assertEquals(InstallEnums.STATUS_PENDING, db.getStatus());

        // 人工硬排同一队同一时段：拒
        ManualMatchRequest overlap = matchReq(team.getTeamNo(),
                LocalDateTime.of(2026, 10, 12, 9, 0), LocalDateTime.of(2026, 10, 12, 13, 0));
        BusinessException busy = assertThrows(BusinessException.class,
                () -> dispatchService.manualMatch(db.getDispatchNo(), overlap));
        assertTrue(busy.getMessage().contains("同一时段"));

        // 首尾相接 13:00 起：可排
        ManualMatchRequest adjacent = matchReq(team.getTeamNo(),
                LocalDateTime.of(2026, 10, 12, 13, 0), LocalDateTime.of(2026, 10, 12, 17, 0));
        InstallOrder assigned = dispatchService.manualMatch(db.getDispatchNo(), adjacent);
        assertEquals(InstallEnums.STATUS_ASSIGNED, assigned.getStatus());
        assertEquals(team.getId(), assigned.getTeamId());
    }

    // ---- 到场打卡 / 完工回执 ----

    @Test
    void 到场必须拍照_完工回执联动上刊单并判准时() throws Exception {
        createTeam("完工队");
        Site site = registerSite(null);
        PostingOrder posting = post(site, "准时单", LocalDate.of(2026, 10, 26)); // 周一
        InstallOrder d = dispatchOf(posting);

        ArriveRequest arrive = new ArriveRequest();
        arrive.setOperatorName("installer1");
        arrive.setArriveTime(d.getPlanStart());
        assertThrows(BusinessException.class, () -> dispatchService.arrive(d.getDispatchNo(), arrive, null));

        InstallOrder arrived = dispatchService.arrive(d.getDispatchNo(), arrive, photos("site.png"));
        assertEquals(InstallEnums.STATUS_ARRIVED, arrived.getStatus());
        assertEquals(1, dispatchService.detail(d.getDispatchNo()).getPhotos().size());

        CompleteRequest noResult = new CompleteRequest();
        noResult.setOperatorName("installer1");
        noResult.setFinishTime(d.getPlanEnd());
        assertThrows(BusinessException.class,
                () -> dispatchService.complete(d.getDispatchNo(), noResult, null));

        // 已到场，直接回传准时完工回执（计划结束前 20 分钟）
        CompleteRequest done = new CompleteRequest();
        done.setFinishResult("画面安装到位，通电正常");
        done.setOperatorName("installer1");
        done.setFinishTime(d.getPlanEnd().minusMinutes(20));
        InstallOrder completed = dispatchService.complete(d.getDispatchNo(), done, photos("done.png"));
        assertEquals(InstallEnums.STATUS_COMPLETED, completed.getStatus());
        assertEquals(1, completed.getOnTime());

        InstallDetailView detail = dispatchService.detail(d.getDispatchNo());
        assertNotNull(detail.getReceipt());
        assertEquals(1, detail.getReceipt().getOnTime());
        assertEquals(2, detail.getPhotos().size());

        PostingOrder reloaded = reload(posting);
        assertEquals(InstallEnums.STATUS_COMPLETED, reloaded.getInstallStatus());
        assertNotNull(reloaded.getInstallFinishTime());

        // 完工即冻结
        assertThrows(BusinessException.class,
                () -> dispatchService.arrive(d.getDispatchNo(), arrive, photos("x.png")));
        CompleteRequest again = new CompleteRequest();
        again.setFinishResult("再完工");
        again.setOperatorName("installer1");
        assertThrows(BusinessException.class, () -> dispatchService.complete(d.getDispatchNo(), again, null));
    }

    @Test
    void 晚于计划结束完工判延误() throws Exception {
        createTeam("延误队");
        Site site = registerSite(null);
        PostingOrder posting = post(site, "延误单", LocalDate.of(2026, 10, 27));
        InstallOrder d = dispatchOf(posting);
        InstallOrder completed = goFinish(dispatchService.requireByNo(d.getDispatchNo()),
                d.getPlanStart().plusHours(2), d.getPlanEnd().plusMinutes(30), "来晚了装完", false);
        assertEquals(0, completed.getOnTime());
        assertEquals(0, dispatchService.detail(d.getDispatchNo()).getReceipt().getOnTime());
    }

    // ---- 改期通知到队 ----

    @Test
    void 改期通知到队_留新旧档期次数_重叠档期拒绝() {
        InstallTeam team = createTeam("改期队");
        giveHeightCert(team, LocalDate.of(2030, 12, 31));
        Site site = registerSite(InstallEnums.WORK_HIGH);
        PostingOrder posting = post(site, "改期单", LocalDate.of(2026, 11, 2)); // 周一
        InstallOrder d = dispatchOf(posting);
        LocalDateTime oldStart = d.getPlanStart();

        RescheduleRequest r = new RescheduleRequest();
        r.setNewStart(LocalDateTime.of(2026, 11, 4, 9, 0));
        r.setNewEnd(LocalDateTime.of(2026, 11, 4, 13, 0));
        r.setReason("客户场地周三才让进场");
        r.setOperatorName("dispatcher");
        InstallOrder rescheduled = dispatchService.reschedule(d.getDispatchNo(), r);
        assertEquals(1, rescheduled.getRescheduleCount());
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 0), rescheduled.getPlanStart());

        InstallDetailView detail = dispatchService.detail(d.getDispatchNo());
        assertEquals(1, detail.getNotices().size());
        InstallNotice notice = detail.getNotices().get(0);
        assertEquals(team.getId(), notice.getTeamId());
        assertEquals(oldStart, notice.getOldStart());
        assertEquals(r.getNewEnd(), notice.getNewEnd());
        assertTrue(notice.getReason().contains("客户场地"));

        // 本单腾出的周一档期被另一张高空单占走：再把本单改回周一重叠时段 -> 拒
        PostingOrder p2 = post(registerSite(InstallEnums.WORK_HIGH), "撞档单", LocalDate.of(2026, 11, 2));
        assertEquals(InstallEnums.STATUS_ASSIGNED, dispatchOf(p2).getStatus());
        RescheduleRequest overlap = new RescheduleRequest();
        overlap.setNewStart(LocalDateTime.of(2026, 11, 2, 10, 0));
        overlap.setNewEnd(LocalDateTime.of(2026, 11, 2, 14, 0));
        overlap.setOperatorName("dispatcher");
        BusinessException busy = assertThrows(BusinessException.class,
                () -> dispatchService.reschedule(d.getDispatchNo(), overlap));
        assertTrue(busy.getMessage().contains("同一时段"));
        assertEquals(1, dispatchService.requireByNo(d.getDispatchNo()).getRescheduleCount());

        // 改成与自身当前档期首尾相接的空档：成功，第二次改期
        RescheduleRequest adjacent = new RescheduleRequest();
        adjacent.setNewStart(LocalDateTime.of(2026, 11, 4, 13, 0));
        adjacent.setNewEnd(LocalDateTime.of(2026, 11, 4, 15, 0));
        adjacent.setOperatorName("dispatcher");
        assertEquals(2, dispatchService.reschedule(d.getDispatchNo(), adjacent).getRescheduleCount());
    }

    // ---- 对冲联动取消 ----

    @Test
    void 上刊对冲联动取消未完工派单释放档期() {
        InstallTeam team = createTeam("取消队");
        giveHeightCert(team, LocalDate.of(2030, 12, 31));
        Site high = registerSite(InstallEnums.WORK_HIGH);
        PostingOrder posting = post(high, "将对冲", LocalDate.of(2026, 11, 9));
        InstallOrder d = dispatchOf(posting);
        assertEquals(InstallEnums.STATUS_ASSIGNED, d.getStatus());

        ActionRequest offset = new ActionRequest();
        offset.setOccurDate(LocalDate.of(2026, 11, 9));
        offset.setOperatorName("tester");
        offset.setReason("画面版本挂错");
        orderService.offset(posting.getOrderNo(), offset);

        InstallOrder cancelled = dispatchService.requireByNo(d.getDispatchNo());
        assertEquals(InstallEnums.STATUS_CANCELLED, cancelled.getStatus());
        assertEquals(InstallEnums.STATUS_CANCELLED, reload(posting).getInstallStatus());
        assertTrue(cancelled.getCancelReason().contains("对冲"));

        // 档期释放：新一张同周高空单派给同一个队
        PostingOrder p2 = post(registerSite(InstallEnums.WORK_HIGH), "补位单", LocalDate.of(2026, 11, 10));
        InstallOrder d2 = dispatchOf(p2);
        assertEquals(InstallEnums.STATUS_ASSIGNED, d2.getStatus());
        assertEquals(team.getId(), d2.getTeamId());
    }

    @Test
    void 已完工派单在上刊对冲时保留不取消() throws Exception {
        createTeam("完工保留队");
        Site site = registerSite(null);
        PostingOrder posting = post(site, "完工后对冲", LocalDate.of(2026, 11, 16));
        InstallOrder d = dispatchOf(posting);
        goFinish(dispatchService.requireByNo(d.getDispatchNo()),
                d.getPlanStart(), d.getPlanEnd(), "装好", true);

        ActionRequest offset = new ActionRequest();
        offset.setOccurDate(LocalDate.of(2026, 11, 16));
        offset.setOperatorName("tester");
        offset.setReason("客户改画面");
        orderService.offset(posting.getOrderNo(), offset);

        assertEquals(InstallEnums.STATUS_COMPLETED,
                dispatchService.requireByNo(d.getDispatchNo()).getStatus());
        assertNotNull(dispatchService.detail(d.getDispatchNo()).getReceipt());
    }

    // ---- 月末按队完工量与准时率 ----

    @Test
    void 月末按队出完工量与准时率_结算认这个数() throws Exception {
        InstallTeam t1 = createTeam("结算一队");

        // 同周三张普通单：a 自动占周一档期派给 t1；b/open 因队忙留待匹配，再人工排到不同档期
        PostingOrder a = post(registerSite(null), "T1-A", LocalDate.of(2026, 10, 5)); // 周一
        PostingOrder b = post(registerSite(null), "T1-B", LocalDate.of(2026, 10, 6));
        PostingOrder open = post(registerSite(null), "T1-OPEN", LocalDate.of(2026, 10, 7));
        InstallOrder da = dispatchOf(a);
        InstallOrder db = dispatchOf(b);
        InstallOrder dopen = dispatchOf(open);
        assertEquals(InstallEnums.STATUS_ASSIGNED, da.getStatus());
        assertEquals(t1.getId(), da.getTeamId());
        assertEquals(InstallEnums.STATUS_PENDING, db.getStatus());
        assertEquals(InstallEnums.STATUS_PENDING, dopen.getStatus());

        db = dispatchService.manualMatch(db.getDispatchNo(), matchReq(t1.getTeamNo(),
                LocalDateTime.of(2026, 10, 11, 9, 0), LocalDateTime.of(2026, 10, 11, 13, 0)));
        dopen = dispatchService.manualMatch(dopen.getDispatchNo(), matchReq(t1.getTeamNo(),
                LocalDateTime.of(2026, 10, 12, 9, 0), LocalDateTime.of(2026, 10, 12, 13, 0)));

        // a 周一档期内准时完工；b 在 10/11 延误完工；open 在途不完工
        goFinish(da, LocalDateTime.of(2026, 10, 5, 9, 0),
                LocalDateTime.of(2026, 10, 5, 12, 0), "T1-A 完成", true);
        goFinish(db, LocalDateTime.of(2026, 10, 11, 9, 0),
                LocalDateTime.of(2026, 10, 11, 14, 30), "T1-B 延误", false);

        // 二队：一张 10 月准时完工。a 已完工仍占 t1 周一档期，c 只能派给 t2
        InstallTeam t2 = createTeam("结算二队");
        PostingOrder c = post(registerSite(null), "T2-C", LocalDate.of(2026, 10, 8));
        InstallOrder dc = dispatchOf(c);
        assertEquals(t2.getId(), dc.getTeamId());
        RescheduleRequest moveC = new RescheduleRequest();
        moveC.setNewStart(LocalDateTime.of(2026, 10, 13, 9, 0));
        moveC.setNewEnd(LocalDateTime.of(2026, 10, 13, 13, 0));
        moveC.setOperatorName("dispatcher");
        dc = dispatchService.reschedule(dc.getDispatchNo(), moveC);
        goFinish(dc, LocalDateTime.of(2026, 10, 13, 9, 0),
                LocalDateTime.of(2026, 10, 13, 11, 0), "T2-C 完成", true);

        List<TeamMonthStat> stats = dispatchService.monthReport("2026-10");
        TeamMonthStat s1 = stats.stream().filter(x -> x.getTeamNo().equals(t1.getTeamNo())).findFirst().get();
        TeamMonthStat s2 = stats.stream().filter(x -> x.getTeamNo().equals(t2.getTeamNo())).findFirst().get();
        assertEquals(2, s1.getCompletedCount());
        assertEquals(1, s1.getOnTimeCount());
        assertEquals(1, s1.getLateCount());
        assertEquals(0.5, s1.getOnTimeRate(), 1e-9);
        assertEquals(1, s1.getAssignedOpenCount());
        assertEquals(1, s2.getCompletedCount());
        assertEquals(1, s2.getOnTimeCount());
        assertEquals(1.0, s2.getOnTimeRate(), 1e-9);

        TeamMonthStat sep = dispatchService.monthReport("2026-09").stream()
                .filter(x -> x.getTeamNo().equals(t1.getTeamNo())).findFirst().get();
        assertEquals(0, sep.getCompletedCount());
        assertEquals(0d, sep.getOnTimeRate(), 1e-9);

        assertThrows(BusinessException.class, () -> dispatchService.monthReport("2026/10"));

        String csv = dispatchService.monthCsv("2026-10");
        assertTrue(csv.startsWith("﻿"));
        assertTrue(csv.contains("完工量") && csv.contains("准时率"));
        assertTrue(csv.contains(t1.getTeamNo()) && csv.contains("50.00%"));
    }
}
