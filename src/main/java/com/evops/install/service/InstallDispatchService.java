package com.evops.install.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.asset.support.ImageFingerprints;
import com.evops.common.BusinessException;
import com.evops.install.InstallEnums;
import com.evops.install.dto.ArriveRequest;
import com.evops.install.dto.CompleteRequest;
import com.evops.install.dto.InstallDetailView;
import com.evops.install.dto.ManualMatchRequest;
import com.evops.install.dto.RescheduleRequest;
import com.evops.install.dto.TeamMonthStat;
import com.evops.install.entity.InstallEvent;
import com.evops.install.entity.InstallNotice;
import com.evops.install.entity.InstallOrder;
import com.evops.install.entity.InstallPhoto;
import com.evops.install.entity.InstallReceipt;
import com.evops.install.entity.InstallTeam;
import com.evops.install.mapper.InstallEventMapper;
import com.evops.install.mapper.InstallNoticeMapper;
import com.evops.install.mapper.InstallOrderMapper;
import com.evops.install.mapper.InstallPhotoMapper;
import com.evops.install.mapper.InstallReceiptMapper;
import com.evops.install.mapper.InstallTeamMapper;
import com.evops.install.support.InstallPhotoStorage;
import com.evops.install.support.InstallProperties;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.PostingOrderMapper;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.support.Operators;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 安装派单主线：
 * 上刊单确认后自动生成派单（默认档期）→ 按「资质 + 档期」匹配安装队（无合格空闲队落待匹配，
 * 定时补扫/人工补匹配）→ 师傅到场拍照打卡 → 完工回传回执（上刊单随状态走）；
 * 改期通知到队；上刊单对冲联动取消。
 *
 * 两条硬口径：
 * - 高空证过期接不了高空点位：匹配按计划作业日校验高空作业证有效期（到期当日仍有效）；
 * - 一个队同一时段只排一个点位：[planStart, planEnd) 半开区间不许重叠，匹配时对候选队加
 *   行级悲观锁串行化，首尾相接不算撞；并发由队行锁 + 排期查询双保险兜底。
 */
