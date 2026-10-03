package com.evops.rectify.controller;

import com.evops.common.ApiResponse;
import com.evops.rectify.RectifyEnums;
import com.evops.rectify.dto.RectifyDetailView;
import com.evops.rectify.dto.RectifyDispatchRequest;
import com.evops.rectify.dto.RectifyReinspectRequest;
import com.evops.rectify.dto.RectifyRepairRequest;
import com.evops.rectify.dto.RectifyReportRequest;
import com.evops.rectify.entity.RectifyOrder;
import com.evops.rectify.entity.RectifyPhoto;
import com.evops.rectify.service.RectifyService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F6 异常上报整改：现场上报（照片可选）→ 派单 → 维护商回传 → 复勘销项/驳回重处理；
 * 超期自动抄送；留证照片关闭前不许删；点位历史异常可翻。
 */
@RestController
@RequestMapping("/api/rectify")
public class RectifyController {

    private final RectifyService rectifyService;

    public RectifyController(RectifyService rectifyService) {
        this.rectifyService = rectifyService;
    }

    /** 异常类型字典（牌面翘边…），供巡查端下拉。 */
    @GetMapping("/types")
    public ApiResponse<List<Map<String, String>>> types() {
        List<Map<String, String>> types = new ArrayList<>();
        for (String code : Arrays.asList(RectifyEnums.TYPE_PANEL_LIFT, RectifyEnums.TYPE_PANEL_DAMAGE,
                RectifyEnums.TYPE_LIGHT_FAULT, RectifyEnums.TYPE_STRUCTURE_RISK, RectifyEnums.TYPE_OTHER)) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("label", RectifyEnums.typeLabel(code));
            types.add(item);
        }
        return ApiResponse.ok(types);
    }

    /** 巡查员现场上报异常：点位、类型必填，photos 可传可不传。 */
    @PostMapping(value = "/orders", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RectifyOrder> report(@Valid @ModelAttribute RectifyReportRequest request,
                                            @RequestParam(value = "photos", required = false) MultipartFile[] photos) {
        return ApiResponse.ok(rectifyService.report(request, photos));
    }

    /** 翻查整改单。 */
    @GetMapping("/orders")
    public ApiResponse<List<RectifyOrder>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String siteNo,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(rectifyService.list(status, siteNo, type, keyword));
    }

    /** 单全量档案：单据 + 事件版本 + 照片 + 抄送记录。 */
    @GetMapping("/orders/{rectifyNo}")
    public ApiResponse<RectifyDetailView> detail(@PathVariable String rectifyNo) {
        return ApiResponse.ok(rectifyService.detail(rectifyNo));
    }

    /** 系统派单给维护商，给整改期限。 */
    @PostMapping("/orders/{rectifyNo}/dispatch")
    public ApiResponse<RectifyOrder> dispatch(@PathVariable String rectifyNo,
                                              @Valid @RequestBody RectifyDispatchRequest request) {
        return ApiResponse.ok(rectifyService.dispatch(rectifyNo, request));
    }

    /** 维护商处理完回传：处理结果必填，photos（整改后照片）可传可不传。 */
    @PostMapping(value = "/orders/{rectifyNo}/repair", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RectifyOrder> repair(@PathVariable String rectifyNo,
                                            @RequestParam("repairResult") String repairResult,
                                            @RequestParam(value = "operatorName", required = false) String operatorName,
                                            @RequestParam(value = "photos", required = false) MultipartFile[] photos) {
        RectifyRepairRequest request = new RectifyRepairRequest();
        request.setRepairResult(repairResult);
        request.setOperatorName(operatorName);
        return ApiResponse.ok(rectifyService.repair(rectifyNo, request, photos));
    }

    /** 复勘通过：销项关闭。 */
    @PostMapping("/orders/{rectifyNo}/reinspect/pass")
    public ApiResponse<RectifyOrder> reinspectPass(@PathVariable String rectifyNo,
                                                   @RequestBody(required = false) RectifyReinspectRequest request) {
        return ApiResponse.ok(rectifyService.reinspectPass(rectifyNo, request == null ? new RectifyReinspectRequest() : request));
    }

    /** 复勘不通过：退回维护商重处理（意见必填）。 */
    @PostMapping("/orders/{rectifyNo}/reinspect/reject")
    public ApiResponse<RectifyOrder> reinspectReject(@PathVariable String rectifyNo,
                                                     @Valid @RequestBody RectifyReinspectRequest request) {
        return ApiResponse.ok(rectifyService.reinspectReject(rectifyNo, request));
    }

    /** 单子未关闭时补传留证照片（现场补拍等）。 */
    @PostMapping(value = "/orders/{rectifyNo}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<RectifyPhoto>> addPhotos(@PathVariable String rectifyNo,
                                                     @RequestParam(defaultValue = "REPORT") String phase,
                                                     @RequestParam(value = "operatorName", required = false) String operatorName,
                                                     @RequestParam("photos") MultipartFile[] photos) {
        return ApiResponse.ok(rectifyService.addPhotos(rectifyNo, phase, photos, operatorName));
    }

    /** 销项关闭后才允许删照片，且删一条留一条事件；关闭前调用直接被拒。 */
    @PostMapping("/photos/{photoId}/delete")
    public ApiResponse<Void> deletePhoto(@PathVariable Long photoId,
                                         @RequestParam(required = false) String operatorName) {
        rectifyService.deletePhoto(photoId, operatorName);
        return ApiResponse.ok(null);
    }

    /** 下载留证照片。 */
    @GetMapping("/photos/{photoId}/download")
    public ResponseEntity<ByteArrayResource> downloadPhoto(@PathVariable Long photoId) {
        RectifyPhoto photo = rectifyService.requirePhoto(photoId);
        byte[] data = rectifyService.readPhotoBytes(photo);
        MediaType mediaType = photo.getContentType() != null
                ? MediaType.parseMediaType(photo.getContentType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(photo.getFileName()))
                .contentType(mediaType)
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    /** 翻点位档案：该点位的历史异常（含已销项），时间倒序。 */
    @GetMapping("/sites/{siteNo}/history")
    public ApiResponse<List<RectifyOrder>> siteHistory(@PathVariable String siteNo) {
        return ApiResponse.ok(rectifyService.listBySite(siteNo));
    }

    /** 手工触发一轮超期抄送扫描（演示/补扫用；定时任务默认每 10 分钟自动跑）。 */
    @PostMapping("/escalations/scan")
    public ApiResponse<Map<String, Integer>> scanEscalations() {
        int count = rectifyService.escalateDue();
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("escalated", count);
        return ApiResponse.ok(result);
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
