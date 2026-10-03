package com.evops.ledger.controller;

import com.evops.common.ApiResponse;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.SiteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 点位档案：挂牌、翻查、详情。 */
@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    /** 挂牌建档（编号重复存不进去）。 */
    @PostMapping
    public ApiResponse<Site> register(@Valid @RequestBody SiteCreateRequest request) {
        return ApiResponse.ok(siteService.register(request));
    }

    /** 翻查：?siteNo=&businessDistrict=&orientation= */
    @GetMapping
    public ApiResponse<List<Site>> search(@RequestParam(required = false) String siteNo,
                                          @RequestParam(required = false) String businessDistrict,
                                          @RequestParam(required = false) String orientation) {
        return ApiResponse.ok(siteService.search(siteNo, businessDistrict, orientation));
    }

    @GetMapping("/{id}")
    public ApiResponse<Site> detail(@PathVariable Long id) {
        return ApiResponse.ok(siteService.getById(id));
    }
}
