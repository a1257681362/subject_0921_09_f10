package com.evops.credit.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.dto.LimitApprovalRequest;
import com.evops.credit.dto.LimitChangeRequest;
import com.evops.credit.entity.CreditLimitVersion;
import com.evops.credit.entity.Customer;
import com.evops.credit.service.CreditLimitService;
import com.evops.credit.service.CustomerService;
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

/** 客户建档授信、信用额度版本翻查、额度变更申请与审批；销售只见名下客户，财务审批。 */
@RestController
@RequestMapping("/api/credit")
public class CreditCustomerController {

    private final CustomerService customerService;
    private final CreditLimitService limitService;

    public CreditCustomerController(CustomerService customerService,
                                    CreditLimitService limitService) {
        this.customerService = customerService;
        this.limitService = limitService;
    }

    private static Viewer viewer() {
        Viewer v = Viewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    /** 客户建档给信用额度（自动 v1 生效，前值 0）。 */
    @PostMapping("/customers")
    public ApiResponse<Customer> create(@Valid @RequestBody CustomerCreateRequest request) {
        return ApiResponse.ok(customerService.create(request, viewer()));
    }

    /** 客户翻查：销售只看名下，财务/系统看全部。 */
    @GetMapping("/customers")
    public ApiResponse<List<Customer>> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String status) {
        return ApiResponse.ok(customerService.list(viewer(), keyword, status));
    }

    /** 客户详情（含额度、在额占用、可用额度）。 */
    @GetMapping("/customers/{customerNo}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String customerNo) {
        Customer customer = customerService.requireViewable(customerNo, viewer());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("customer", customer);
        view.put("availableAmount", CreditLimitService.available(customer));
        return ApiResponse.ok(view);
    }

    /** 信用额度版本翻查：前后值、申请/审批人、状态全在。 */
    @GetMapping("/customers/{customerNo}/versions")
    public ApiResponse<List<CreditLimitVersion>> versions(@PathVariable String customerNo) {
        return ApiResponse.ok(limitService.listVersions(customerNo, viewer()));
    }

    /** 提交额度变更申请（ADJUST/FREEZE/UNFREEZE），先进待审批。 */
    @PostMapping("/customers/{customerNo}/applications")
    public ApiResponse<CreditLimitVersion> apply(@PathVariable String customerNo,
                                                 @Valid @RequestBody LimitChangeRequest request) {
        return ApiResponse.ok(limitService.apply(customerNo, request, viewer()));
    }

    /** 待审批列表：财务看全部，销售只看名下客户的申请。 */
    @GetMapping("/limit-versions/pending")
    public ApiResponse<List<CreditLimitVersion>> pending() {
        return ApiResponse.ok(limitService.listPending(viewer()));
    }

    /** 财务审批通过（申请人不能审批自己的申请）。 */
    @PostMapping("/limit-versions/{versionId}/approve")
    public ApiResponse<CreditLimitVersion> approve(@PathVariable Long versionId,
                                                   @RequestBody(required = false) LimitApprovalRequest request) {
        return ApiResponse.ok(limitService.approve(versionId,
                request == null ? new LimitApprovalRequest() : request, viewer()));
    }

    /** 财务驳回（留底不删）。 */
    @PostMapping("/limit-versions/{versionId}/reject")
    public ApiResponse<CreditLimitVersion> reject(@PathVariable Long versionId,
                                                  @RequestBody(required = false) LimitApprovalRequest request) {
        return ApiResponse.ok(limitService.reject(versionId,
                request == null ? new LimitApprovalRequest() : request, viewer()));
    }
}
