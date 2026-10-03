package com.evops.playlist.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.playlist.dto.ChangeApprovalRequest;
import com.evops.playlist.dto.PlaylistChangeRequest;
import com.evops.playlist.dto.PlaylistCreateRequest;
import com.evops.playlist.dto.PlaylistDetailView;
import com.evops.playlist.dto.PlaylistSaveRequest;
import com.evops.playlist.dto.SegmentCopyRequest;
import com.evops.playlist.dto.SegmentShiftRequest;
import com.evops.playlist.dto.WeekLockRequest;
import com.evops.playlist.dto.WeekScheduleView;
import com.evops.playlist.entity.Playlist;
import com.evops.playlist.entity.PlaylistAlert;
import com.evops.playlist.entity.PlaylistEvent;
import com.evops.playlist.entity.PlaylistVersion;
import com.evops.playlist.service.PlaylistOpsService;
import com.evops.playlist.service.PlaylistService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F7 屏体播放列表排片：
 * 建档 → 按日期时段编排（素材从库挑、跨零点按自然日切、同屏同时段唯一）→ 整段复制/顺延
 * → 周锁版 → 锁版后改走审批；到点自动切换、切换失败告警。
 */
@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {

    private final PlaylistService playlistService;
    private final PlaylistOpsService opsService;

    public PlaylistController(PlaylistService playlistService, PlaylistOpsService opsService) {
        this.playlistService = playlistService;
        this.opsService = opsService;
    }

    private static Viewer viewer() {
        Viewer v = Viewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    /** 给一块屏建播放列表（一屏一条），带首个编排周。 */
    @PostMapping
    public ApiResponse<Playlist> create(@Valid @RequestBody PlaylistCreateRequest request) {
        return ApiResponse.ok(playlistService.create(request));
    }

    /** 播放列表翻查：可按屏编号、状态过滤。 */
    @GetMapping
    public ApiResponse<List<Playlist>> list(@RequestParam(required = false) String siteNo,
                                            @RequestParam(required = false) String status) {
        return ApiResponse.ok(playlistService.list(siteNo, status));
    }

    /** 全量档案：主档 + 版本链 + 周锁版 + 事件 + 告警。 */
    @GetMapping("/{playlistNo}")
    public ApiResponse<PlaylistDetailView> detail(@PathVariable String playlistNo) {
        return ApiResponse.ok(playlistService.detail(playlistNo));
    }

    /** 版本链翻查（新版本号在前）。 */
    @GetMapping("/{playlistNo}/versions")
    public ApiResponse<List<PlaylistVersion>> versions(@PathVariable String playlistNo) {
        return ApiResponse.ok(playlistService.listVersions(playlistNo));
    }

    /** 看某周编排（已锁版取锁版版本，否则取最新草稿；时段按自然日切好）。 */
    @GetMapping("/{playlistNo}/schedule")
    public ApiResponse<WeekScheduleView> schedule(@PathVariable String playlistNo,
                                                  @RequestParam(required = false) LocalDate weekStart) {
        return ApiResponse.ok(playlistService.weekView(playlistNo, weekStart));
    }

    /** 整表保存某周编排（替换式，每次保存另起草稿版本）；该周已锁版拒绝。 */
    @PutMapping("/{playlistNo}/schedule")
    public ApiResponse<PlaylistVersion> save(@PathVariable String playlistNo,
                                             @Valid @RequestBody PlaylistSaveRequest request) {
        return ApiResponse.ok(playlistService.saveDraft(playlistNo, request));
    }

    /** 整段复制：来源周整段复制到目标周（默认下周），生成目标周新草稿。 */
    @PostMapping("/{playlistNo}/segments/copy")
    public ApiResponse<PlaylistVersion> copy(@PathVariable String playlistNo,
                                             @Valid @RequestBody SegmentCopyRequest request) {
        return ApiResponse.ok(playlistService.copySegment(playlistNo, request));
    }

    /** 整段顺延：某周全部时段整体平移分钟（正延后、负提前）。 */
    @PostMapping("/{playlistNo}/segments/shift")
    public ApiResponse<PlaylistVersion> shift(@PathVariable String playlistNo,
                                              @Valid @RequestBody SegmentShiftRequest request) {
        return ApiResponse.ok(playlistService.shiftSegment(playlistNo, request));
    }

    /** 人工锁版某周：锁后该周改动只能走改单审批。 */
    @PostMapping("/{playlistNo}/lock")
    public ApiResponse<PlaylistVersion> lock(@PathVariable String playlistNo,
                                             @RequestBody(required = false) WeekLockRequest request) {
        return ApiResponse.ok(playlistService.lockWeek(playlistNo,
                request == null ? new WeekLockRequest() : request, null, null));
    }

    /** 锁版后改单申请（PENDING 待审批），旧锁版继续生效。 */
    @PostMapping("/{playlistNo}/change-requests")
    public ApiResponse<PlaylistVersion> requestChange(@PathVariable String playlistNo,
                                                      @Valid @RequestBody PlaylistChangeRequest request) {
        return ApiResponse.ok(playlistService.requestChange(playlistNo, request, viewer()));
    }

    /** 待审批改单翻查（可按屏过滤）。 */
    @GetMapping("/change-requests/pending")
    public ApiResponse<List<PlaylistVersion>> pendingChanges(
            @RequestParam(required = false) String playlistNo) {
        return ApiResponse.ok(playlistService.listPendingChanges(playlistNo));
    }

    /** 审批通过锁版改单（财务/系统，申请人不能审批自己）。 */
    @PostMapping("/{playlistNo}/change-requests/{versionId}/approve")
    public ApiResponse<PlaylistVersion> approve(@PathVariable String playlistNo,
                                                @PathVariable Long versionId,
                                                @RequestBody(required = false) ChangeApprovalRequest request) {
        return ApiResponse.ok(playlistService.approveChange(playlistNo, versionId,
                request == null ? new ChangeApprovalRequest() : request, viewer()));
    }

    /** 驳回锁版改单（原锁版继续生效）。 */
    @PostMapping("/{playlistNo}/change-requests/{versionId}/reject")
    public ApiResponse<PlaylistVersion> reject(@PathVariable String playlistNo,
                                               @PathVariable Long versionId,
                                               @RequestBody(required = false) ChangeApprovalRequest request) {
        return ApiResponse.ok(playlistService.rejectChange(playlistNo, versionId,
                request == null ? new ChangeApprovalRequest() : request, viewer()));
    }

    /** 编排/锁版/切换事件留底。 */
    @GetMapping("/{playlistNo}/events")
    public ApiResponse<List<PlaylistEvent>> events(@PathVariable String playlistNo) {
        return ApiResponse.ok(playlistService.listEvents(playlistNo));
    }

    /** 排片告警翻查（切换失败/空档/素材缺失/未锁版…），可按状态过滤。 */
    @GetMapping("/alerts")
    public ApiResponse<List<PlaylistAlert>> alerts(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String playlistNo) {
        return ApiResponse.ok(playlistService.listAlerts(status, playlistNo));
    }

    /** 人工确认解除告警。 */
    @PostMapping("/alerts/{alertId}/resolve")
    public ApiResponse<PlaylistAlert> resolveAlert(@PathVariable Long alertId,
                                                   @RequestParam(required = false) String operatorName,
                                                   @RequestParam(required = false) String comment) {
        return ApiResponse.ok(playlistService.resolveAlert(alertId, operatorName, comment));
    }

    /** 手工触发一轮到点切换扫描（演示/补扫；定时任务默认每 30 秒自动跑）。 */
    @PostMapping("/switch/scan")
    public ApiResponse<Map<String, Integer>> scanSwitch() {
        int switched = opsService.switchDue();
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("switched", switched);
        return ApiResponse.ok(result);
    }

    /** 手工触发一轮周五自动锁版扫描（仅在周五下班时刻后生效）。 */
    @PostMapping("/locks/scan")
    public ApiResponse<Map<String, Integer>> scanAutoLock() {
        int locked = opsService.autoLockNextWeek();
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("locked", locked);
        return ApiResponse.ok(result);
    }
}
