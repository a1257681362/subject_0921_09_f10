package com.evops.playlist;

import com.evops.asset.entity.Asset;
import com.evops.asset.mapper.AssetMapper;
import com.evops.asset.service.AssetService;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.SiteService;
import com.evops.ledger.support.WeekRanges;
import com.evops.playlist.dto.ChangeApprovalRequest;
import com.evops.playlist.dto.PlaylistChangeRequest;
import com.evops.playlist.dto.PlaylistCreateRequest;
import com.evops.playlist.dto.PlaylistDetailView;
import com.evops.playlist.dto.PlaylistSaveRequest;
import com.evops.playlist.dto.SegmentCopyRequest;
import com.evops.playlist.dto.SegmentShiftRequest;
import com.evops.playlist.dto.SlotItemSpec;
import com.evops.playlist.dto.SlotSpec;
import com.evops.playlist.dto.WeekLockRequest;
import com.evops.playlist.dto.WeekScheduleView;
import com.evops.playlist.entity.Playlist;
import com.evops.playlist.entity.PlaylistAlert;
import com.evops.playlist.entity.PlaylistVersion;
import com.evops.playlist.service.PlaylistOpsService;
import com.evops.playlist.service.PlaylistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F7 屏体播放列表排片端到端（真实 H2 + 真实事务，无 Shiro，经手人显式传；自动扫描间隔测试配置拉到 1h）。
 * 覆盖：一屏一单、跨零点按自然日切、同屏同时段冲突、出周/跨周边界、素材库校验、版本链、
 * 整段复制/顺延、周锁版、锁后改单审批（职责分离）、到点自动切换与空档/素材缺失告警（幂等）、周五自动锁版。
 *
 * 注：切换/自动锁版扫描遍历全部播放列表，各用例使用互不重叠的编排周（间隔 2 周）避免相互干扰。
 */
@SpringBootTest
class PlaylistFlowTest {

    @Autowired private PlaylistService playlistService;
    @Autowired private PlaylistOpsService opsService;
    @Autowired private SiteService siteService;
    @Autowired private AssetService assetService;
    @Autowired private AssetMapper assetMapper;

    /** 固定基准周（2026-11-02 周一）；每个用例再错开 2 周，跨零点用例天然占相邻两周也不撞。 */
    private static final LocalDate BASE_MONDAY = WeekRanges.mondayOf(LocalDate.of(2026, 11, 4));

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    /** 用例专属周（与其它用例错开 2 周）。 */
    private LocalDate week(int testIndex) {
        return BASE_MONDAY.plusWeeks((long) testIndex * 2L);
    }

