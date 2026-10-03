package com.evops.ledger.controller;

import com.evops.common.ApiResponse;
import com.evops.ledger.dto.GeoArchiveRequest;
import com.evops.ledger.dto.GeoSearchHit;
import com.evops.ledger.entity.SiteGeoVersion;
import com.evops.ledger.service.SiteGeoService;
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

import javax.validation.Valid;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 经纬度建档与半径捞点：坐标建档、版本翻查、按半径检索、整页导出。 */
@RestController
@RequestMapping("/api/sites")
public class SiteGeoController {

    private final SiteGeoService siteGeoService;

    public SiteGeoController(SiteGeoService siteGeoService) {
        this.siteGeoService = siteGeoService;
    }

    /** 经纬度建档：首建记 v1，纠偏重录另起新版本（旧版不抹）。 */
    @PostMapping("/{siteNo}/geo")
    public ApiResponse<SiteGeoVersion> archive(@PathVariable String siteNo,
                                               @Valid @RequestBody GeoArchiveRequest request) {
        return ApiResponse.ok(siteGeoService.archive(siteNo, request));
    }

    /** 建档版本翻查：最新版在前。 */
    @GetMapping("/{siteNo}/geo/versions")
    public ApiResponse<List<SiteGeoVersion>> versions(@PathVariable String siteNo) {
        return ApiResponse.ok(siteGeoService.versions(siteNo));
    }

    /** 半径捞点：?lat=&lng=&radiusKm=，圈里在营点位按距离升序，重叠带标出。 */
    @GetMapping("/geo/search")
    public ApiResponse<List<GeoSearchHit>> search(@RequestParam BigDecimal lat,
                                                  @RequestParam BigDecimal lng,
                                                  @RequestParam BigDecimal radiusKm) {
        return ApiResponse.ok(siteGeoService.search(lat, lng, radiusKm));
    }

    /** 整页导出：与检索同口径的完整结果集，CSV 直接下载（含 BOM，Excel 打开不乱码）。 */
    @GetMapping("/geo/search/export")
    public ResponseEntity<byte[]> export(@RequestParam BigDecimal lat,
                                         @RequestParam BigDecimal lng,
                                         @RequestParam BigDecimal radiusKm) {
        byte[] csv = siteGeoService.exportCsv(lat, lng, radiusKm).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"site-radius-search.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
