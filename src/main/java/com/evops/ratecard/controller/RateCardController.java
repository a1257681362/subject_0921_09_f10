package com.evops.ratecard.controller;

import com.evops.common.ApiResponse;
import com.evops.ratecard.dto.RateCardCreateRequest;
import com.evops.ratecard.dto.VersionActionRequest;
import com.evops.ratecard.dto.VersionConfigureRequest;
import com.evops.ratecard.dto.VersionDetailView;
import com.evops.ratecard.entity.RateCard;
import com.evops.ratecard.entity.RateCardVersion;
import com.evops.ratecard.service.RateCardService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 年度刊例配置：年度刊例、刊例版本、阶梯档位与点位刊例价，以及财务随查随拉的阶梯对照表。 */
@RestController
@RequestMapping("/api/rate-cards")
public class RateCardController {

    private final RateCardService rateCardService;

    public RateCardController(RateCardService rateCardService) {
        this.rateCardService = rateCardService;
    }

    /** 建年度刊例（同年只能一份，自动带 v1 草稿）。 */
    @PostMapping
    public ApiResponse<RateCard> create(@Valid @RequestBody RateCardCreateRequest request) {
        return ApiResponse.ok(rateCardService.createCard(request));
    }

    /** 年度刊例翻查，?year= 可按年过滤。 */
    @GetMapping
    public ApiResponse<List<RateCard>> list(@RequestParam(required = false) Integer year) {
        return ApiResponse.ok(rateCardService.listCards(year));
    }

    @GetMapping("/{cardId}")
    public ApiResponse<RateCard> card(@PathVariable Long cardId) {
        return ApiResponse.ok(rateCardService.requireCard(cardId));
    }

    /** 版本翻查：新版本号在前。 */
    @GetMapping("/{cardId}/versions")
    public ApiResponse<List<RateCardVersion>> versions(@PathVariable Long cardId) {
        return ApiResponse.ok(rateCardService.listVersions(cardId));
    }

    /** 另起新版本（从上一版拷贝档位与点位价做起点，拿到可改草稿）。 */
    @PostMapping("/{cardId}/versions")
    public ApiResponse<RateCardVersion> newVersion(@PathVariable Long cardId,
                                                   @RequestBody(required = false) VersionActionRequest request) {
        return ApiResponse.ok(rateCardService.newVersion(cardId, request == null ? new VersionActionRequest() : request));
    }

    /** 年度封存：封存后不可起新版、不可报价。 */
    @PostMapping("/{cardId}/close")
    public ApiResponse<RateCard> close(@PathVariable Long cardId,
                                       @RequestBody(required = false) VersionActionRequest request) {
        return ApiResponse.ok(rateCardService.closeCard(cardId, request == null ? new VersionActionRequest() : request));
    }

    /** 草稿版本整表配置：档位比例 + 点位刊例价（只准动草稿，生效版冻结）。 */
    @PutMapping("/versions/{versionId}")
    public ApiResponse<RateCardVersion> configure(@PathVariable Long versionId,
                                                   @Valid @RequestBody VersionConfigureRequest request) {
        return ApiResponse.ok(rateCardService.configure(versionId, request));
    }

    /** 版本详情：版本头 + 阶梯档位 + 点位刊例价。 */
    @GetMapping("/versions/{versionId}")
    public ApiResponse<VersionDetailView> versionDetail(@PathVariable Long versionId) {
        return ApiResponse.ok(rateCardService.versionDetail(versionId));
    }

    /** 发布草稿：旧生效版置 SUPERSEDED，本版挂为年度当前版本。 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<RateCardVersion> publish(@PathVariable Long versionId,
                                                @RequestBody(required = false) VersionActionRequest request) {
        return ApiResponse.ok(rateCardService.publish(versionId, request == null ? new VersionActionRequest() : request));
    }

    /** 作废生效版本（留底不删，作废后该年度重新发布版本才能报价）。 */
    @PostMapping("/versions/{versionId}/revoke")
    public ApiResponse<RateCardVersion> revoke(@PathVariable Long versionId,
                                               @RequestBody(required = false) VersionActionRequest request) {
        return ApiResponse.ok(rateCardService.revoke(versionId, request == null ? new VersionActionRequest() : request));
    }

    /** 财务随查随拉：某年当前版本的阶梯对照详情。 */
    @GetMapping("/years/{year}")
    public ApiResponse<VersionDetailView> yearDetail(@PathVariable int year) {
        return ApiResponse.ok(rateCardService.yearDetail(year));
    }

    /** 阶梯对照表导出 CSV（含 BOM，Excel 打开不乱码）。 */
    @GetMapping("/years/{year}/tiers/export")
    public ResponseEntity<byte[]> exportTiers(@PathVariable int year) {
        byte[] csv = rateCardService.tierTableCsv(year).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"rate-card-" + year + "-tiers.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