    private Site registerSite() {
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("PL-SITE"));
        sr.setSizeSpec("P3 室内屏");
        sr.setOrientation("朝南");
        sr.setBusinessDistrict("排片测试商圈");
        sr.setOperatorName("tester");
        return siteService.register(sr);
    }

    private Asset uploadAsset(String name) {
        try {
            int v = (seq * 37) % 255;
            BufferedImage img = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    img.setRGB(x, y, new Color((x * 20 + v) % 255, (y * 15 + v) % 255, v).getRGB());
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            MockMultipartFile file = new MockMultipartFile(
                    "file", uniqueNo(name) + ".png", "image/png", bos.toByteArray());
            return assetService.upload(file, "tester", null).getAsset();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Playlist createPlaylist(Site site, LocalDate weekAnchor) {
        PlaylistCreateRequest req = new PlaylistCreateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setWeekStart(weekAnchor);
        req.setOperatorName("planner1");
        return playlistService.create(req);
    }

    private SlotSpec slot(LocalDate date, String start, String end, Asset... assets) {
        return slotCross(date, start, end, null, assets);
    }

    private SlotSpec slotCross(LocalDate date, String start, String end, Boolean cross, Asset... assets) {
        SlotSpec spec = new SlotSpec();
        spec.setDate(date);
        spec.setStartTime(start);
        spec.setEndTime(end);
        spec.setCrossMidnight(cross);
        List<SlotItemSpec> items = new ArrayList<>();
        for (Asset a : assets) {
            SlotItemSpec item = new SlotItemSpec();
            item.setAssetNo(a.getAssetNo());
            item.setDurationSec(15);
            items.add(item);
        }
        spec.setItems(items);
        return spec;
    }

    private PlaylistVersion save(String plNo, LocalDate weekAnchor, SlotSpec... slots) {
        PlaylistSaveRequest req = new PlaylistSaveRequest();
        req.setWeekStart(weekAnchor);
        req.setSlots(new ArrayList<>(Arrays.asList(slots)));
        req.setOperatorName("planner1");
        return playlistService.saveDraft(plNo, req);
    }

    private PlaylistVersion lock(String plNo, LocalDate weekAnchor) {
        WeekLockRequest req = new WeekLockRequest();
        req.setWeekStart(weekAnchor);
        req.setOperatorName("planner1");
        return playlistService.lockWeek(plNo, req, PlaylistEnums.LOCK_MANUAL, "planner1");
    }

    private List<PlaylistAlert> openAlerts(String plNo) {
        return playlistService.listAlerts(PlaylistEnums.ALERT_OPEN, plNo);
    }

    // ---- 建档 ----

    @Test
    void create_onePlaylistPerSite_startsWithV1() {
        LocalDate m = week(0);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        assertTrue(pl.getPlaylistNo().startsWith("PL"));
        assertEquals(PlaylistEnums.PLAYLIST_EDITING, pl.getStatus());
        assertEquals(1, pl.getCurrentVersionNo());

        // 一块屏只许一条
        assertThrows(BusinessException.class, () -> createPlaylist(site, m));
        // 未挂牌的屏不能开
        PlaylistCreateRequest bad = new PlaylistCreateRequest();
        bad.setSiteNo("NO-SUCH-SCREEN");
        bad.setOperatorName("planner1");
        assertThrows(BusinessException.class, () -> playlistService.create(bad));
    }

    // ---- 跨零点切自然日 + 冲突 ----

    @Test
    void crossMidnight_isSplitByNaturalDay_andOverlapRejected() {
        LocalDate m = week(1);
        LocalDate sunday = m.plusDays(6);
        LocalDate nextMonday = m.plusWeeks(1);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        Asset a1 = uploadAsset("夜场");

        // 周日 22:00~次日 02:00 跨零点：切成 周日22:00~24:00 + 下周一00:00~02:00 两行
        PlaylistVersion v = save(pl.getPlaylistNo(), m, slotCross(sunday, "22:00", "02:00", null, a1));
        WeekScheduleView view = playlistService.weekView(pl.getPlaylistNo(), m);
        assertEquals(2, view.getSlots().size());
        assertEquals(2, v.getSlotCount());
        assertTrue(view.getSlots().stream().anyMatch(s -> s.getSlotDate().equals(sunday) && s.getEndReachMidnight()));
        assertTrue(view.getSlots().stream().anyMatch(s -> s.getSlotDate().equals(nextMonday) && s.getStartFromMidnight()));
        // 播放单在两行各一份（素材从库挑，存编号快照）
        assertEquals(2, view.getSlots().stream().mapToInt(s -> s.getItems().size()).sum());
        assertEquals(a1.getAssetNo(), view.getSlots().get(0).getItems().get(0).getAssetNo());

        // 同日重叠：周日 23:00~23:30 撞进 22:00~24:00，保存直接报错
        BusinessException ex = assertThrows(BusinessException.class,
                () -> save(pl.getPlaylistNo(), m,
                        slotCross(sunday, "22:00", "02:00", null, a1),
                        slot(sunday, "23:00", "23:30", a1)));
        assertTrue(ex.getMessage().contains("时段撞车"));

        // 下周一凌晨 01:00~01:30 撞进溢出段（相邻周同一天），也报错
        BusinessException crossWeek = assertThrows(BusinessException.class,
                () -> save(pl.getPlaylistNo(), nextMonday, slot(nextMonday, "01:00", "01:30", a1)));
        assertTrue(crossWeek.getMessage().contains("上一周"));

        // 首尾相接不算撞：下周一 02:00 起可以排
        PlaylistVersion next = save(pl.getPlaylistNo(), nextMonday, slot(nextMonday, "02:00", "03:00", a1));
        assertNotNull(next);
    }

    @Test
    void sameDayOverlap_andEndExactly2400_areHandled() {
        LocalDate m = week(2);
        LocalDate sunday = m.plusDays(6);
        LocalDate nextMonday = m.plusWeeks(1);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        Asset a1 = uploadAsset("早场");

        // 同屏同日两条重叠时段：拒收
        assertThrows(BusinessException.class, () -> save(pl.getPlaylistNo(), m,
                slot(m, "09:00", "10:00", a1),
                slot(m, "09:30", "10:30", a1)));

        // 一次整表提交：周一首尾相接两段 + 周日 22:00~00:00（正好到当日 24:00，无空的次日段）
        PlaylistVersion v = save(pl.getPlaylistNo(), m,
                slot(m, "09:00", "10:00", a1),
                slot(m, "10:00", "11:00", a1),
                slotCross(sunday, "22:00", "00:00", null, a1));
        assertEquals(3, v.getSlotCount());

        WeekScheduleView view = playlistService.weekView(pl.getPlaylistNo(), m);
        assertEquals(3, view.getSlots().size());
        assertTrue(view.getSlots().stream()
                .anyMatch(s -> s.getSlotDate().equals(sunday) && Boolean.TRUE.equals(s.getEndReachMidnight())));
        // 正好到 24:00 不产生下周一的段
        assertFalse(view.getSlots().stream().anyMatch(s -> s.getSlotDate().equals(nextMonday)));
    }

    @Test
    void unknownAsset_orSlotOutOfWeek_rejected() {
        LocalDate m = week(3);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        Asset a1 = uploadAsset("主视觉");

        // 素材不在库里
        SlotItemSpec ghost = new SlotItemSpec();
        ghost.setAssetNo("AS_NOT_EXIST");
        SlotSpec bad = new SlotSpec();
        bad.setDate(m);
        bad.setStartTime("09:00");
        bad.setEndTime("10:00");
        bad.setItems(new ArrayList<>(Collections.singletonList(ghost)));
        PlaylistSaveRequest req = new PlaylistSaveRequest();
        req.setWeekStart(m);
        req.setSlots(new ArrayList<>(Collections.singletonList(bad)));
        req.setOperatorName("planner1");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> playlistService.saveDraft(pl.getPlaylistNo(), req));
        assertTrue(ex.getMessage().contains("不在素材库"));

        // 时段排到上周（非周日溢出的合法形态）：出周拒收
        assertThrows(BusinessException.class, () -> save(pl.getPlaylistNo(), m,
                slot(m.minusDays(1), "09:00", "10:00", a1)));
    }

    // ---- 版本链 + 复制 + 顺延 ----

    @Test
    void everySaveCopyShift_createsVersion_chainKept() {
        LocalDate m = week(4);
        LocalDate nextMonday = m.plusWeeks(1);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        Asset a1 = uploadAsset("周一");
        Asset a2 = uploadAsset("周二");

        save(pl.getPlaylistNo(), m,
                slot(m, "08:00", "10:00", a1),
                slot(m.plusDays(1), "09:00", "11:00", a2));
        // 再保存一版：v3（替换式整表提交），旧草稿仍可翻
        save(pl.getPlaylistNo(), m, slot(m, "08:30", "10:30", a1));

        List<PlaylistVersion> versions = playlistService.listVersions(pl.getPlaylistNo());
        assertEquals(3, versions.size());
        assertEquals(PlaylistEnums.CHANGE_EDIT, versions.get(0).getChangeType());
        assertNotNull(versions.get(0).getSnapshot());

        // 整段复制到下周
        SegmentCopyRequest copy = new SegmentCopyRequest();
        copy.setFromWeekStart(m);
        copy.setToWeekStart(nextMonday);
        copy.setOperatorName("planner1");
        PlaylistVersion copied = playlistService.copySegment(pl.getPlaylistNo(), copy);
        assertEquals(PlaylistEnums.CHANGE_COPY, copied.getChangeType());
        WeekScheduleView nextView = playlistService.weekView(pl.getPlaylistNo(), nextMonday);
        assertEquals(1, nextView.getSlots().size());
        assertEquals(nextMonday, nextView.getSlots().get(0).getSlotDate());

        // 整段顺延 30 分钟：08:30 → 09:00
        SegmentShiftRequest shift = new SegmentShiftRequest();
        shift.setWeekStart(nextMonday);
        shift.setOffsetMinutes(30L);
        shift.setOperatorName("planner1");
        PlaylistVersion shifted = playlistService.shiftSegment(pl.getPlaylistNo(), shift);
        assertEquals(PlaylistEnums.CHANGE_SHIFT, shifted.getChangeType());
        WeekScheduleView shiftedView = playlistService.weekView(pl.getPlaylistNo(), nextMonday);
        assertEquals(LocalTime.of(9, 0), shiftedView.getSlots().get(0).getStartTime());

        // 顺延越界（下周一段整体平移 7 天会推出下周）：拒收
        SegmentShiftRequest tooFar = new SegmentShiftRequest();
        tooFar.setWeekStart(nextMonday);
        tooFar.setOffsetMinutes(7L * 24 * 60);
        tooFar.setOperatorName("planner1");
        assertThrows(BusinessException.class, () -> playlistService.shiftSegment(pl.getPlaylistNo(), tooFar));
    }

    // ---- 锁版 + 锁后审批 ----

    @Test
    void lock_freezesWeek_changeGoesThroughApproval_withSeparationOfDuty() {
        LocalDate m = week(5);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        Asset a1 = uploadAsset("锁版前");
        Asset a2 = uploadAsset("锁版后");
        save(pl.getPlaylistNo(), m, slot(m, "09:00", "10:00", a1));

        PlaylistVersion locked = lock(pl.getPlaylistNo(), m);
        assertEquals(PlaylistEnums.VERSION_LOCKED, locked.getStatus());
        assertEquals(PlaylistEnums.PLAYLIST_LOCKED, playlistService.requireByNo(pl.getPlaylistNo()).getStatus());

        // 重复锁版拒绝
        assertThrows(BusinessException.class, () -> lock(pl.getPlaylistNo(), m));
        // 锁了之后直接动：拒绝
        assertThrows(BusinessException.class,
                () -> save(pl.getPlaylistNo(), m, slot(m, "11:00", "12:00", a2)));

        // 改单申请：原因必填，进 PENDING；有待审批时不能再申请
        PlaylistChangeRequest noReason = new PlaylistChangeRequest();
        noReason.setWeekStart(m);
        noReason.setSlots(new ArrayList<>(Collections.singletonList(slot(m, "11:00", "12:00", a2))));
        noReason.setReason("  ");
        assertThrows(BusinessException.class,
                () -> playlistService.requestChange(pl.getPlaylistNo(), noReason, Viewer.sales("planner1")));

        PlaylistChangeRequest change = new PlaylistChangeRequest();
        change.setWeekStart(m);
        change.setSlots(new ArrayList<>(Collections.singletonList(slot(m, "11:00", "12:00", a2))));
        change.setReason("客户临时换画");
        PlaylistVersion pending = playlistService.requestChange(pl.getPlaylistNo(), change, Viewer.sales("planner1"));
        assertEquals(PlaylistEnums.VERSION_PENDING, pending.getStatus());
        assertThrows(BusinessException.class,
                () -> playlistService.requestChange(pl.getPlaylistNo(), change, Viewer.sales("planner1")));

        ChangeApprovalRequest approval = new ChangeApprovalRequest();

        // 销售不能审批
        assertThrows(BusinessException.class, () -> playlistService.approveChange(
                pl.getPlaylistNo(), pending.getId(), approval, Viewer.sales("planner1")));
        // 申请人不能审批自己（即便用了财务视角同名账号）
        assertThrows(BusinessException.class, () -> playlistService.approveChange(
                pl.getPlaylistNo(), pending.getId(), approval, Viewer.finance("planner1")));

        // 财务审批通过：新版锁版生效，旧版 SUPERSEDED，周锁版指针换版
        approval.setComment("同意换画");
        PlaylistVersion approved = playlistService.approveChange(
                pl.getPlaylistNo(), pending.getId(), approval, Viewer.finance("finance1"));
        assertEquals(PlaylistEnums.VERSION_LOCKED, approved.getStatus());
        WeekScheduleView view = playlistService.weekView(pl.getPlaylistNo(), m);
        assertEquals(approved.getId(), view.getVersion().getId());
        assertEquals(11, view.getSlots().get(0).getStartTime().getHour());
        PlaylistVersion old = playlistService.listVersions(pl.getPlaylistNo()).stream()
                .filter(v -> v.getId().equals(locked.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(PlaylistEnums.VERSION_SUPERSEDED, old.getStatus());

        // 事件链含 SAVE/LOCK/CHANGE_REQUEST/APPROVE
        PlaylistDetailView detail = playlistService.detail(pl.getPlaylistNo());
        List<String> types = detail.getEvents().stream().map(e -> e.getEventType()).collect(Collectors.toList());
        assertTrue(types.contains(PlaylistEnums.EVENT_LOCK));
        assertTrue(types.contains(PlaylistEnums.EVENT_CHANGE_REQUEST));
        assertTrue(types.contains(PlaylistEnums.EVENT_APPROVE));
    }

    @Test
    void rejectChange_keepsOldLockedVersion() {
        LocalDate m = week(6);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        Asset a1 = uploadAsset("原版");
        Asset a2 = uploadAsset("改版");
        save(pl.getPlaylistNo(), m, slot(m, "09:00", "10:00", a1));
        PlaylistVersion locked = lock(pl.getPlaylistNo(), m);

        PlaylistChangeRequest change = new PlaylistChangeRequest();
        change.setWeekStart(m);
        change.setSlots(new ArrayList<>(Collections.singletonList(slot(m, "07:00", "08:00", a2))));
        change.setReason("想提前播");
        PlaylistVersion pending = playlistService.requestChange(pl.getPlaylistNo(), change, Viewer.sales("planner2"));

        ChangeApprovalRequest rejection = new ChangeApprovalRequest();
        rejection.setComment("不符合投放规范");
        PlaylistVersion rejected = playlistService.rejectChange(
                pl.getPlaylistNo(), pending.getId(), rejection, Viewer.finance("finance1"));
        assertEquals(PlaylistEnums.VERSION_REJECTED, rejected.getStatus());

        // 原锁版继续生效
        WeekScheduleView view = playlistService.weekView(pl.getPlaylistNo(), m);
        assertEquals(locked.getId(), view.getVersion().getId());
        assertEquals(9, view.getSlots().get(0).getStartTime().getHour());
    }

    // ---- 到点自动切换 + 告警 ----

    @Test
    void switchDue_changesLivePointer_idempotent_andAlertsGap() {
        LocalDate m = week(7);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        Asset a1 = uploadAsset("白天");
        save(pl.getPlaylistNo(), m,
                slot(m, "09:00", "10:00", a1),
                slot(m, "14:00", "15:00", a1));
        PlaylistVersion locked = lock(pl.getPlaylistNo(), m);

        // 还没到点：不切、不告警
        assertEquals(0, opsService.switchDue(m.atTime(8, 59)));
        assertTrue(openAlerts(pl.getPlaylistNo()).isEmpty());

        // 到点：推进 live 指针，写 SWITCH 事件
        assertEquals(1, opsService.switchDue(m.atTime(9, 30)));
        Playlist onAir = playlistService.requireByNo(pl.getPlaylistNo());
        assertEquals(locked.getId(), onAir.getLiveVersionId());
        assertEquals(m, onAir.getLiveSwitchDate());
        assertNotNull(onAir.getLiveSwitchedTime());
        // 重复扫描：幂等，不再切
        assertEquals(0, opsService.switchDue(m.atTime(9, 45)));

        // 下一时段到点再切
        assertEquals(1, opsService.switchDue(m.atTime(14, 10)));

        // 段间空档（10:00~14:00）扫描：不切换，落 GAP 告警；重复扫不重发
        assertEquals(0, opsService.switchDue(m.atTime(10, 30)));
        List<PlaylistAlert> gaps = openAlerts(pl.getPlaylistNo()).stream()
                .filter(a -> PlaylistEnums.ALERT_GAP.equals(a.getAlertType())).collect(Collectors.toList());
        assertEquals(1, gaps.size());
        opsService.switchDue(m.atTime(10, 45));
        assertEquals(1, openAlerts(pl.getPlaylistNo()).stream()
                .filter(a -> PlaylistEnums.ALERT_GAP.equals(a.getAlertType())).count());

        // 告警可人工解除
        playlistService.resolveAlert(gaps.get(0).getId(), "finance1", "已确认空档为计划内停播");
        assertEquals(0, playlistService.listAlerts(PlaylistEnums.ALERT_OPEN, pl.getPlaylistNo()).size());
    }

    @Test
    void switchDue_missingAsset_alertsAndDoesNotSwitch() {
        LocalDate m = week(8);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        Asset a1 = uploadAsset("会丢的素材");
        save(pl.getPlaylistNo(), m, slot(m.plusDays(1), "09:00", "10:00", a1));
        PlaylistVersion locked = lock(pl.getPlaylistNo(), m);

        // 素材被物理删除（库里找不到）：切换失败，指针不动，落 MISSING_ASSET + SWITCH_FAILED，不静默
        assetMapper.deleteById(a1.getId());
        int switched = opsService.switchDue(m.plusDays(1).atTime(9, 15));
        assertEquals(0, switched);
        Playlist still = playlistService.requireByNo(pl.getPlaylistNo());
        assertNull(still.getLiveVersionId());
        List<PlaylistAlert> alerts = openAlerts(pl.getPlaylistNo());
        assertEquals(2, alerts.size());
        assertTrue(alerts.stream().anyMatch(a -> PlaylistEnums.ALERT_MISSING_ASSET.equals(a.getAlertType())));
        assertTrue(alerts.stream().anyMatch(a -> PlaylistEnums.ALERT_SWITCH_FAILED.equals(a.getAlertType())));
        // 重复扫描不重发
        opsService.switchDue(m.plusDays(1).atTime(9, 20));
        assertEquals(2, openAlerts(pl.getPlaylistNo()).size());
        assertNotNull(locked.getId());
    }

    @Test
    void switchDue_crossMidnight_playsPrevWeekOverflowThenSwitches() {
        LocalDate m = week(9);
        LocalDate sunday = m.plusDays(6);
        LocalDate nextMonday = m.plusWeeks(1);
        Site site = registerSite();
        Playlist pl = createPlaylist(site, m);
        Asset a1 = uploadAsset("深夜场");
        Asset a2 = uploadAsset("早场");
        // 本周日 22:00~次日 01:00 跨零点
        save(pl.getPlaylistNo(), m, slotCross(sunday, "22:00", "01:00", null, a1));
        PlaylistVersion week1 = lock(pl.getPlaylistNo(), m);
        // 下周一 02:00~03:00（与溢出段 01:00 结束首尾相接）
        save(pl.getPlaylistNo(), nextMonday, slot(nextMonday, "02:00", "03:00", a2));
        PlaylistVersion week2 = lock(pl.getPlaylistNo(), nextMonday);

        // 下周一 00:30：按自然日切到周一后，播的是上周版本溢出段
        assertEquals(1, opsService.switchDue(nextMonday.atTime(0, 30)));
        Playlist onAir = playlistService.requireByNo(pl.getPlaylistNo());
        assertEquals(week1.getId(), onAir.getLiveVersionId());
        assertEquals(nextMonday, onAir.getLiveSwitchDate());

        // 01:30 溢出段已结束、本周首段未开始：跨周边界空档告警
        assertEquals(0, opsService.switchDue(nextMonday.atTime(1, 30)));
        assertTrue(openAlerts(pl.getPlaylistNo()).stream()
                .anyMatch(a -> PlaylistEnums.ALERT_GAP.equals(a.getAlertType())
                        && a.getVersionId().equals(week1.getId())));

        // 02:30 切到下周版本
        assertEquals(1, opsService.switchDue(nextMonday.atTime(2, 30)));
        onAir = playlistService.requireByNo(pl.getPlaylistNo());
        assertEquals(week2.getId(), onAir.getLiveVersionId());
    }

    // ---- 周五下班前自动锁版 ----

    @Test
    void autoLockNextWeek_locksDraftsAndAlertsEmptyAndPending() {
        LocalDate m = week(10);
        LocalDate nextMonday = m.plusWeeks(1);
        // nextMonday 往前 3 天即该周周五
        LocalDateTime fridayAfterDeadline = nextMonday.minusDays(3).atTime(18, 30);
        LocalDateTime fridayBeforeDeadline = nextMonday.minusDays(3).atTime(17, 0);

        // 屏一：下周完全没排
        Site empty = registerSite();
        Playlist plEmpty = createPlaylist(empty, nextMonday);
        // 未到周五下班时刻：什么都不做
        assertEquals(0, opsService.autoLockNextWeek(fridayBeforeDeadline));
        // 到点：无排片 → WEEK_EMPTY 告警，不锁
        assertEquals(0, opsService.autoLockNextWeek(fridayAfterDeadline));
        assertTrue(openAlerts(plEmpty.getPlaylistNo()).stream()
                .anyMatch(a -> PlaylistEnums.ALERT_WEEK_EMPTY.equals(a.getAlertType())));
        assertFalse(playlistService.weekView(plEmpty.getPlaylistNo(), nextMonday).isLocked());

        // 补排后再扫：自动锁版（AUTO），主档进入 LOCKED
        Asset a1 = uploadAsset("补排");
        save(plEmpty.getPlaylistNo(), nextMonday, slot(nextMonday, "09:00", "10:00", a1));
        assertEquals(1, opsService.autoLockNextWeek(fridayAfterDeadline));
        assertTrue(playlistService.weekView(plEmpty.getPlaylistNo(), nextMonday).isLocked());
        assertEquals(PlaylistEnums.PLAYLIST_LOCKED,
                playlistService.requireByNo(plEmpty.getPlaylistNo()).getStatus());
        assertTrue(playlistService.detail(plEmpty.getPlaylistNo()).getEvents().stream()
                .anyMatch(e -> PlaylistEnums.EVENT_AUTO_LOCK.equals(e.getEventType())));
        // 重复扫：已锁版不重复锁、不重复告警
        assertEquals(0, opsService.autoLockNextWeek(fridayAfterDeadline.plusMinutes(10)));

        // 屏二：下周已锁版但改单待审批 → PENDING_CHANGE 提醒
        Site pendingSite = registerSite();
        Playlist plPending = createPlaylist(pendingSite, nextMonday);
        Asset b1 = uploadAsset("已锁");
        Asset b2 = uploadAsset("改单");
        save(plPending.getPlaylistNo(), nextMonday, slot(nextMonday, "12:00", "13:00", b1));
        lock(plPending.getPlaylistNo(), nextMonday);
        PlaylistChangeRequest change = new PlaylistChangeRequest();
        change.setWeekStart(nextMonday);
        change.setSlots(new ArrayList<>(Collections.singletonList(slot(nextMonday, "12:00", "13:30", b2))));
        change.setReason("客户加时");
        playlistService.requestChange(plPending.getPlaylistNo(), change, Viewer.sales("planner1"));
        assertEquals(0, opsService.autoLockNextWeek(fridayAfterDeadline));
        assertTrue(openAlerts(plPending.getPlaylistNo()).stream()
                .anyMatch(a -> PlaylistEnums.ALERT_PENDING_CHANGE.equals(a.getAlertType())));
    }
}
