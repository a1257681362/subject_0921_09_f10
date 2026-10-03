package com.evops.compliance.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.compliance.dto.CategoryCreateRequest;
import com.evops.compliance.dto.CategoryModifyRequest;
import com.evops.compliance.dto.CategoryRemarkRequest;
import com.evops.compliance.dto.CategoryRestrictRequest;
import com.evops.compliance.dto.CheckOrderRequest;
import com.evops.compliance.dto.FreezeRequest;
import com.evops.compliance.dto.FreezePreviewView;
import com.evops.compliance.entity.IndustryCategory;
import com.evops.compliance.entity.IndustryCategoryOpLog;
import com.evops.compliance.entity.IndustryCategoryVersion;
import com.evops.compliance.service.ComplianceFreezeService;
import com.evops.compliance.service.IndustryCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F10 受限行业类目名单与在途单冻结。
 * GET 类接口所有登录账号可用（建档下拉、页面预检）；维护与冻结类接口服务层强制合规岗视角。
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final IndustryCategoryService categoryService;
    private final ComplianceFreezeService freezeService;

    public ComplianceController(IndustryCategoryService categoryService,
                                ComplianceFreezeService freezeService) {
        this.categoryService = categoryService;
        this.freezeService = freezeService;
    }

    private static Viewer viewer() {
        Viewer v = Viewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    // ---- 名单翻查（所有登录用户） ----

    /** 名单翻查；includeDisabled=true 含停用条目。 */
    @GetMapping("/categories")
    public ApiResponse<List<IndustryCategory>> categories(@RequestParam(required = false, defaultValue = "false")
                                                          boolean includeDisabled) {
        return ApiResponse.ok(categoryService.list(includeDisabled));
    }

    /** 名单版本链（新版本在前，含全量快照）。 */
    @GetMapping("/versions")
    public ApiResponse<List<IndustryCategoryVersion>> versions() {
        return ApiResponse.ok(categoryService.listVersions());
    }

    /** 逐条操作记录；?categoryCode= 可只看某条目。 */
    @GetMapping("/operations")
    public ApiResponse<List<IndustryCategoryOpLog>> operations(@RequestParam(required = false) String categoryCode) {
        return ApiResponse.ok(categoryService.listOps(categoryCode));
    }

    /** 上刊页预检：按报价单号看客户行业是否命中受限。 */
    @PostMapping("/check/order")
    public ApiResponse<Map<String, Object>> checkOrder(@Valid @RequestBody CheckOrderRequest request) {
        return ApiResponse.ok(freezeService.checkQuoteOrder(request.getQuoteNo()));
    }

    // ---- 合规岗维护（即改即生效） ----

    @PostMapping("/categories")
    public ApiResponse<IndustryCategory> add(@Valid @RequestBody CategoryCreateRequest request) {
        return ApiResponse.ok(categoryService.addCategory(request, viewer()));
    }

    @PostMapping("/categories/{code}/restrict")
    public ApiResponse<IndustryCategory> restrict(@PathVariable String code,
                                                  @Valid @RequestBody CategoryRestrictRequest request) {
        return ApiResponse.ok(categoryService.restrict(code, request, viewer()));
    }

    @PostMapping("/categories/{code}/release")
    public ApiResponse<IndustryCategory> release(@PathVariable String code,
                                                 @RequestBody(required = false) CategoryRemarkRequest request) {
        return ApiResponse.ok(categoryService.release(code,
                request != null ? request.getRemark() : null, viewer()));
    }

    @PostMapping("/categories/{code}/modify")
    public ApiResponse<IndustryCategory> modify(@PathVariable String code,
                                                @Valid @RequestBody CategoryModifyRequest request) {
        return ApiResponse.ok(categoryService.modify(code, request, viewer()));
    }

    @PostMapping("/categories/{code}/disable")
    public ApiResponse<IndustryCategory> disable(@PathVariable String code,
                                                 @RequestBody(required = false) CategoryRemarkRequest request) {
        return ApiResponse.ok(categoryService.disable(code,
                request != null ? request.getRemark() : null, viewer()));
    }

    // ---- 在途单批量冻结/解冻（先预览后一键执行） ----

    @PostMapping("/freeze/preview")
    public ApiResponse<FreezePreviewView> freezePreview(@Valid @RequestBody FreezeRequest request) {
        return ApiResponse.ok(freezeService.previewFreeze(request.getCategoryCode()));
    }

    @PostMapping("/freeze/execute")
    public ApiResponse<FreezePreviewView> freezeExecute(@Valid @RequestBody FreezeRequest request) {
        return ApiResponse.ok(freezeService.executeFreeze(request.getCategoryCode(),
                request.getRemark(), viewer()));
    }

    @PostMapping("/unfreeze/preview")
    public ApiResponse<FreezePreviewView> unfreezePreview(@Valid @RequestBody FreezeRequest request) {
        return ApiResponse.ok(freezeService.previewUnfreeze(request.getCategoryCode()));
    }

    @PostMapping("/unfreeze/execute")
    public ApiResponse<FreezePreviewView> unfreezeExecute(@Valid @RequestBody FreezeRequest request) {
        return ApiResponse.ok(freezeService.executeUnfreeze(request.getCategoryCode(),
                request.getRemark(), viewer()));
    }

    /** 当前登录视角：前端据此显隐合规维护区（真正鉴权在服务层）。 */
    @GetMapping("/viewer")
    public ApiResponse<Map<String, Object>> viewerInfo() {
        Viewer v = viewer();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("username", v.getUsername());
        info.put("complianceMaintainer", v.isComplianceMaintainer());
        return ApiResponse.ok(info);
    }
}
