package com.evops.compliance.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.compliance.dto.DisposeRequest;
import com.evops.compliance.dto.FreezePreviewView;
import com.evops.compliance.dto.FreezeRequest;
import com.evops.compliance.dto.PublishRequest;
import com.evops.compliance.dto.PublishResult;
import com.evops.compliance.dto.CategoryItemsConfigureRequest;
import com.evops.compliance.entity.CategoryList;
import com.evops.compliance.entity.CategoryListItem;
import com.evops.compliance.entity.CategoryListVersion;
import com.evops.compliance.entity.ComplianceFreezeBatch;
import com.evops.compliance.entity.ComplianceFreezeRecord;
import com.evops.compliance.service.CategoryListService;
import com.evops.compliance.service.ComplianceFreezeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 受限行业类目名单：生效名单与版本链翻查（所有登录角色），
 * 名单维护与冻结处置（仅合规岗/系统，Viewer.requireListAdmin 兜底）。
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final CategoryListService categoryListService;
    private final ComplianceFreezeService freezeService;

    public ComplianceController(CategoryListService categoryListService,
                                ComplianceFreezeService freezeService) {
        this.categoryListService = categoryListService;
        this.freezeService = freezeService;
    }

    private static Viewer viewer() {
        Viewer v = Viewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    /** 当前生效名单（主档 + 版本 + 全量条目含依据）；未发布过版本则版本与条目为空。 */
    @GetMapping("/list/effective")
    public ApiResponse<Map<String, Object>> effective() {
        CategoryList list = categoryListService.requireList();
        CategoryListVersion effective = categoryListService.currentEffective();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("list", list);
        view.put("version", effective);
        view.put("items", effective == null ? java.util.Collections.emptyList()
                : categoryListService.itemsOf(effective.getId()));
        return ApiResponse.ok(view);
    }

    /** 版本链翻查（版本即操作记录：经手人/发布时刻/调整说明）。 */
    @GetMapping("/list/versions")
    public ApiResponse<List<CategoryListVersion>> versions() {
        return ApiResponse.ok(categoryListService.listVersions());
    }

    /** 版本详情（含全量条目）。 */
    @GetMapping("/list/versions/{versionId}")
    public ApiResponse<Map<String, Object>> versionDetail(@PathVariable Long versionId) {
        CategoryListVersion v = categoryListService.requireVersion(versionId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", v);
        view.put("items", categoryListService.itemsOf(v.getId()));
        return ApiResponse.ok(view);
    }

    /** 另起新版（从当前生效版拷贝条目做起点；已有草稿直接返回）。仅合规岗/系统。 */
    @PostMapping("/list/versions")
    public ApiResponse<CategoryListVersion> newVersion() {
        return ApiResponse.ok(categoryListService.newVersion(viewer()));
    }

    /** 整表配置草稿条目（类目+依据）。仅合规岗/系统。 */
    @PutMapping("/list/versions/{versionId}/items")
    public ApiResponse<CategoryListVersion> configure(@PathVariable Long versionId,
                                                      @Valid @RequestBody CategoryItemsConfigureRequest request) {
        return ApiResponse.ok(categoryListService.configure(versionId, request.getItems(), viewer()));
    }

    /** 发布：即时生效，旧版自动取代；返回类目增减（新增类目可一键批量冻结）。仅合规岗/系统。 */
    @PostMapping("/list/versions/{versionId}/publish")
    public ApiResponse<PublishResult> publish(@PathVariable Long versionId,
                                              @RequestBody(required = false) PublishRequest request) {
        return ApiResponse.ok(categoryListService.publish(versionId,
                request == null ? null : request.getChangeSummary(), viewer()));
    }

    /** 冻结命中预览：?categories=医疗美容,保健食品（留空=生效版全量）。仅合规岗/系统。 */
    @GetMapping("/freeze/preview")
    public ApiResponse<FreezePreviewView> preview(@RequestParam(required = false) String categories) {
        return ApiResponse.ok(freezeService.preview(split(categories), viewer()));
    }

    /** 批量冻结在途单。仅合规岗/系统。 */
    @PostMapping("/freeze")
    public ApiResponse<ComplianceFreezeBatch> freeze(@RequestBody(required = false) FreezeRequest request) {
        FreezeRequest req = request == null ? new FreezeRequest() : request;
        return ApiResponse.ok(freezeService.freeze(req.getCategories(), req.getReason(), viewer()));
    }

    /** 冻结批次翻查。仅合规岗/系统。 */
    @GetMapping("/freeze/batches")
    public ApiResponse<List<ComplianceFreezeBatch>> batches() {
        return ApiResponse.ok(freezeService.listBatches(viewer()));
    }

    /** 批次详情（含逐单记录行）。仅合规岗/系统。 */
    @GetMapping("/freeze/batches/{batchNo}")
    public ApiResponse<Map<String, Object>> batchDetail(@PathVariable String batchNo) {
        ComplianceFreezeBatch batch = freezeService.requireBatch(batchNo, viewer());
        List<ComplianceFreezeRecord> records = freezeService.recordsOf(batch.getId(), viewer());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batch", batch);
        view.put("records", records);
        return ApiResponse.ok(view);
    }

    /** 解冻（特批放行，同类目不再重复冻结）。仅合规岗/系统。 */
    @PostMapping("/orders/{orderNo}/unfreeze")
    public ApiResponse<String> unfreeze(@PathVariable String orderNo,
                                        @RequestBody(required = false) DisposeRequest request) {
        freezeService.unfreeze(orderNo, request == null ? null : request.getReason(), viewer());
        return ApiResponse.ok("已解冻");
    }

    /** 强制落架（冻结单下画，走正常落架主流程）。仅合规岗/系统。 */
    @PostMapping("/orders/{orderNo}/force-take-down")
    public ApiResponse<String> forceTakeDown(@PathVariable String orderNo,
                                             @RequestBody(required = false) DisposeRequest request) {
        freezeService.forceTakeDown(orderNo, request == null ? null : request.getReason(), viewer());
        return ApiResponse.ok("已强制落架");
    }

    private static List<String> split(String categories) {
        if (categories == null || categories.trim().isEmpty()) {
            return null;
        }
        return Arrays.stream(categories.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
