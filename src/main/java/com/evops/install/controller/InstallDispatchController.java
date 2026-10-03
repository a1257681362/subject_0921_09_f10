package com.evops.install.controller;

import com.evops.common.ApiResponse;
import com.evops.install.dto.ArriveRequest;
import com.evops.install.dto.CompleteRequest;
import com.evops.install.dto.InstallDetailView;
import com.evops.install.dto.ManualMatchRequest;
import com.evops.install.dto.RescheduleRequest;
import com.evops.install.dto.TeamMonthStat;
import com.evops.install.entity.InstallOrder;
import com.evops.install.entity.InstallPhoto;
import com.evops.install.service.InstallDispatchService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F8 安装派单：上刊确认自动派单（按资质+档期匹配）→ 人工补匹配/改期 → 到场拍照打卡
 * → 完工回传回执；月末按队出完工量与准时率。
 */
@RestController
@RequestMapping("/api/install")
public class InstallDispatchController {

    private final InstallDispatchService dispatchService;

    public InstallDispatchController(InstallDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /** 翻查派单：?status=&teamNo=&siteNo=&keyword=（keyword 匹配派单号/上刊单号）。 */
    @GetMapping("/orders")
    public ApiResponse<List<InstallOrder>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String teamNo,
                                                @RequestParam(required = false) String siteNo,
                                                @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(dispatchService.list(status, teamNo, siteNo, keyword));
    }

    /** 派单全量档案：单 + 事件版本 + 照片 + 回执 + 改期通知。 */
    @GetMapping("/orders/{dispatchNo}")
    public ApiResponse<InstallDetailView> detail(@PathVariable String dispatchNo) {
        return ApiResponse.ok(dispatchService.detail(dispatchNo));
    }

    /** 按上刊单号查它的安装派单（上刊单侧翻状态用）。 */
    @GetMapping("/by-order/{orderNo}")
    public ApiResponse<InstallOrder> byOrder(@PathVariable String orderNo) {
        return ApiResponse.ok(dispatchService.getByOrderNo(orderNo));
    }

    /** 人工补匹配：把待匹配派单指定派给某安装队（资质、档期仍强制校验）。 */
    @PostMapping("/orders/{dispatchNo}/match")
    public ApiResponse<InstallOrder> manualMatch(@PathVariable String dispatchNo,
                                                 @Valid @RequestBody ManualMatchRequest request) {
        return ApiResponse.ok(dispatchService.manualMatch(dispatchNo, request));
    }

    /** 改期：新档期+原因，通知到队（资质、档期在新档期上重新校验）。 */
    @PostMapping("/orders/{dispatchNo}/reschedule")
    public ApiResponse<InstallOrder> reschedule(@PathVariable String dispatchNo,
                                                @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.ok(dispatchService.reschedule(dispatchNo, request));
    }

    /** 师傅到场打卡：现场照片必传。 */
    @PostMapping(value = "/orders/{dispatchNo}/arrive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<InstallOrder> arrive(@PathVariable String dispatchNo,
                                            @RequestParam(value = "arriveTime", required = false)
                                            java.time.LocalDateTime arriveTime,
                                            @RequestParam(value = "operatorName", required = false) String operatorName,
                                            @RequestParam("photos") MultipartFile[] photos) {
        ArriveRequest request = new ArriveRequest();
        request.setArriveTime(arriveTime);
        request.setOperatorName(operatorName);
        return ApiResponse.ok(dispatchService.arrive(dispatchNo, request, photos));
    }

    /** 完工回传回执：回执内容必填，完工照片可传可不传。 */
    @PostMapping(value = "/orders/{dispatchNo}/complete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<InstallOrder> complete(@PathVariable String dispatchNo,
                                              @RequestParam("finishResult") String finishResult,
                                              @RequestParam(value = "finishTime", required = false)
                                              java.time.LocalDateTime finishTime,
                                              @RequestParam(value = "operatorName", required = false) String operatorName,
                                              @RequestParam(value = "photos", required = false) MultipartFile[] photos) {
        CompleteRequest request = new CompleteRequest();
        request.setFinishResult(finishResult);
        request.setFinishTime(finishTime);
        request.setOperatorName(operatorName);
        return ApiResponse.ok(dispatchService.complete(dispatchNo, request, photos));
    }

    /** 下载到场/完工照片。 */
    @GetMapping("/photos/{photoId}/download")
    public ResponseEntity<ByteArrayResource> downloadPhoto(@PathVariable Long photoId) {
        InstallPhoto photo = dispatchService.requirePhoto(photoId);
        byte[] data = dispatchService.readPhotoBytes(photo);
        MediaType mediaType = photo.getContentType() != null
                ? MediaType.parseMediaType(photo.getContentType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(photo.getFileName()))
                .contentType(mediaType)
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    /** 手工触发一轮待匹配补扫（演示/补扫用；定时任务默认每 10 分钟自动跑）。 */
    @PostMapping("/match/scan")
    public ApiResponse<Map<String, Integer>> scanMatch() {
        int count = dispatchService.autoMatchPending();
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("matched", count);
        return ApiResponse.ok(result);
    }

    /** 月末按队完工量与准时率（结算认这个数），period=yyyy-MM（按完工时刻所在月）。 */
    @GetMapping("/reports/team-month")
    public ApiResponse<List<TeamMonthStat>> teamMonthReport(@RequestParam String period) {
        return ApiResponse.ok(dispatchService.monthReport(period));
    }

    /** 月末按队完工量/准时率导出 CSV（BOM，Excel 打开不乱码）。 */
    @GetMapping("/reports/team-month/export")
    public ResponseEntity<byte[]> exportTeamMonth(@RequestParam String period) {
        byte[] csv = dispatchService.monthCsv(period).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"install-team-month-" + period + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    private String contentDisposition(String filename) {
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不受支持", e);
        }
        return "attachment; filename=\"" + filename.replaceAll("[\"\\\\]", "_") + "\"; filename*=UTF-8''" + encoded;
    }
}
