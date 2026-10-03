package com.evops.ledger.controller;

import com.evops.common.ApiResponse;
import com.evops.ledger.dto.DistrictCircleRequest;
import com.evops.ledger.entity.DistrictCircle;
import com.evops.ledger.service.DistrictCircleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 商圈圈界：圈定、翻查、重圈。 */
@RestController
@RequestMapping("/api/districts")
public class DistrictCircleController {

    private final DistrictCircleService districtCircleService;

    public DistrictCircleController(DistrictCircleService districtCircleService) {
        this.districtCircleService = districtCircleService;
    }

    /** 圈定商圈：中心坐标+半径（公里），同名不能重复圈。 */
    @PostMapping
    public ApiResponse<DistrictCircle> create(@Valid @RequestBody DistrictCircleRequest request) {
        return ApiResponse.ok(districtCircleService.create(request));
    }

    /** 重圈：同名商圈圈界改写（当前态覆盖，点位归属不动）。 */
    @PutMapping("/{districtName}")
    public ApiResponse<DistrictCircle> redraw(@PathVariable String districtName,
                                              @Valid @RequestBody DistrictCircleRequest request) {
        return ApiResponse.ok(districtCircleService.redraw(districtName, request));
    }

    /** 翻查全部商圈圈界。 */
    @GetMapping
    public ApiResponse<List<DistrictCircle>> list() {
        return ApiResponse.ok(districtCircleService.list());
    }
}
