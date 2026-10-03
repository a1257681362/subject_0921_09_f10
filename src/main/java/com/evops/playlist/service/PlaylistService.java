package com.evops.playlist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.asset.entity.Asset;
import com.evops.asset.mapper.AssetMapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.support.Operators;
import com.evops.ledger.support.WeekRanges;
import com.evops.playlist.PlaylistEnums;
import com.evops.playlist.dto.ChangeApprovalRequest;
import com.evops.playlist.dto.PlaylistChangeRequest;
import com.evops.playlist.dto.PlaylistCreateRequest;
import com.evops.playlist.dto.PlaylistDetailView;
import com.evops.playlist.dto.PlaylistSaveRequest;
import com.evops.playlist.dto.SegmentCopyRequest;
import com.evops.playlist.dto.SegmentShiftRequest;
import com.evops.playlist.dto.SlotItemSpec;
import com.evops.playlist.dto.SlotSpec;
import com.evops.playlist.dto.SlotView;
import com.evops.playlist.dto.WeekLockRequest;
import com.evops.playlist.dto.WeekScheduleView;
import com.evops.playlist.entity.Playlist;
import com.evops.playlist.entity.PlaylistAlert;
import com.evops.playlist.entity.PlaylistEvent;
import com.evops.playlist.entity.PlaylistItem;
import com.evops.playlist.entity.PlaylistSlot;
import com.evops.playlist.entity.PlaylistVersion;
import com.evops.playlist.entity.PlaylistWeekLock;
import com.evops.playlist.mapper.PlaylistAlertMapper;
import com.evops.playlist.mapper.PlaylistEventMapper;
import com.evops.playlist.mapper.PlaylistItemMapper;
import com.evops.playlist.mapper.PlaylistMapper;
import com.evops.playlist.mapper.PlaylistSlotMapper;
import com.evops.playlist.mapper.PlaylistVersionMapper;
import com.evops.playlist.mapper.PlaylistWeekLockMapper;
import com.evops.playlist.support.PlaylistSegments;
import com.evops.playlist.support.PlaylistSegments.Segment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 播放列表排片编排主线：
 * 一块屏一条播放列表，按 ISO 自然周组织版本；周内按自然日时段编排播放单，素材从素材库里挑。
 *
 * 口径：
 * - 跨零点时段先按自然日切成两段再比较，同屏同一自然日只许一条有效排片，半开区间重叠即报错（首尾相接不算撞）；
 * - 编排每次保存/复制/顺延/改单都另起版本，版本链只追加，历史可翻；
 * - 周锁版后该周不能直接改，改动走「锁版改单申请 → 财务/系统审批」，申请人不能审批自己；
 * - 锁版/审批、自动锁版、到点切换均写事件留底。
 */
@Service
public class PlaylistService {

    private final PlaylistMapper playlistMapper;
    private final PlaylistVersionMapper versionMapper;
    private final PlaylistSlotMapper slotMapper;
    private final PlaylistItemMapper itemMapper;
    private final PlaylistWeekLockMapper lockMapper;
    private final PlaylistEventMapper eventMapper;
    private final PlaylistAlertMapper alertMapper;
    private final SiteMapper siteMapper;
    private final AssetMapper assetMapper;
    private final ObjectMapper objectMapper;

    public PlaylistService(PlaylistMapper playlistMapper, PlaylistVersionMapper versionMapper,
                           PlaylistSlotMapper slotMapper, PlaylistItemMapper itemMapper,
                           PlaylistWeekLockMapper lockMapper, PlaylistEventMapper eventMapper,
                           PlaylistAlertMapper alertMapper, SiteMapper siteMapper,
                           AssetMapper assetMapper, ObjectMapper objectMapper) {
        this.playlistMapper = playlistMapper;
        this.versionMapper = versionMapper;
        this.slotMapper = slotMapper;
        this.itemMapper = itemMapper;
        this.lockMapper = lockMapper;
        this.eventMapper = eventMapper;
        this.alertMapper = alertMapper;
        this.siteMapper = siteMapper;
        this.assetMapper = assetMapper;
        this.objectMapper = objectMapper;
    }

    // ---- 建档 ----

    /** 给一块屏开播放列表（一屏一条），带首个编排周的 v1 空草稿。 */
    @Transactional(rollbackFor = Exception.class)
    public Playlist create(PlaylistCreateRequest request) {
        String siteNo = request.getSiteNo().trim();
        Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>().eq(Site::getSiteNo, siteNo));
        if (site == null) {
            throw new BusinessException("点位档案里没有这个屏（点位编号）: " + siteNo + "，请先挂牌建档");
        }
        Playlist existing = playlistMapper.selectOne(
                new LambdaQueryWrapper<Playlist>().eq(Playlist::getSiteId, site.getId()));
        if (existing != null) {
            throw new BusinessException("屏 " + siteNo + " 已有播放列表 " + existing.getPlaylistNo()
                    + "，一块屏只许一条排片单");
        }
        LocalDate monday = WeekRanges.mondayOf(request.getWeekStart() != null ? request.getWeekStart() : LocalDate.now());
        String operator = Operators.resolve(request.getOperatorName());

        Playlist playlist = new Playlist();
        playlist.setPlaylistNo(generateNo());
        playlist.setSiteId(site.getId());
        playlist.setSiteNo(site.getSiteNo());
        playlist.setStatus(PlaylistEnums.PLAYLIST_EDITING);
        playlist.setOperatorName(operator);
        playlist.setRemark(request.getRemark());
        try {
            playlistMapper.insert(playlist);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("屏 " + siteNo + " 已有播放列表，一块屏只许一条排片单");
        }