@Service
public class InstallDispatchService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InstallOrderMapper orderMapper;
    private final InstallEventMapper eventMapper;
    private final InstallPhotoMapper photoMapper;
    private final InstallReceiptMapper receiptMapper;
    private final InstallNoticeMapper noticeMapper;
    private final InstallTeamMapper teamMapper;
    private final InstallTeamService teamService;
    private final SiteMapper siteMapper;
    private final PostingOrderMapper postingOrderMapper;
    private final InstallPhotoStorage storage;
    private final InstallProperties properties;
    private final ObjectMapper objectMapper;

    public InstallDispatchService(InstallOrderMapper orderMapper, InstallEventMapper eventMapper,
                                  InstallPhotoMapper photoMapper, InstallReceiptMapper receiptMapper,
                                  InstallNoticeMapper noticeMapper, InstallTeamMapper teamMapper,
                                  InstallTeamService teamService,
                                  SiteMapper siteMapper, PostingOrderMapper postingOrderMapper,
                                  InstallPhotoStorage storage, InstallProperties properties,
                                  ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.eventMapper = eventMapper;
        this.photoMapper = photoMapper;
        this.receiptMapper = receiptMapper;
        this.noticeMapper = noticeMapper;
        this.teamMapper = teamMapper;
        this.teamService = teamService;
        this.siteMapper = siteMapper;
        this.postingOrderMapper = postingOrderMapper;
        this.storage = storage;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ---- 上刊确认联动：自动生成派单 ----

    /** 上刊确认后：自动生成派单（默认档期）并立即匹配；该上刊单已有派单则幂等跳过。 */
    @Transactional(rollbackFor = Exception.class)
    public void handlePostingConfirmed(PostingOrder posting, String operatorName) {
        InstallOrder existing = findByOrderId(posting.getId());
        if (existing != null) {
            return;
        }
        String operator = Operators.resolve(operatorName);
        Site site = siteMapper.selectById(posting.getSiteId());
        String workType = site != null && site.getWorkType() != null
                ? site.getWorkType() : com.evops.ledger.LedgerEnums.WORK_NORMAL;

        LocalDateTime[] window = defaultWindow(posting.getWeekStart());
        InstallOrder dispatch = new InstallOrder();
        dispatch.setDispatchNo(generateNo());
        dispatch.setOrderId(posting.getId());
        dispatch.setOrderNo(posting.getOrderNo());
        dispatch.setSiteId(posting.getSiteId());
        dispatch.setSiteNo(site != null ? site.getSiteNo() : String.valueOf(posting.getSiteId()));
        dispatch.setBusinessDistrict(site != null ? site.getBusinessDistrict() : null);
        dispatch.setCreativeName(posting.getCreativeName());
        dispatch.setWorkType(workType);
        dispatch.setStatus(InstallEnums.STATUS_PENDING);
        dispatch.setVersionNo(1);
        dispatch.setPlanStart(window[0]);
        dispatch.setPlanEnd(window[1]);
        dispatch.setRescheduleCount(0);
        dispatch.setOperatorName(operator);
        try {
            orderMapper.insert(dispatch);
        } catch (DuplicateKeyException e) {
            // 并发：该上刊单已生成派单，幂等返回
            return;
        }
        mirrorPosting(dispatch, false);
        appendEvent(dispatch, InstallEnums.EVENT_AUTO_CREATE, operator,
                "上刊单 " + posting.getOrderNo() + " 确认，自动生成安装派单（"
                        + InstallEnums.workTypeLabel(workType) + "，默认档期 "
                        + window[0].format(TS) + "~" + window[1].format(TS) + "）");

        // 立即试匹配；没有合格空闲队就留在待匹配，等补扫或人工补匹配
        matchEngine(dispatch, InstallEnums.MATCH_AUTO, operator);

        // 把安装派单状态回填到上刊返回对象（DB 已在 mirrorPosting 落库，这里让上刊接口响应即时可见）
        posting.setInstallDispatchId(dispatch.getId());
        posting.setInstallStatus(dispatch.getStatus());
        if (dispatch.getFinishTime() != null) {
            posting.setInstallFinishTime(dispatch.getFinishTime());
        }
    }

    /** 上刊单对冲：联动取消未完工派单释放档期；已完工的保留不阻断对冲。 */
    @Transactional(rollbackFor = Exception.class)
    public void handlePostingOffset(PostingOrder original, String operatorName) {
        InstallOrder dispatch = findByOrderId(original.getId());
        if (dispatch == null) {
            return;
        }
        if (InstallEnums.STATUS_CANCELLED.equals(dispatch.getStatus())
                || InstallEnums.STATUS_COMPLETED.equals(dispatch.getStatus())) {
            return;
        }
        String operator = Operators.resolve(operatorName);
        dispatch.setStatus(InstallEnums.STATUS_CANCELLED);
        dispatch.setCancelReason("来源上刊单 " + original.getOrderNo() + " 对冲更正，联动取消安装派单");
        dispatch.setCancelledTime(LocalDateTime.now());
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        orderMapper.updateById(dispatch);
        mirrorPosting(dispatch, false);
        appendEvent(dispatch, InstallEnums.EVENT_CANCEL, operator, dispatch.getCancelReason());
    }

    // ---- 匹配引擎 ----

    /**
     * 待匹配补扫：把所有 PENDING 派单逐个尝试按资质+档期匹配，返回本次新派成的数量。
     * 供定时任务与人工触发调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public int autoMatchPending() {
        List<InstallOrder> pending = orderMapper.selectList(new LambdaQueryWrapper<InstallOrder>()
                .eq(InstallOrder::getStatus, InstallEnums.STATUS_PENDING)
                .orderByAsc(InstallOrder::getId));
        int matched = 0;
        for (InstallOrder dispatch : pending) {
            if (doMatch(dispatch, InstallEnums.MATCH_AUTO, "SYSTEM")) {
                matched++;
            }
        }
        return matched;
    }

    /**
     * 按资质与档期为派单选第一个合格且空闲的在册安装队。
     * 选中即派成（ASSIGNED）；无合格空闲队留在 PENDING。是否派成返回布尔。
     */
    private boolean matchEngine(InstallOrder dispatch, String matchSource, String operator) {
        return doMatch(dispatch, matchSource, operator);
    }

    private boolean doMatch(InstallOrder dispatch, String matchSource, String operator) {
        LocalDate planDate = dispatch.getPlanStart().toLocalDate();
        for (InstallTeam candidate : teamService.activeTeams()) {
            // 锁住候选队串行化：并发两张单排同一队时，后一个在这等前一个提交后看到档期已占
            InstallTeam locked = teamMapper.lockById(candidate.getId());
            if (!teamService.isQualified(locked, dispatch.getWorkType(), planDate)) {
                continue;
            }
            if (isTeamBusy(locked.getId(), dispatch.getPlanStart(), dispatch.getPlanEnd(), dispatch.getId())) {
                continue;
            }
            assign(dispatch, locked, matchSource, operator);
            return true;
        }
        return false;
    }

    /** 人工补匹配：把待匹配派单指定派给某队（资质、档期仍强制校验），可顺带给档期。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallOrder manualMatch(String dispatchNo, ManualMatchRequest request) {
        InstallOrder dispatch = requireByNo(dispatchNo);
        requireStatus(dispatch, InstallEnums.STATUS_PENDING, "补匹配");
        InstallTeam team = teamService.requireByNo(request.getTeamNo());
        String operator = Operators.resolve(request.getOperatorName());

        LocalDateTime start = request.getPlanStart() != null ? request.getPlanStart() : dispatch.getPlanStart();
        LocalDateTime end = request.getPlanEnd() != null ? request.getPlanEnd() : dispatch.getPlanEnd();
        validateWindow(start, end);
        LocalDate planDate = start.toLocalDate();
        if (!InstallEnums.TEAM_ACTIVE.equals(team.getStatus())) {
            throw new BusinessException("安装队 " + team.getTeamNo() + " 已停用，不能派单");
        }
        if (!teamService.isQualified(team, dispatch.getWorkType(), planDate)) {
            if (InstallEnums.isHighAltitude(dispatch.getWorkType())) {
                throw new BusinessException(teamService.highAltitudeBlockReason(team, planDate));
            }
            throw new BusinessException("安装队 " + team.getTeamNo() + " 资质不合格，不能派单");
        }
        InstallTeam locked = teamMapper.lockById(team.getId());
        if (isTeamBusy(locked.getId(), start, end, dispatch.getId())) {
            throw new BusinessException("安装队「" + team.getTeamName() + "」在 " + start.format(TS) + "~"
                    + end.format(TS) + " 已有别的点位排期，一个队同一时段只能排一个点位");
        }
        dispatch.setPlanStart(start);
        dispatch.setPlanEnd(end);
        assign(dispatch, locked, InstallEnums.MATCH_MANUAL, operator);
        return dispatch;
    }

    /** 选定队后落派单：状态 ASSIGNED、记队与档期快照、到场截止=计划开始。 */
    private void assign(InstallOrder dispatch, InstallTeam team, String matchSource, String operator) {
        boolean auto = InstallEnums.MATCH_AUTO.equals(matchSource);
        dispatch.setTeamId(team.getId());
        dispatch.setTeamNo(team.getTeamNo());
        dispatch.setTeamNameSnapshot(team.getTeamName());
        dispatch.setArrivalDeadline(dispatch.getPlanStart());
        dispatch.setAutoMatched(auto ? 1 : 0);
        dispatch.setStatus(InstallEnums.STATUS_ASSIGNED);
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        orderMapper.updateById(dispatch);
        mirrorPosting(dispatch, false);
        appendEvent(dispatch, InstallEnums.EVENT_MATCH, operator,
                (auto ? "自动匹配" : "人工补匹配") + "安装队「" + team.getTeamName() + "（" + team.getTeamNo()
                        + "）」，计划 " + dispatch.getPlanStart().format(TS) + "~"
                        + dispatch.getPlanEnd().format(TS)
                        + (InstallEnums.isHighAltitude(dispatch.getWorkType()) ? "（高空点位，已验高空作业证有效期）" : ""));
    }

    // ---- 改期（通知到队） ----

    /** 改期：只对已派单未到场的单；新档期仍过资质与档期校验，落改期通知到队。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallOrder reschedule(String dispatchNo, RescheduleRequest request) {
        InstallOrder dispatch = requireByNo(dispatchNo);
        requireStatus(dispatch, InstallEnums.STATUS_ASSIGNED, "改期");
        validateWindow(request.getNewStart(), request.getNewEnd());
        String operator = Operators.resolve(request.getOperatorName());
        LocalDate newPlanDate = request.getNewStart().toLocalDate();

        // 改期后仍要满足高空资质（证可能恰好到期）与队档期
        InstallTeam team = teamMapper.selectById(dispatch.getTeamId());
        if (!teamService.isQualified(team, dispatch.getWorkType(), newPlanDate)) {
            if (InstallEnums.isHighAltitude(dispatch.getWorkType())) {
                throw new BusinessException("改期失败：" + teamService.highAltitudeBlockReason(team, newPlanDate));
            }
        }
        InstallTeam locked = teamMapper.lockById(team.getId());
        if (isTeamBusy(locked.getId(), request.getNewStart(), request.getNewEnd(), dispatch.getId())) {
            throw new BusinessException("改期失败：安装队「" + team.getTeamName() + "」在新档期 "
                    + request.getNewStart().format(TS) + "~" + request.getNewEnd().format(TS)
                    + " 已排了别的点位，一个队同一时段只能排一个点位");
        }

        LocalDateTime oldStart = dispatch.getPlanStart();
        LocalDateTime oldEnd = dispatch.getPlanEnd();
        dispatch.setPlanStart(request.getNewStart());
        dispatch.setPlanEnd(request.getNewEnd());
        dispatch.setArrivalDeadline(request.getNewStart());
        dispatch.setRescheduleCount(dispatch.getRescheduleCount() + 1);
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        orderMapper.updateById(dispatch);
        mirrorPosting(dispatch, false);

        int seq = dispatch.getRescheduleCount();
        InstallNotice notice = new InstallNotice();
        notice.setDispatchId(dispatch.getId());
        notice.setDispatchNo(dispatch.getDispatchNo());
        notice.setTeamId(team.getId());
        notice.setTeamNo(team.getTeamNo());
        notice.setSeq(seq);
        notice.setOldStart(oldStart);
        notice.setOldEnd(oldEnd);
        notice.setNewStart(request.getNewStart());
        notice.setNewEnd(request.getNewEnd());
        notice.setReason(trimTo(request.getReason(), 255));
        notice.setOperatorName(operator);
        notice.setNoticeTime(LocalDateTime.now());
        noticeMapper.insert(notice);

        appendEvent(dispatch, InstallEnums.EVENT_RESCHEDULE, operator,
                "改期并通知安装队「" + team.getTeamName() + "」：原档期 " + oldStart.format(TS) + "~"
                        + oldEnd.format(TS) + " 调整为 " + request.getNewStart().format(TS) + "~"
                        + request.getNewEnd().format(TS)
                        + (request.getReason() != null && !request.getReason().trim().isEmpty()
                        ? "；原因：" + request.getReason().trim() : "")
                        + "（第 " + seq + " 次改期）");
        return dispatch;
    }

    // ---- 到场打卡 ----

    /** 师傅到场打卡：必须传现场照片；打不上照片不算到场。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallOrder arrive(String dispatchNo, ArriveRequest request, MultipartFile[] photos) {
        InstallOrder dispatch = requireByNo(dispatchNo);
        requireStatus(dispatch, InstallEnums.STATUS_ASSIGNED, "到场打卡");
        if (photos == null || nonEmpty(photos) == 0) {
            throw new BusinessException("到场打卡必须上传现场照片，没有照片不算到场");
        }
        String operator = Operators.resolve(request.getOperatorName());
        LocalDateTime now = request.getArriveTime() != null ? request.getArriveTime() : LocalDateTime.now();

        dispatch.setStatus(InstallEnums.STATUS_ARRIVED);
        dispatch.setArriveTime(now);
        dispatch.setArriveOperator(operator);
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        orderMapper.updateById(dispatch);
        mirrorPosting(dispatch, false);
        List<InstallPhoto> saved = savePhotos(dispatch, InstallEnums.PHASE_ARRIVAL, photos, operator);
        appendEvent(dispatch, InstallEnums.EVENT_ARRIVE, operator,
                "师傅到场拍照打卡，上传现场照片 " + saved.size() + " 张"
                        + (now.isAfter(dispatch.getArrivalDeadline())
                        ? "（晚于计划开始 " + dispatch.getArrivalDeadline().format(TS) + "）" : ""));
        return dispatch;
    }

    // ---- 完工回执 ----

    /** 完工回传回执：完工照片可传可不传；回执内容必填；按完工时刻对计划结束判准时。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallOrder complete(String dispatchNo, CompleteRequest request, MultipartFile[] photos) {
        InstallOrder dispatch = requireByNo(dispatchNo);
        requireStatus(dispatch, InstallEnums.STATUS_ARRIVED, "完工回传");
        if (request.getFinishResult() == null || request.getFinishResult().trim().isEmpty()) {
            throw new BusinessException("完工回传必须填写回执内容（安装结果/件料）");
        }
        String operator = Operators.resolve(request.getOperatorName());
        LocalDateTime now = request.getFinishTime() != null ? request.getFinishTime() : LocalDateTime.now();
        boolean onTime = !now.isAfter(dispatch.getPlanEnd());

        dispatch.setStatus(InstallEnums.STATUS_COMPLETED);
        dispatch.setFinishTime(now);
        dispatch.setFinishOperator(operator);
        dispatch.setFinishResult(trimTo(request.getFinishResult(), 1000));
        dispatch.setOnTime(onTime ? 1 : 0);
        dispatch.setVersionNo(dispatch.getVersionNo() + 1);
        dispatch.setOperatorName(operator);
        orderMapper.updateById(dispatch);

        InstallReceipt receipt = new InstallReceipt();
        receipt.setDispatchId(dispatch.getId());
        receipt.setDispatchNo(dispatch.getDispatchNo());
        receipt.setTeamId(dispatch.getTeamId());
        receipt.setReceiptContent(dispatch.getFinishResult());
        receipt.setFinishTime(now);
        receipt.setOnTime(onTime ? 1 : 0);
        receipt.setOperatorName(operator);
        try {
            receiptMapper.insert(receipt);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("派单 " + dispatchNo + " 已回传过完工回执，不能重复完工");
        }

        List<InstallPhoto> saved = savePhotos(dispatch, InstallEnums.PHASE_COMPLETION, photos, operator);
        // 上刊单随派单走：回写安装状态与完工时刻
        mirrorPosting(dispatch, true);
        appendEvent(dispatch, InstallEnums.EVENT_COMPLETE, operator,
                "完工回传回执，安装队「" + dispatch.getTeamNameSnapshot() + "」" + (onTime ? "准时" : "延误")
                        + "完工（计划结束 " + dispatch.getPlanEnd().format(TS) + "，完工 " + now.format(TS)
                        + "），完工照片 " + saved.size() + " 张；上刊单随状态置安装完工");
        return dispatch;
    }

    // ---- 翻查 ----

    public InstallOrder findByOrderId(Long orderId) {
        return orderMapper.selectOne(new LambdaQueryWrapper<InstallOrder>()
                .eq(InstallOrder::getOrderId, orderId));
    }

    public InstallOrder getByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw new BusinessException("上刊单号不能为空");
        }
        InstallOrder dispatch = orderMapper.selectOne(new LambdaQueryWrapper<InstallOrder>()
                .eq(InstallOrder::getOrderNo, orderNo.trim()));
        if (dispatch == null) {
            throw new BusinessException("上刊单 " + orderNo + " 还没有安装派单");
        }
        return dispatch;
    }

    public InstallOrder requireByNo(String dispatchNo) {
        if (dispatchNo == null || dispatchNo.trim().isEmpty()) {
            throw new BusinessException("安装派单号不能为空");
        }
        InstallOrder dispatch = orderMapper.selectOne(new LambdaQueryWrapper<InstallOrder>()
                .eq(InstallOrder::getDispatchNo, dispatchNo.trim()));
        if (dispatch == null) {
            throw new BusinessException("安装派单不存在: " + dispatchNo);
        }
        return dispatch;
    }

    /** 翻查：状态 / 安装队编号 / 点位编号 / 关键字（派单号或上刊单号）。 */
    public List<InstallOrder> list(String status, String teamNo, String siteNo, String keyword) {
        LambdaQueryWrapper<InstallOrder> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(InstallOrder::getStatus, status.trim().toUpperCase());
        }
        if (teamNo != null && !teamNo.trim().isEmpty()) {
            wrapper.eq(InstallOrder::getTeamNo, teamNo.trim());
        }
        if (siteNo != null && !siteNo.trim().isEmpty()) {
            wrapper.eq(InstallOrder::getSiteNo, siteNo.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(InstallOrder::getDispatchNo, kw)
                    .or().like(InstallOrder::getOrderNo, kw));
        }
        wrapper.orderByDesc(InstallOrder::getId);
        return orderMapper.selectList(wrapper);
    }

    /** 全量档案：派单 + 事件版本 + 照片 + 回执 + 改期通知。 */
    public InstallDetailView detail(String dispatchNo) {
        InstallOrder dispatch = requireByNo(dispatchNo);
        InstallDetailView view = new InstallDetailView();
        view.setOrder(dispatch);
        view.setEvents(eventMapper.selectList(new LambdaQueryWrapper<InstallEvent>()
                .eq(InstallEvent::getDispatchId, dispatch.getId())
                .orderByAsc(InstallEvent::getId)));
        view.setPhotos(photoMapper.selectList(new LambdaQueryWrapper<InstallPhoto>()
                .eq(InstallPhoto::getDispatchId, dispatch.getId())
                .orderByAsc(InstallPhoto::getId)));
        view.setReceipt(receiptMapper.selectOne(new LambdaQueryWrapper<InstallReceipt>()
                .eq(InstallReceipt::getDispatchId, dispatch.getId())));
        view.setNotices(noticeMapper.selectList(new LambdaQueryWrapper<InstallNotice>()
                .eq(InstallNotice::getDispatchId, dispatch.getId())
                .orderByAsc(InstallNotice::getSeq)));
        return view;
    }

    public List<InstallEvent> events(Long dispatchId) {
        return eventMapper.selectList(new LambdaQueryWrapper<InstallEvent>()
                .eq(InstallEvent::getDispatchId, dispatchId)
                .orderByAsc(InstallEvent::getId));
    }

    public InstallPhoto requirePhoto(Long photoId) {
        InstallPhoto photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException("安装照片不存在: id=" + photoId);
        }
        return photo;
    }

    public byte[] readPhotoBytes(InstallPhoto photo) {
        return storage.read(photo.getStoragePath());
    }

    // ---- 月末按队结算：完工量与准时率 ----

    /**
     * 月末按队出完工量与准时率（结算认这个数）。
     * 归属期间按完工时刻所在月固化；完工量=当月完工回执数，准时率=准时/完工量。
     */
    public List<TeamMonthStat> monthReport(String period) {
        YearMonth ym = parsePeriod(period);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();

        List<InstallOrder> completed = orderMapper.selectList(new LambdaQueryWrapper<InstallOrder>()
                .eq(InstallOrder::getStatus, InstallEnums.STATUS_COMPLETED)
                .ge(InstallOrder::getFinishTime, from)
                .lt(InstallOrder::getFinishTime, to));
        List<InstallOrder> openAssigned = orderMapper.selectList(new LambdaQueryWrapper<InstallOrder>()
                .in(InstallOrder::getStatus, InstallEnums.STATUS_ASSIGNED, InstallEnums.STATUS_ARRIVED));

        List<TeamMonthStat> stats = new ArrayList<>();
        for (InstallTeam team : teamService.activeTeams()) {
            TeamMonthStat stat = new TeamMonthStat();
            stat.setTeamNo(team.getTeamNo());
            stat.setTeamName(team.getTeamName());
            stat.setPeriod(ym.toString());
            long done = 0;
            long onTime = 0;
            for (InstallOrder d : completed) {
                if (team.getId().equals(d.getTeamId())) {
                    done++;
                    if (d.getOnTime() != null && d.getOnTime() == 1) {
                        onTime++;
                    }
                }
            }
            long open = openAssigned.stream().filter(d -> team.getId().equals(d.getTeamId())).count();
            stat.setCompletedCount(done);
            stat.setOnTimeCount(onTime);
            stat.setLateCount(done - onTime);
            stat.setAssignedOpenCount(open);
            stat.setOnTimeRate(done == 0 ? 0d : (double) onTime / done);
            stats.add(stat);
        }
        return stats;
    }

    /** 月末按队完工量/准时率导出 CSV（带 BOM，Excel 打开不乱码）。 */
    public String monthCsv(String period) {
        List<TeamMonthStat> rows = monthReport(period);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("归属期间,安装队编号,安装队名称,完工量,准时完工,延误完工,准时率,在途未完工\n");
        for (TeamMonthStat s : rows) {
            csv.append(esc(s.getPeriod())).append(',')
                    .append(esc(s.getTeamNo())).append(',')
                    .append(esc(s.getTeamName())).append(',')
                    .append(s.getCompletedCount()).append(',')
                    .append(s.getOnTimeCount()).append(',')
                    .append(s.getLateCount()).append(',')
                    .append(String.format("%.2f%%", s.getOnTimeRate() * 100)).append(',')
                    .append(s.getAssignedOpenCount()).append('\n');
        }
        return csv.toString();
    }

    // ---- 内部方法 ----

    /**
     * 队在 [start,end) 是否已有未取消派单：半开区间重叠 existing.start &lt; end 且 existing.end &gt; start。
     * 首尾相接（existing.end == start 或 existing.start == end）不算撞。
     */
    private boolean isTeamBusy(Long teamId, LocalDateTime start, LocalDateTime end, Long excludeDispatchId) {
        LambdaQueryWrapper<InstallOrder> w = new LambdaQueryWrapper<InstallOrder>()
                .eq(InstallOrder::getTeamId, teamId)
                .ne(InstallOrder::getStatus, InstallEnums.STATUS_CANCELLED)
                .lt(InstallOrder::getPlanStart, end)
                .gt(InstallOrder::getPlanEnd, start);
        if (excludeDispatchId != null) {
            w.ne(InstallOrder::getId, excludeDispatchId);
        }
        Long count = orderMapper.selectCount(w);
        return count != null && count > 0;
    }

    private LocalDateTime[] defaultWindow(LocalDate weekStart) {
        LocalDateTime start = weekStart.atTime(properties.getDefaultStartHour(), 0);
        LocalDateTime end = start.plusHours(properties.getDefaultJobHours());
        return new LocalDateTime[]{start, end};
    }

    private void validateWindow(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new BusinessException("计划作业时段开始与结束都不能为空");
        }
        if (!end.isAfter(start)) {
            throw new BusinessException("计划结束时刻必须晚于开始时刻（半开区间 [开始, 结束)）");
        }
    }

    private void mirrorPosting(InstallOrder dispatch, boolean finished) {
        PostingOrder patch = new PostingOrder();
        patch.setId(dispatch.getOrderId());
        patch.setInstallDispatchId(dispatch.getId());
        patch.setInstallStatus(dispatch.getStatus());
        if (finished) {
            patch.setInstallFinishTime(dispatch.getFinishTime());
        }
        postingOrderMapper.updateById(patch);
    }

    private List<InstallPhoto> savePhotos(InstallOrder dispatch, String phase, MultipartFile[] photos, String uploader) {
        List<InstallPhoto> saved = new ArrayList<>();
        if (photos == null) {
            return saved;
        }
        LocalDateTime batchTime = LocalDateTime.now();
        for (MultipartFile file : photos) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            byte[] data;
            try {
                data = file.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("读取上传照片失败: " + file.getOriginalFilename(), e);
            }
            String fingerprint = ImageFingerprints.sha256(data);
            String path = storage.store(dispatch.getDispatchNo(), phase, fingerprint,
                    file.getOriginalFilename(), batchTime, data);
            InstallPhoto photo = new InstallPhoto();
            photo.setDispatchId(dispatch.getId());
            photo.setPhase(phase);
            photo.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "photo.jpg");
            photo.setContentType(file.getContentType());
            photo.setFileSize((long) data.length);
            photo.setStoragePath(path);
            photo.setFingerprintSha256(fingerprint);
            photo.setUploaderName(uploader);
            photo.setUploadTime(batchTime);
            photoMapper.insert(photo);
            saved.add(photo);
        }
        return saved;
    }

    private int nonEmpty(MultipartFile[] photos) {
        int n = 0;
        for (MultipartFile f : photos) {
            if (f != null && !f.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private void appendEvent(InstallOrder dispatch, String eventType, String operator, String detail) {
        InstallEvent event = new InstallEvent();
        event.setDispatchId(dispatch.getId());
        event.setDispatchNo(dispatch.getDispatchNo());
        event.setEventType(eventType);
        event.setVersionNo(dispatch.getVersionNo());
        event.setOperatorName(operator);
        event.setDetail(detail);
        event.setSnapshot(snapshot(dispatch));
        event.setEventTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private String snapshot(InstallOrder dispatch) {
        try {
            return objectMapper.writeValueAsString(dispatch);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("安装派单快照序列化失败: " + dispatch.getDispatchNo(), e);
        }
    }

    private String generateNo() {
        long seq = orderMapper.nextDispatchSeq();
        return String.format("ID%010d", seq);
    }

    private void requireStatus(InstallOrder dispatch, String expected, String action) {
        if (InstallEnums.STATUS_CANCELLED.equals(dispatch.getStatus())) {
            throw new BusinessException("安装派单 " + dispatch.getDispatchNo() + " 已取消，不能再" + action);
        }
        if (InstallEnums.STATUS_COMPLETED.equals(dispatch.getStatus())) {
            throw new BusinessException("安装派单 " + dispatch.getDispatchNo() + " 已完工，不能再" + action);
        }
        if (!expected.equals(dispatch.getStatus())) {
            throw new BusinessException("安装派单 " + dispatch.getDispatchNo() + " 当前状态是「"
                    + InstallEnums.statusLabel(dispatch.getStatus()) + "」，不能" + action
                    + "（仅「" + InstallEnums.statusLabel(expected) + "」可操作）");
        }
    }

    private YearMonth parsePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new BusinessException("归属期间格式应为 yyyy-MM，例如 2026-10");
        }
        try {
            return YearMonth.parse(period);
        } catch (Exception e) {
            throw new BusinessException("归属期间格式应为 yyyy-MM，例如 2026-10");
        }
    }

    private String esc(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
