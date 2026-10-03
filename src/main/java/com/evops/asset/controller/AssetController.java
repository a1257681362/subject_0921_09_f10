package com.evops.asset.controller;

import com.evops.asset.dto.AssetDetailView;
import com.evops.asset.dto.SpecsView;
import com.evops.asset.dto.UploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.service.AssetService;
import com.evops.asset.service.TranscodeService;
import com.evops.asset.support.AssetProperties;
import com.evops.common.ApiResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 素材流水与多规格转档：上传建档（指纹去重）、翻查、档案详情、产物下载、状态轮询、重新转档。 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;
    private final TranscodeService transcodeService;
    private final AssetProperties properties;

    public AssetController(AssetService assetService, TranscodeService transcodeService,
                           AssetProperties properties) {
        this.assetService = assetService;
        this.transcodeService = transcodeService;
        this.properties = properties;
    }

    /** 原图上传：同指纹不另开档；新图自动出横竖两版常规规格。 */
    @PostMapping("/upload")
    public ApiResponse<UploadResult> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "operatorName", required = false) String operatorName,
                                            @RequestParam(value = "remark", required = false) String remark) {
        return ApiResponse.ok(assetService.upload(file, operatorName, remark));
    }

    /** 翻查：keyword 同时匹配文件名 / 素材编号。 */
    @GetMapping
    public ApiResponse<List<Asset>> search(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String status) {
        return ApiResponse.ok(assetService.search(keyword, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Asset> detail(@PathVariable Long id) {
        return ApiResponse.ok(assetService.requireById(id));
    }

    /** 档案全量：素材档案 + 转档版本（各带多规格产物）+ 转档任务状态。 */
    @GetMapping("/{id}/detail")
    public ApiResponse<AssetDetailView> fullDetail(@PathVariable Long id) {
        Asset asset = assetService.requireById(id);
        AssetDetailView view = new AssetDetailView();
        view.setAsset(asset);
        List<AssetDetailView.VersionView> versionViews = new ArrayList<>();
        assetService.listVersions(id).forEach(v -> {
            AssetDetailView.VersionView vv = new AssetDetailView.VersionView();
            vv.setVersion(v);
            vv.setFiles(assetService.listFiles(v.getId()));
            versionViews.add(vv);
        });
        view.setVersions(versionViews);
        view.setJobs(assetService.listJobs(id));
        return ApiResponse.ok(view);
    }

    /** 当前生效的常规规格（用途 / 比例 / 长边 / 体积上限），供前端标清用途。 */
    @GetMapping("/specs")
    public ApiResponse<SpecsView> specs() {
        SpecsView view = new SpecsView();
        List<SpecsView.SpecItem> items = new ArrayList<>();
        for (AssetProperties.Spec spec : properties.getSpecs()) {
            SpecsView.SpecItem item = new SpecsView.SpecItem();
            item.setName(spec.getName());
            item.setUsage(spec.getUsage());
            item.setRatio(spec.getRatio());
            item.setLongEdge(spec.getLongEdge());
            items.add(item);
        }
        view.setSpecs(items);
        view.setMaxFileSizeBytes(properties.getMaxFileSize().toBytes());
        view.setMaxFileSizeKb(properties.getMaxFileSize().toKilobytes());
        view.setQualityInitial(properties.getQuality().getInitial());
        view.setQualityFloor(properties.getQuality().getFloor());
        view.setQualityDecrement(properties.getQuality().getDecrement());
        return ApiResponse.ok(view);
    }

    /** 转档任务状态轮询：PENDING / PROCESSING / SUCCESS / FAILED，FAILED 带原因。 */
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<TranscodeJob> jobStatus(@PathVariable Long jobId) {
        return ApiResponse.ok(transcodeService.requireJob(jobId));
    }

    /** 失败或想重跑：不抹历史，另起一版重新转档。 */
    @PostMapping("/{id}/retry")
    public ApiResponse<TranscodeJob> retry(@PathVariable Long id,
                                           @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(assetService.retry(id, operatorName));
    }

    /** 下载某个多规格产物。 */
    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable Long fileId) {
        AssetFile file = assetService.requireProductFile(fileId);
        byte[] data = assetService.readProduct(file);
        String downloadName = file.getUsage() + "-" + file.getWidth() + "x" + file.getHeight() + ".jpg";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(downloadName))
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    /** 下载原图。 */
    @GetMapping("/{id}/original")
    public ResponseEntity<ByteArrayResource> downloadOriginal(@PathVariable Long id) {
        Asset asset = assetService.requireById(id);
        byte[] data = assetService.readOriginal(asset);
        String ext = "jpg";
        String path = asset.getStoragePath();
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            ext = path.substring(dot + 1);
        }
        String downloadName = asset.getAssetNo() + "-original." + ext;
        MediaType mediaType = asset.getContentType() != null
                ? MediaType.parseMediaType(asset.getContentType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(downloadName))
                .contentType(mediaType)
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
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