        PlaylistVersion v1 = newVersion(playlist, monday, PlaylistEnums.CHANGE_CREATE, operator, request.getRemark());
        v1.setSlotCount(0);
        v1.setItemCount(0);
        v1.setSnapshot(snapshot(buildWeekView(playlist, v1)));
        versionMapper.insert(v1);
        pointCurrent(playlist, v1);
        appendEvent(playlist, v1, PlaylistEnums.EVENT_CREATE, operator,
                "为屏 " + siteNo + " 建立播放列表，开排 " + monday + " 周", v1);
        return playlist;
    }

    // ---- 整表保存（编排留版本） ----

    /** 整表保存某周编排（替换式）：每次保存另起草稿版本；该周已锁版或有待审批改单时拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public PlaylistVersion saveDraft(String playlistNo, PlaylistSaveRequest request) {
        Playlist playlist = requireByNo(playlistNo);
        LocalDate monday = resolveWeek(playlist, request.getWeekStart());
        requireWeekNotLocked(playlist.getId(), monday, "保存编排");
        requireNoPendingChange(playlist.getId(), monday);
        String operator = Operators.resolve(request.getOperatorName());
        if (request.getSlots() == null || request.getSlots().isEmpty()) {
            throw new BusinessException("至少编排一个时段；整周清空请先与排期负责人确认后逐段删除");
        }
        List<Segment> segments = normalizeAndValidate(request.getSlots(), monday, playlist.getId());
        Map<String, Asset> assets = requireAssets(segments);

        PlaylistVersion version = newVersion(playlist, monday, PlaylistEnums.CHANGE_EDIT, operator, request.getRemark());
        versionMapper.insert(version);
        int itemCount = persistSegments(playlist, version, segments, assets);
        finishVersion(version, segments.size(), itemCount);
        pointCurrent(playlist, version);
        appendEvent(playlist, version, PlaylistEnums.EVENT_SAVE, operator,
                "保存 " + monday + " 周编排：" + segments.size() + " 个自然日时段、" + itemCount + " 条播放单", version);
        return version;
    }

    // ---- 整段复制 ----

    /** 把来源周整段复制到目标周（生成目标周新草稿）；目标周已锁版/有待审批时拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public PlaylistVersion copySegment(String playlistNo, SegmentCopyRequest request) {
        Playlist playlist = requireByNo(playlistNo);
        if (request.getFromWeekStart() == null) {
            throw new BusinessException("整段复制必须指定来源周（传周内任意一天）");
        }
        LocalDate fromMonday = WeekRanges.mondayOf(request.getFromWeekStart());
        LocalDate toMonday = request.getToWeekStart() != null
                ? WeekRanges.mondayOf(request.getToWeekStart()) : fromMonday.plusWeeks(1);
        if (toMonday.equals(fromMonday)) {
            throw new BusinessException("整段复制的目标周不能与来源周相同（" + fromMonday + "）");
        }
        requireWeekNotLocked(playlist.getId(), toMonday, "整段复制到目标周");
        requireNoPendingChange(playlist.getId(), toMonday);
        PlaylistVersion source = latestWeekVersion(playlist.getId(), fromMonday);
        if (source == null || countSlots(source.getId()) == 0) {
            throw new BusinessException("来源周 " + fromMonday + " 还没有任何编排，没有可复制的整段");
        }
        String operator = Operators.resolve(request.getOperatorName());

        PlaylistVersion target = newVersion(playlist, toMonday, PlaylistEnums.CHANGE_COPY, operator, request.getRemark());
        target.setSourceVersionId(source.getId());
        versionMapper.insert(target);
        int itemCount = copyPersisted(playlist, source, target, fromMonday, toMonday);
        finishVersion(target, source.getSlotCount(), itemCount);
        pointCurrent(playlist, target);
        appendEvent(playlist, target, PlaylistEnums.EVENT_COPY, operator,
                "整段复制：" + fromMonday + " 周 → " + toMonday + " 周（来源 v" + source.getVersionNo() + "）", target);
        return target;
    }

    // ---- 整段顺延 ----

    /** 把某周全部时段整体平移 offsetMinutes 分钟（正延后/负提前），跨零点跨日按切分重算。 */
    @Transactional(rollbackFor = Exception.class)
    public PlaylistVersion shiftSegment(String playlistNo, SegmentShiftRequest request) {
        Playlist playlist = requireByNo(playlistNo);
        LocalDate monday = resolveWeek(playlist, request.getWeekStart());
        requireWeekNotLocked(playlist.getId(), monday, "整段顺延");
        requireNoPendingChange(playlist.getId(), monday);
        if (request.getOffsetMinutes() == null || request.getOffsetMinutes() == 0) {
            throw new BusinessException("整段顺延必须给出非 0 的顺延分钟数（正数延后、负数提前）");
        }
        PlaylistVersion source = editableWeekVersion(playlist.getId(), monday);
        if (source == null || countSlots(source.getId()) == 0) {
            throw new BusinessException(monday + " 周还没有可顺延的草稿编排，请先保存编排");
        }
        String operator = Operators.resolve(request.getOperatorName());

        List<PlaylistSlot> srcSlots = listSlotsOrdered(source.getId());
        Map<Long, List<PlaylistItem>> itemsBySlot = loadItemsBySlot(srcSlots);
        List<Segment> shifted = new ArrayList<>();
        for (PlaylistSlot slot : srcSlots) {
            LocalDateTime from = slot.getSlotDate().atTime(slot.getStartTime())
                    .plusMinutes(request.getOffsetMinutes());
            LocalDateTime endBase = slot.getEndReachMidnight() == 1
                    ? slot.getSlotDate().plusDays(1).atStartOfDay()
                    : slot.getSlotDate().atTime(slot.getEndTime());
            LocalDateTime to = endBase.plusMinutes(request.getOffsetMinutes());
            List<SlotItemSpec> specs = toSpecs(itemsBySlot.get(slot.getId()));
            shifted.addAll(PlaylistSegments.splitAbsolute(from, to, slot.getCrossMidnight() == 1, specs));
        }
        // 顺延后重新按自然日校验：出周、段内互撞、与相邻周溢出段互撞
        List<Segment> normalized = normalizeAndValidate(toSlotSpecs(shifted, monday), monday, playlist.getId());
        Map<String, Asset> assets = requireAssets(normalized);

        PlaylistVersion version = newVersion(playlist, monday, PlaylistEnums.CHANGE_SHIFT, operator, request.getRemark());
        version.setSourceVersionId(source.getId());
        versionMapper.insert(version);
        int itemCount = persistSegments(playlist, version, normalized, assets);
        finishVersion(version, normalized.size(), itemCount);
        pointCurrent(playlist, version);
        appendEvent(playlist, version, PlaylistEnums.EVENT_SHIFT, operator,
                "整段顺延 " + request.getOffsetMinutes() + " 分钟（来源 v" + source.getVersionNo()
                        + "）：" + normalized.size() + " 个自然日时段、" + itemCount + " 条播放单", version);
        return version;
    }

    // ---- 周锁版 ----

    /** 锁版某周：草稿置 LOCKED 并写周锁版；必须有时段、素材齐全。周五自动锁版走 source=AUTO。 */
    @Transactional(rollbackFor = Exception.class)
    public PlaylistVersion lockWeek(String playlistNo, WeekLockRequest request, String lockSource, String explicitOperator) {
        Playlist playlist = requireByNo(playlistNo);
        LocalDate monday = resolveWeek(playlist, request != null ? request.getWeekStart() : null);
        return lockWeek(playlist, monday, lockSource, explicitOperator);
    }

    /** 内部锁版入口（自动锁版任务复用）。 */
    @Transactional(rollbackFor = Exception.class)
    public PlaylistVersion lockWeek(Playlist playlist, LocalDate monday, String lockSource, String explicitOperator) {
        PlaylistWeekLock existing = findLock(playlist.getId(), monday);
        if (existing != null) {
            throw new BusinessException("屏 " + playlist.getSiteNo() + " 的 " + monday + " 周已经锁版，不能重复锁；要改请走锁版改单审批");
        }
        requireNoPendingChange(playlist.getId(), monday);
        PlaylistVersion version = latestWeekVersion(playlist.getId(), monday);
        if (version == null || countSlots(version.getId()) == 0) {
            throw new BusinessException("屏 " + playlist.getSiteNo() + " 的 " + monday + " 周还没有任何编排，不能锁版");
        }
        if (!PlaylistEnums.VERSION_DRAFT.equals(version.getStatus())
                && !PlaylistEnums.VERSION_REJECTED.equals(version.getStatus())) {
            throw new BusinessException(monday + " 周最新版本是「"
                    + PlaylistEnums.versionStatusLabel(version.getStatus()) + "」，不能锁版");
        }
        // 锁版前再过一遍素材齐全性：库里找不到/已归档的素材不许锁进版本
        List<Segment> segments = loadSegments(version);
        requireAssets(segments);
        String operator = Operators.resolve(explicitOperator);
        LocalDateTime now = LocalDateTime.now();

        version.setStatus(PlaylistEnums.VERSION_LOCKED);
        version.setLockedBy(operator);
        version.setLockTime(now);
        versionMapper.updateById(version);

        PlaylistWeekLock lock = new PlaylistWeekLock();
        lock.setPlaylistId(playlist.getId());
        lock.setWeekStart(monday);
        lock.setWeekEnd(monday.plusDays(6));
        lock.setVersionId(version.getId());
        lock.setLockSource(lockSource != null ? lockSource : PlaylistEnums.LOCK_MANUAL);
        lock.setLockedBy(operator);
        lock.setLockTime(now);
        lockMapper.insert(lock);

        if (PlaylistEnums.PLAYLIST_EDITING.equals(playlist.getStatus())) {
            Playlist update = new Playlist();
            update.setId(playlist.getId());
            update.setStatus(PlaylistEnums.PLAYLIST_LOCKED);
            playlistMapper.updateById(update);
            playlist.setStatus(PlaylistEnums.PLAYLIST_LOCKED);
        }
        pointCurrent(playlist, version);
        String eventType = PlaylistEnums.LOCK_AUTO.equals(lockSource)
                ? PlaylistEnums.EVENT_AUTO_LOCK : PlaylistEnums.EVENT_LOCK;
        appendEvent(playlist, version, eventType, operator,
                (PlaylistEnums.LOCK_AUTO.equals(lockSource) ? "周五下班前自动锁版：" : "人工锁版：")
                        + monday + " 周（v" + version.getVersionNo() + "，" + version.getSlotCount() + " 时段）", version);
        return version;
    }

    // ---- 锁版后改单与审批 ----

    /** 锁版后改单：提交改后整周编排，版本进 PENDING 待审批，旧锁版继续生效。 */
    @Transactional(rollbackFor = Exception.class)
    public PlaylistVersion requestChange(String playlistNo, PlaylistChangeRequest request, Viewer viewer) {
        Playlist playlist = requireByNo(playlistNo);
        LocalDate monday = resolveWeek(playlist, request.getWeekStart());
        PlaylistWeekLock lock = findLock(playlist.getId(), monday);
        if (lock == null) {
            throw new BusinessException(monday + " 周还没锁版，直接保存编排即可，不用走改单审批");
        }
        requireNoPendingChange(playlist.getId(), monday);
        String applicant = viewer != null && viewer.getUsername() != null
                ? viewer.getUsername() : Operators.resolve(request.getOperatorName());
        if (request.getReason() == null || request.getReason().trim().isEmpty()) {
            throw new BusinessException("锁版后改单必须填写申请原因");
        }
        List<Segment> segments = normalizeAndValidate(request.getSlots(), monday, playlist.getId());
        Map<String, Asset> assets = requireAssets(segments);

        PlaylistVersion version = newVersion(playlist, monday, PlaylistEnums.CHANGE_CHANGE_REQUEST, applicant, null);
        version.setStatus(PlaylistEnums.VERSION_PENDING);
        version.setApplicantName(applicant);
        version.setApplyReason(request.getReason());
        version.setApplyTime(LocalDateTime.now());
        versionMapper.insert(version);
        int itemCount = persistSegments(playlist, version, segments, assets);
        finishVersion(version, segments.size(), itemCount);
        pointCurrent(playlist, version);
        appendEvent(playlist, version, PlaylistEnums.EVENT_CHANGE_REQUEST, applicant,
                "锁版后改单申请：" + monday + " 周，原因「" + request.getReason() + "」，待审批", version);
        return version;
    }

    /** 审批通过：改单版本置 LOCKED 生效，原锁版版本置 SUPERSEDED，周锁版指针改到新版本。 */
    @Transactional(rollbackFor = Exception.class)
    public PlaylistVersion approveChange(String playlistNo, Long versionId, ChangeApprovalRequest request, Viewer viewer) {
        requireApprover(viewer);
        Playlist playlist = requireByNo(playlistNo);
        PlaylistVersion version = requireVersion(versionId);
        if (!version.getPlaylistId().equals(playlist.getId())) {
            throw new BusinessException("改单版本不属于播放列表 " + playlistNo);
        }
        if (!PlaylistEnums.VERSION_PENDING.equals(version.getStatus())) {
            throw new BusinessException("只有待审批的锁版改单可审批，该版本当前状态: "
                    + PlaylistEnums.versionStatusLabel(version.getStatus()));
        }
        String approver = viewer != null && viewer.getUsername() != null
                ? viewer.getUsername() : Operators.resolve(request.getOperatorName());
        if (approver.equals(version.getApplicantName())) {
            throw new BusinessException("申请人不能审批自己提交的锁版改单（职责分离）");
        }
        LocalDateTime now = LocalDateTime.now();

        version.setStatus(PlaylistEnums.VERSION_LOCKED);
        version.setChangeType(PlaylistEnums.CHANGE_APPROVE);
        version.setApproverName(approver);
        version.setApproveComment(request.getComment());
        version.setApproveTime(now);
        version.setLockedBy(approver);
        version.setLockTime(now);
        versionMapper.updateById(version);

        PlaylistWeekLock lock = findLock(playlist.getId(), version.getWeekStart());
        if (lock != null) {
            PlaylistVersion old = versionMapper.selectById(lock.getVersionId());
            if (old != null && PlaylistEnums.VERSION_LOCKED.equals(old.getStatus())) {
                PlaylistVersion superseded = new PlaylistVersion();
                superseded.setId(old.getId());
                superseded.setStatus(PlaylistEnums.VERSION_SUPERSEDED);
                versionMapper.updateById(superseded);
            }
            PlaylistWeekLock lockUpdate = new PlaylistWeekLock();
            lockUpdate.setId(lock.getId());
            lockUpdate.setVersionId(version.getId());
            lockUpdate.setLockedBy(approver);
            lockUpdate.setLockTime(now);
            lockMapper.updateById(lockUpdate);
        }
        pointCurrent(playlist, version);
        appendEvent(playlist, version, PlaylistEnums.EVENT_APPROVE, approver,
                "审批通过锁版改单：" + version.getWeekStart() + " 周 v" + version.getVersionNo()
                        + "（申请人 " + version.getApplicantName() + "）", version);
        return version;
    }

    /** 审批驳回：改单版本置 REJECTED 留底，原锁版继续生效。 */
    @Transactional(rollbackFor = Exception.class)
    public PlaylistVersion rejectChange(String playlistNo, Long versionId, ChangeApprovalRequest request, Viewer viewer) {
        requireApprover(viewer);
        Playlist playlist = requireByNo(playlistNo);
        PlaylistVersion version = requireVersion(versionId);
        if (!version.getPlaylistId().equals(playlist.getId())) {
            throw new BusinessException("改单版本不属于播放列表 " + playlistNo);
        }
        if (!PlaylistEnums.VERSION_PENDING.equals(version.getStatus())) {
            throw new BusinessException("只有待审批的锁版改单可驳回，该版本当前状态: "
                    + PlaylistEnums.versionStatusLabel(version.getStatus()));
        }
        String approver = viewer != null && viewer.getUsername() != null
                ? viewer.getUsername() : Operators.resolve(request.getOperatorName());
        if (approver.equals(version.getApplicantName())) {
            throw new BusinessException("申请人不能审批自己提交的锁版改单（职责分离）");
        }
        version.setStatus(PlaylistEnums.VERSION_REJECTED);
        version.setApproverName(approver);
        version.setApproveComment(request.getComment());
        version.setApproveTime(LocalDateTime.now());
        versionMapper.updateById(version);
        appendEvent(playlist, version, PlaylistEnums.EVENT_REJECT, approver,
                "驳回锁版改单：" + version.getWeekStart() + " 周 v" + version.getVersionNo()
                        + "（申请人 " + version.getApplicantName() + "）"
                        + (request.getComment() != null ? "；意见：" + request.getComment() : ""), version);
        return version;
    }

    // ---- 翻查 ----

    public List<Playlist> list(String siteNo, String status) {
        LambdaQueryWrapper<Playlist> wrapper = new LambdaQueryWrapper<>();
        if (siteNo != null && !siteNo.trim().isEmpty()) {
            wrapper.eq(Playlist::getSiteNo, siteNo.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(Playlist::getStatus, status.trim().toUpperCase());
        }
        return playlistMapper.selectList(wrapper.orderByDesc(Playlist::getId));
    }

    public Playlist requireByNo(String playlistNo) {
        if (playlistNo == null || playlistNo.trim().isEmpty()) {
            throw new BusinessException("播放列表单号不能为空");
        }
        Playlist playlist = playlistMapper.selectOne(new LambdaQueryWrapper<Playlist>()
                .eq(Playlist::getPlaylistNo, playlistNo.trim()));
        if (playlist == null) {
            throw new BusinessException("播放列表不存在: " + playlistNo);
        }
        return playlist;
    }

    public Playlist requireById(Long id) {
        Playlist playlist = playlistMapper.selectById(id);
        if (playlist == null) {
            throw new BusinessException("播放列表不存在: id=" + id);
        }
        return playlist;
    }

    /** 版本链翻查（新版本号在前）。 */
    public List<PlaylistVersion> listVersions(String playlistNo) {
        Playlist playlist = requireByNo(playlistNo);
        return versionMapper.selectList(new LambdaQueryWrapper<PlaylistVersion>()
                .eq(PlaylistVersion::getPlaylistId, playlist.getId())
                .orderByDesc(PlaylistVersion::getVersionNo));
    }

    /** 待审批改单翻查：可按屏过滤。 */
    public List<PlaylistVersion> listPendingChanges(String playlistNo) {
        LambdaQueryWrapper<PlaylistVersion> wrapper = new LambdaQueryWrapper<PlaylistVersion>()
                .eq(PlaylistVersion::getStatus, PlaylistEnums.VERSION_PENDING)
                .orderByAsc(PlaylistVersion::getId);
        if (playlistNo != null && !playlistNo.trim().isEmpty()) {
            Playlist playlist = requireByNo(playlistNo);
            wrapper.eq(PlaylistVersion::getPlaylistId, playlist.getId());
        }
        return versionMapper.selectList(wrapper);
    }

    /** 某周编排视图：优先锁版版本，否则最新草稿；含按自然日切分的时段与播放单。 */
    public WeekScheduleView weekView(String playlistNo, LocalDate weekAnchor) {
        Playlist playlist = requireByNo(playlistNo);
        LocalDate monday = resolveWeek(playlist, weekAnchor);
        PlaylistWeekLock lock = findLock(playlist.getId(), monday);
        PlaylistVersion version;
        if (lock != null) {
            version = versionMapper.selectById(lock.getVersionId());
        } else {
            version = latestWeekVersion(playlist.getId(), monday);
        }
        WeekScheduleView view = new WeekScheduleView();
        view.setWeekStart(monday);
        view.setWeekEnd(monday.plusDays(6));
        view.setLock(lock);
        view.setLocked(lock != null);
        view.setVersion(version);
        view.setSlots(version == null ? new ArrayList<>() : toSlotViews(version));
        return view;
    }

    /** 全量档案。 */
    public PlaylistDetailView detail(String playlistNo) {
        Playlist playlist = requireByNo(playlistNo);
        PlaylistDetailView view = new PlaylistDetailView();
        view.setPlaylist(playlist);
        view.setVersions(versionMapper.selectList(new LambdaQueryWrapper<PlaylistVersion>()
                .eq(PlaylistVersion::getPlaylistId, playlist.getId())
                .orderByDesc(PlaylistVersion::getVersionNo)));
        view.setLocks(lockMapper.selectList(new LambdaQueryWrapper<PlaylistWeekLock>()
                .eq(PlaylistWeekLock::getPlaylistId, playlist.getId())
                .orderByAsc(PlaylistWeekLock::getWeekStart)));
        view.setEvents(eventMapper.selectList(new LambdaQueryWrapper<PlaylistEvent>()
                .eq(PlaylistEvent::getPlaylistId, playlist.getId())
                .orderByAsc(PlaylistEvent::getId)));
        view.setAlerts(alertMapper.selectList(new LambdaQueryWrapper<PlaylistAlert>()
                .eq(PlaylistAlert::getPlaylistId, playlist.getId())
                .orderByDesc(PlaylistAlert::getId)));
        return view;
    }

    public List<PlaylistEvent> listEvents(String playlistNo) {
        Playlist playlist = requireByNo(playlistNo);
        return eventMapper.selectList(new LambdaQueryWrapper<PlaylistEvent>()
                .eq(PlaylistEvent::getPlaylistId, playlist.getId())
                .orderByAsc(PlaylistEvent::getId));
    }

    public List<PlaylistAlert> listAlerts(String status, String playlistNo) {
        LambdaQueryWrapper<PlaylistAlert> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(PlaylistAlert::getStatus, status.trim().toUpperCase());
        }
        if (playlistNo != null && !playlistNo.trim().isEmpty()) {
            Playlist playlist = requireByNo(playlistNo);
            wrapper.eq(PlaylistAlert::getPlaylistId, playlist.getId());
        }
        return alertMapper.selectList(wrapper.orderByDesc(PlaylistAlert::getId));
    }

    /** 告警解除（人工确认处理）。 */
    @Transactional(rollbackFor = Exception.class)
    public PlaylistAlert resolveAlert(Long alertId, String operatorName, String comment) {
        PlaylistAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw new BusinessException("排片告警不存在: id=" + alertId);
        }
        if (PlaylistEnums.ALERT_RESOLVED.equals(alert.getStatus())) {
            throw new BusinessException("该告警已解除，请勿重复操作");
        }
        alert.setStatus(PlaylistEnums.ALERT_RESOLVED);
        alert.setResolvedBy(Operators.resolve(operatorName));
        alert.setResolvedTime(LocalDateTime.now());
        alert.setResolveComment(comment);
        alertMapper.updateById(alert);
        return alert;
    }

    // ---- 内部/包内辅助 ----

    /** 该屏全部播放列表（切换/锁版任务用）。 */
    List<Playlist> allPlaylists() {
        return playlistMapper.selectList(new LambdaQueryWrapper<Playlist>().orderByAsc(Playlist::getId));
    }

    PlaylistWeekLock findLock(Long playlistId, LocalDate monday) {
        return lockMapper.selectOne(new LambdaQueryWrapper<PlaylistWeekLock>()
                .eq(PlaylistWeekLock::getPlaylistId, playlistId)
                .eq(PlaylistWeekLock::getWeekStart, monday));
    }

    /** 该周最新版本（任意状态）。 */
    PlaylistVersion latestWeekVersion(Long playlistId, LocalDate monday) {
        List<PlaylistVersion> versions = weekVersions(playlistId, monday);
        return versions.isEmpty() ? null : versions.get(0);
    }

    /** 该周最新可编辑草稿（已锁版返回 null）。 */
    private PlaylistVersion editableWeekVersion(Long playlistId, LocalDate monday) {
        PlaylistWeekLock lock = findLock(playlistId, monday);
        if (lock != null) {
            return null;
        }
        return latestWeekVersion(playlistId, monday);
    }

    private List<PlaylistVersion> weekVersions(Long playlistId, LocalDate monday) {
        return versionMapper.selectList(new LambdaQueryWrapper<PlaylistVersion>()
                .eq(PlaylistVersion::getPlaylistId, playlistId)
                .eq(PlaylistVersion::getWeekStart, monday)
                .orderByDesc(PlaylistVersion::getVersionNo));
    }

    long countSlots(Long versionId) {
        Long n = slotMapper.selectCount(new LambdaQueryWrapper<PlaylistSlot>()
                .eq(PlaylistSlot::getVersionId, versionId));
        return n == null ? 0 : n;
    }

    /** 该周待审批改单条数（周五自动锁版任务用）。 */
    long countPendingChanges(Long playlistId, LocalDate monday) {
        Long n = versionMapper.selectCount(new LambdaQueryWrapper<PlaylistVersion>()
                .eq(PlaylistVersion::getPlaylistId, playlistId)
                .eq(PlaylistVersion::getWeekStart, monday)
                .eq(PlaylistVersion::getStatus, PlaylistEnums.VERSION_PENDING));
        return n == null ? 0 : n;
    }

    private List<PlaylistSlot> listSlotsOrdered(Long versionId) {
        return slotMapper.selectList(new LambdaQueryWrapper<PlaylistSlot>()
                .eq(PlaylistSlot::getVersionId, versionId)
                .orderByAsc(PlaylistSlot::getSlotDate)
                .orderByAsc(PlaylistSlot::getStartTime)
                .orderByAsc(PlaylistSlot::getId));
    }

    private Map<Long, List<PlaylistItem>> loadItemsBySlot(List<PlaylistSlot> slots) {
        if (slots.isEmpty()) {
            return new HashMap<>();
        }
        List<PlaylistItem> items = itemMapper.selectList(new LambdaQueryWrapper<PlaylistItem>()
                .in(PlaylistItem::getSlotId, slots.stream().map(PlaylistSlot::getId).collect(Collectors.toList()))
                .orderByAsc(PlaylistItem::getSlotId)
                .orderByAsc(PlaylistItem::getSeq));
        Map<Long, List<PlaylistItem>> map = new LinkedHashMap<>();
        for (PlaylistItem item : items) {
            map.computeIfAbsent(item.getSlotId(), k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private List<SlotItemSpec> toSpecs(List<PlaylistItem> items) {
        List<SlotItemSpec> specs = new ArrayList<>();
        if (items == null) {
            return specs;
        }
        for (PlaylistItem item : items) {
            SlotItemSpec spec = new SlotItemSpec();
            spec.setAssetNo(item.getAssetNo());
            spec.setDurationSec(item.getDurationSec());
            specs.add(spec);
        }
        return specs;
    }

    /** 由顺延后的 Segment 列表重建规格（用于统一走 normalizeAndValidate 校验）。 */
    private List<SlotSpec> toSlotSpecs(List<Segment> segments, LocalDate monday) {
        // 顺延后跨零点已被 splitAbsolute 切成自然日段；逐段当作同日不跨零点规格回灌
        List<SlotSpec> specs = new ArrayList<>();
        for (Segment s : segments) {
            SlotSpec spec = new SlotSpec();
            spec.setDate(s.date);
            spec.setStartTime(s.start.toString());
            spec.setEndTime(s.endReachMidnight ? "00:00" : s.end.toString());
            spec.setCrossMidnight(s.endReachMidnight);
            spec.setItems(s.items);
            specs.add(spec);
        }
        return specs;
    }

    private List<Segment> loadSegments(PlaylistVersion version) {
        List<PlaylistSlot> slots = listSlotsOrdered(version.getId());
        Map<Long, List<PlaylistItem>> itemsBySlot = loadItemsBySlot(slots);
        List<Segment> segments = new ArrayList<>();
        for (PlaylistSlot slot : slots) {
            Segment s = new Segment();
            s.date = slot.getSlotDate();
            s.start = slot.getStartTime();
            s.end = slot.getEndTime();
            s.endReachMidnight = slot.getEndReachMidnight() == 1;
            s.startFromMidnight = slot.getStartFromMidnight() == 1;
            s.crossMidnight = slot.getCrossMidnight() == 1;
            s.items = toSpecs(itemsBySlot.get(slot.getId()));
            segments.add(s);
        }
        return segments;
    }

    private List<SlotView> toSlotViews(PlaylistVersion version) {
        List<PlaylistSlot> slots = listSlotsOrdered(version.getId());
        Map<Long, List<PlaylistItem>> itemsBySlot = loadItemsBySlot(slots);
        List<SlotView> views = new ArrayList<>();
        for (PlaylistSlot slot : slots) {
            SlotView view = new SlotView();
            view.setSeq(slot.getSeq());
            view.setSlotDate(slot.getSlotDate());
            view.setStartTime(slot.getStartTime());
            view.setEndTime(slot.getEndTime());
            view.setCrossMidnight(slot.getCrossMidnight() == 1);
            view.setEndReachMidnight(slot.getEndReachMidnight() == 1);
            view.setStartFromMidnight(slot.getStartFromMidnight() == 1);
            view.setItems(itemsBySlot.getOrDefault(slot.getId(), new ArrayList<>()));
            views.add(view);
        }
        return views;
    }

    private int copyPersisted(Playlist playlist, PlaylistVersion source, PlaylistVersion target,
                              LocalDate fromMonday, LocalDate toMonday) {
        long dayShift = java.time.temporal.ChronoUnit.DAYS.between(fromMonday, toMonday);
        List<PlaylistSlot> slots = listSlotsOrdered(source.getId());
        Map<Long, List<PlaylistItem>> itemsBySlot = loadItemsBySlot(slots);
        // 复制段仍需出周校验与冲突校验（不同周日期平移后理论无撞，保险走一遍）
        List<Segment> segments = new ArrayList<>();
        Map<Long, Long> slotIdMap = new HashMap<>();
        int seq = 0;
        int itemCount = 0;
        for (PlaylistSlot slot : slots) {
            PlaylistSlot copy = new PlaylistSlot();
            copy.setPlaylistId(playlist.getId());
            copy.setVersionId(target.getId());
            copy.setSlotDate(slot.getSlotDate().plusDays(dayShift));
            copy.setStartTime(slot.getStartTime());
            copy.setEndTime(slot.getEndTime());
            copy.setEndReachMidnight(slot.getEndReachMidnight());
            copy.setStartFromMidnight(slot.getStartFromMidnight());
            copy.setCrossMidnight(slot.getCrossMidnight());
            copy.setSeq(++seq);
            slotMapper.insert(copy);
            slotIdMap.put(slot.getId(), copy.getId());

            Segment s = new Segment();
            s.date = copy.getSlotDate();
            s.start = copy.getStartTime();
            s.end = copy.getEndTime();
            s.endReachMidnight = copy.getEndReachMidnight() == 1;
            s.items = toSpecs(itemsBySlot.get(slot.getId()));
            segments.add(s);

            int itemSeq = 0;
            for (PlaylistItem item : itemsBySlot.getOrDefault(slot.getId(), new ArrayList<>())) {
                PlaylistItem ci = new PlaylistItem();
                ci.setPlaylistId(playlist.getId());
                ci.setVersionId(target.getId());
                ci.setSlotId(copy.getId());
                ci.setSeq(++itemSeq);
                ci.setAssetId(item.getAssetId());
                ci.setAssetNo(item.getAssetNo());
                ci.setAssetNameSnapshot(item.getAssetNameSnapshot());
                ci.setDurationSec(item.getDurationSec());
                itemMapper.insert(ci);
                itemCount++;
            }
        }
        validateWeek(playlist.getId(), toMonday, segments);
        return itemCount;
    }

    /**
     * 规格 → 切分后自然日段：解析跨零点、周界/配对校验、同屏同日冲突校验、与相邻周溢出段冲突校验。
     * anchorDate：规格未带 date 时归到该周周一。
     */
    private List<Segment> normalizeAndValidate(List<SlotSpec> specs, LocalDate monday, Long playlistId) {
        List<Segment> segments = new ArrayList<>();
        for (SlotSpec spec : specs) {
            segments.addAll(PlaylistSegments.parse(spec, spec.getDate() != null ? spec.getDate() : monday));
        }
        return validateWeek(playlistId, monday, segments);
    }

    /**
     * 自然日段统一校验（保存/复制/顺延共用）：
     * 1. 周界：起点段必须在 [周一, 周日]；唯一允许溢出到下周一的，是周日跨零点段的后半（00:00 起），
     *    且必须与一行“周日到 24:00”的前半配对，顺延把段推出周的情形会被拦下；
     * 2. 同屏同一自然日段间半开区间重叠即撞（首尾相接不算）；
     * 3. 跨版本：本周一凌晨的段要让开上一周版本周日跨零点的溢出段；本周日溢出到下周一的段
     *    要让开下一周版本周一凌晨的段——跨零点切到自然日后，相邻周版本在同一自然日上也只许一条有效排片。
     */
    private List<Segment> validateWeek(Long playlistId, LocalDate monday, List<Segment> raw) {
        List<Segment> segments = new ArrayList<>(raw);
        segments.sort(Comparator.comparing((Segment s) -> s.date).thenComparingInt(Segment::startSec));
        LocalDate sunday = monday.plusDays(6);
        LocalDate nextMonday = sunday.plusDays(1);

        boolean hasSundayReachMidnight = segments.stream().anyMatch(
                s -> s.date.equals(sunday) && s.endReachMidnight);
        for (Segment s : segments) {
            boolean overflowTail = s.startFromMidnight && s.date.equals(nextMonday);
            if (overflowTail) {
                if (!hasSundayReachMidnight) {
                    throw new BusinessException("下周一（" + nextMonday + "）00:00 起的时段必须由周日跨零点段带出，"
                            + "不能直接把段排到下周；整周顺延越界请调整顺延分钟数");
                }
                continue;
            }
            if (s.date.isBefore(monday) || s.date.isAfter(sunday)) {
                throw new BusinessException("时段 " + s.date + " " + s.timeText()
                        + " 超出编排周（" + monday + "~" + sunday + "），请排到对应那一周");
            }
        }
        PlaylistSegments.checkNoOverlap(segments);
        checkNeighborOverlap(playlistId, monday, segments);
        return segments;
    }

    /** 与相邻周当前采用版本（已锁版取锁版版本，否则最新版本）在边界自然日上再比一次冲突。 */
    private void checkNeighborOverlap(Long playlistId, LocalDate monday, List<Segment> segments) {
        LocalDate nextMonday = monday.plusWeeks(1);
        LocalDate prevMonday = monday.minusWeeks(1);
        compareOverlapOnDate(playlistId, monday, segments,
                effectiveWeekVersion(playlistId, prevMonday), monday,
                "上一周（" + prevMonday + " 周）周日跨零点到本周一凌晨的时段");
        compareOverlapOnDate(playlistId, nextMonday, segments,
                effectiveWeekVersion(playlistId, nextMonday), nextMonday,
                "下一周（" + nextMonday + " 周）周一凌晨的时段");
    }

    /** 某周“当前采用”的版本：已锁版取锁版版本，没锁取最新版本（草稿/待审批），没有返回 null。 */
    private PlaylistVersion effectiveWeekVersion(Long playlistId, LocalDate monday) {
        PlaylistWeekLock lock = findLock(playlistId, monday);
        if (lock != null) {
            return versionMapper.selectById(lock.getVersionId());
        }
        return latestWeekVersion(playlistId, monday);
    }

    private void compareOverlapOnDate(Long playlistId, LocalDate date, List<Segment> candidate,
                                      PlaylistVersion neighbor, LocalDate neighborDate, String neighborDesc) {
        if (neighbor == null) {
            return;
        }
        List<Segment> onDate = candidate.stream()
                .filter(s -> s.date.equals(date)).collect(Collectors.toList());
        if (onDate.isEmpty()) {
            return;
        }
        List<PlaylistSlot> neighborSlots = slotMapper.selectList(new LambdaQueryWrapper<PlaylistSlot>()
                .eq(PlaylistSlot::getVersionId, neighbor.getId())
                .eq(PlaylistSlot::getSlotDate, neighborDate)
                .orderByAsc(PlaylistSlot::getStartTime));
        if (neighborSlots.isEmpty()) {
            return;
        }
        Map<Long, List<PlaylistItem>> itemsBySlot = loadItemsBySlot(neighborSlots);
        List<Segment> merged = new ArrayList<>(onDate);
        for (PlaylistSlot slot : neighborSlots) {
            Segment s = new Segment();
            s.date = slot.getSlotDate();
            s.start = slot.getStartTime();
            s.end = slot.getEndTime();
            s.endReachMidnight = slot.getEndReachMidnight() == 1;
            s.startFromMidnight = slot.getStartFromMidnight() == 1;
            s.crossMidnight = slot.getCrossMidnight() == 1;
            s.items = toSpecs(itemsBySlot.get(slot.getId()));
            merged.add(s);
        }
        try {
            PlaylistSegments.checkNoOverlap(merged);
        } catch (BusinessException e) {
            throw new BusinessException(e.getMessage() + "；对方是" + neighborDesc + "（v"
                    + neighbor.getVersionNo() + "），请调整后再保存");
        }
    }

    /** 素材必须来自素材库：按编号批量核验，不存在或已归档都拒；返回编号→素材。 */
    private Map<String, Asset> requireAssets(List<Segment> segments) {
        Set<String> nos = new LinkedHashSet<>();
        for (Segment s : segments) {
            for (SlotItemSpec item : s.items) {
                if (item.getAssetNo() == null || item.getAssetNo().trim().isEmpty()) {
                    throw new BusinessException("时段 " + s.date + " " + s.timeText() + " 里有条播放单没选素材");
                }
                if (item.getDurationSec() != null && item.getDurationSec() <= 0) {
                    throw new BusinessException("素材 " + item.getAssetNo() + " 的播放时长必须是正数秒");
                }
                nos.add(item.getAssetNo().trim());
            }
        }
        if (nos.isEmpty()) {
            throw new BusinessException("每个时段至少要挑一条素材");
        }
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .in(Asset::getAssetNo, nos));
        Map<String, Asset> byNo = new HashMap<>();
        for (Asset asset : assets) {
            byNo.put(asset.getAssetNo(), asset);
        }
        for (String no : nos) {
            Asset asset = byNo.get(no);
            if (asset == null) {
                throw new BusinessException("素材 " + no + " 不在素材库里，请先上传转档后再排");
            }
            if (com.evops.asset.AssetEnums.ASSET_ARCHIVED.equals(asset.getStatus())) {
                throw new BusinessException("素材 " + no + " 已归档，不能再排进播放单（请启用或换素材）");
            }
        }
        return byNo;
    }

    private int persistSegments(Playlist playlist, PlaylistVersion version,
                                List<Segment> segments, Map<String, Asset> assets) {
        int itemCount = 0;
        int seq = 0;
        for (Segment s : segments) {
            PlaylistSlot slot = new PlaylistSlot();
            slot.setPlaylistId(playlist.getId());
            slot.setVersionId(version.getId());
            slot.setSlotDate(s.date);
            slot.setStartTime(s.start);
            slot.setEndTime(s.end);
            slot.setEndReachMidnight(s.endReachMidnight ? 1 : 0);
            slot.setStartFromMidnight(s.startFromMidnight ? 1 : 0);
            slot.setCrossMidnight(s.crossMidnight ? 1 : 0);
            slot.setSeq(++seq);
            slotMapper.insert(slot);

            int itemSeq = 0;
            for (SlotItemSpec spec : s.items) {
                Asset asset = assets.get(spec.getAssetNo().trim());
                PlaylistItem item = new PlaylistItem();
                item.setPlaylistId(playlist.getId());
                item.setVersionId(version.getId());
                item.setSlotId(slot.getId());
                item.setSeq(++itemSeq);
                item.setAssetId(asset.getId());
                item.setAssetNo(asset.getAssetNo());
                item.setAssetNameSnapshot(asset.getFileName());
                item.setDurationSec(spec.getDurationSec());
                itemMapper.insert(item);
                itemCount++;
            }
        }
        return itemCount;
    }

    private void finishVersion(PlaylistVersion version, int slotCount, int itemCount) {
        version.setSlotCount(slotCount);
        version.setItemCount(itemCount);
        version.setSnapshot(snapshot(buildWeekView(version)));
        versionMapper.updateById(version);
    }

    private WeekScheduleView buildWeekView(PlaylistVersion version) {
        WeekScheduleView view = new WeekScheduleView();
        view.setWeekStart(version.getWeekStart());
        view.setWeekEnd(version.getWeekEnd());
        view.setVersion(version);
        view.setLocked(PlaylistEnums.VERSION_LOCKED.equals(version.getStatus()));
        view.setSlots(toSlotViews(version));
        return view;
    }

    private WeekScheduleView buildWeekView(Playlist playlist, PlaylistVersion version) {
        return buildWeekView(version);
    }

    private PlaylistVersion newVersion(Playlist playlist, LocalDate monday, String changeType,
                                       String operator, String remark) {
        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<PlaylistVersion>()
                        .eq(PlaylistVersion::getPlaylistId, playlist.getId()))
                .stream().map(PlaylistVersion::getVersionNo).max(Integer::compareTo).orElse(0);
        PlaylistVersion version = new PlaylistVersion();
        version.setPlaylistId(playlist.getId());
        version.setVersionNo(maxNo + 1);
        version.setWeekStart(monday);
        version.setWeekEnd(monday.plusDays(6));
        version.setChangeType(changeType);
        version.setStatus(PlaylistEnums.VERSION_DRAFT);
        version.setSlotCount(0);
        version.setItemCount(0);
        version.setOperatorName(operator);
        version.setRemark(remark);
        return version;
    }

    private void pointCurrent(Playlist playlist, PlaylistVersion version) {
        Playlist update = new Playlist();
        update.setId(playlist.getId());
        update.setCurrentVersionId(version.getId());
        update.setCurrentVersionNo(version.getVersionNo());
        playlistMapper.updateById(update);
        playlist.setCurrentVersionId(version.getId());
        playlist.setCurrentVersionNo(version.getVersionNo());
    }

    private void appendEvent(Playlist playlist, PlaylistVersion version, String eventType,
                             String operator, String detail, Object snapshotObj) {
        PlaylistEvent event = new PlaylistEvent();
        event.setPlaylistId(playlist.getId());
        event.setPlaylistNo(playlist.getPlaylistNo());
        event.setVersionNo(version.getVersionNo());
        event.setEventType(eventType);
        event.setOperatorName(operator);
        event.setDetail(detail);
        event.setSnapshot(snapshot(snapshotObj));
        event.setEventTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private String snapshot(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("播放列表快照序列化失败", e);
        }
    }

    private PlaylistVersion requireVersion(Long versionId) {
        PlaylistVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("播放列表版本不存在: id=" + versionId);
        }
        return version;
    }

    private void requireWeekNotLocked(Long playlistId, LocalDate monday, String action) {
        if (findLock(playlistId, monday) != null) {
            throw new BusinessException(monday + " 周已锁版，不能" + action + "；锁版后改动请走改单审批");
        }
    }

    private void requireNoPendingChange(Long playlistId, LocalDate monday) {
        Long pending = versionMapper.selectCount(new LambdaQueryWrapper<PlaylistVersion>()
                .eq(PlaylistVersion::getPlaylistId, playlistId)
                .eq(PlaylistVersion::getWeekStart, monday)
                .eq(PlaylistVersion::getStatus, PlaylistEnums.VERSION_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException(monday + " 周有锁版改单正在待审批，请先等审批结果再编排该周");
        }
    }

    private LocalDate resolveWeek(Playlist playlist, LocalDate anchor) {
        if (anchor != null) {
            return WeekRanges.mondayOf(anchor);
        }
        if (playlist.getCurrentVersionId() != null) {
            PlaylistVersion current = versionMapper.selectById(playlist.getCurrentVersionId());
            if (current != null) {
                return current.getWeekStart();
            }
        }
        return WeekRanges.mondayOf(LocalDate.now());
    }

    private void requireApprover(Viewer viewer) {
        if (viewer == null) {
            throw new BusinessException("未识别审批人：请以财务/系统账号登录后审批");
        }
        viewer.requireApprove();
    }

    private String generateNo() {
        long seq = playlistMapper.nextPlaylistSeq();
        return String.format("PL%010d", seq);
    }

    /** 供切换任务读取：某周锁版版本。 */
    PlaylistVersion lockedVersion(Long playlistId, LocalDate monday) {
        PlaylistWeekLock lock = findLock(playlistId, monday);
        return lock == null ? null : versionMapper.selectById(lock.getVersionId());
    }

    /** 供切换任务推进播放器 live 指针。 */
    void updateLivePointer(Playlist playlist) {
        playlistMapper.updateById(playlist);
    }

    /**
     * 写一条排片告警（切换失败/空档/素材缺失等），按 dedupKey 幂等：
     * 同一屏同一自然日同一时段同一类告警只落一条，定时任务重复扫不重发。
     * 已存在同类告警时返回 null。
     */
    PlaylistAlert raiseAlert(Playlist playlist, String alertType, LocalDate switchDate,
                             Integer slotSeq, Long versionId, String detail) {
        PlaylistAlert alert = new PlaylistAlert();
        alert.setPlaylistId(playlist.getId());
        alert.setPlaylistNo(playlist.getPlaylistNo());
        alert.setSiteNo(playlist.getSiteNo());
        alert.setAlertType(alertType);
        alert.setTriggerTime(LocalDateTime.now());
        alert.setSwitchDate(switchDate);
        alert.setSlotSeq(slotSeq);
        alert.setVersionId(versionId);
        alert.setDetail(detail);
        alert.setStatus(PlaylistEnums.ALERT_OPEN);
        alert.setDedupKey(buildDedupKey(playlist.getId(), alertType, switchDate, slotSeq));
        try {
            alertMapper.insert(alert);
            return alert;
        } catch (DuplicateKeyException e) {
            // 同一告警已报过：不重发、不静默刷屏
            return null;
        }
    }

    /** 切换任务落 live 指针后写 SWITCH 事件留底。 */
    void recordSwitchEvent(Playlist playlist, PlaylistVersion version, LocalDate date,
                           Integer slotSeq, String detail) {
        PlaylistEvent event = new PlaylistEvent();
        event.setPlaylistId(playlist.getId());
        event.setPlaylistNo(playlist.getPlaylistNo());
        event.setVersionNo(version != null ? version.getVersionNo() : 0);
        event.setEventType(PlaylistEnums.EVENT_SWITCH);
        event.setOperatorName("SYSTEM");
        event.setDetail(detail);
        event.setSnapshot(snapshot(java.util.Collections.singletonMap("live",
                java.util.Collections.singletonMap(date + " #" + slotSeq,
                        version != null ? version.getVersionNo() : null))));
        event.setEventTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private String buildDedupKey(Long playlistId, String type, LocalDate date, Integer slotSeq) {
        return playlistId + "|" + type + "|" + date + "|" + (slotSeq == null ? 0 : slotSeq);
    }
}
