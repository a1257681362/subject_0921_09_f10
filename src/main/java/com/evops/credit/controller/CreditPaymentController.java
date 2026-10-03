package com.evops.credit.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.credit.dto.PaymentDetailView;
import com.evops.credit.dto.PaymentRegisterRequest;
import com.evops.credit.dto.WriteoffRequest;
import com.evops.credit.entity.CreditOccupation;
import com.evops.credit.entity.Payment;
import com.evops.credit.service.CreditService;
import com.evops.credit.service.CustomerService;
import com.evops.credit.service.PaymentService;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** 回款登记、按单分次核销、占用/应收明细翻查，以及财务月末每户占用明细 CSV。 */
@RestController
@RequestMapping("/api/credit")
public class CreditPaymentController {

    private final PaymentService paymentService;
    private final CreditService creditService;
    private final CustomerService customerService;

    public CreditPaymentController(PaymentService paymentService, CreditService creditService,
                                   CustomerService customerService) {
        this.paymentService = paymentService;
        this.creditService = creditService;
        this.customerService = customerService;
    }

    private static Viewer viewer() {
        Viewer v = Viewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    /** 回款登记：一笔回款一张凭证，登记后再按单核销。 */
    @PostMapping("/payments")
    public ApiResponse<Payment> register(@Valid @RequestBody PaymentRegisterRequest request) {
        return ApiResponse.ok(paymentService.register(request, viewer()));
    }

    /** 回款翻查：销售限名下；?customerNo=&from=&to= */
    @GetMapping("/payments")
    public ApiResponse<List<Payment>> listPayments(@RequestParam(required = false) String customerNo,
                                                   @RequestParam(required = false) LocalDate from,
                                                   @RequestParam(required = false) LocalDate to) {
        return ApiResponse.ok(paymentService.list(viewer(), customerNo, from, to));
    }

    /** 回款凭证详情（凭证头 + 逐单核销明细）。 */
    @GetMapping("/payments/{paymentNo}")
    public ApiResponse<PaymentDetailView> paymentDetail(@PathVariable String paymentNo) {
        PaymentDetailView view = paymentService.detail(paymentNo);
        viewer().checkOwn(customerService.requireById(view.getPayment().getCustomerId()).getOwnerSales());
        return ApiResponse.ok(view);
    }

    /** 按单核销（部分回款分次核；一笔回款也可核多张单）。 */
    @PostMapping("/write-offs")
    public ApiResponse<PaymentDetailView> writeoff(@Valid @RequestBody WriteoffRequest request) {
        return ApiResponse.ok(paymentService.writeoff(request, viewer()));
    }

    /** 占用/应收明细：?customerNo=&period=yyyy-MM&status=&settleStatus=，销售限名下。 */
    @GetMapping("/occupations")
    public ApiResponse<List<CreditOccupation>> occupations(@RequestParam(required = false) String customerNo,
                                                           @RequestParam(required = false) String period,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String settleStatus) {
        return ApiResponse.ok(creditService.list(viewer(), customerNo, period, status, settleStatus));
    }

    /** 月末每户占用明细（按固化归属期间 yyyy-MM）。 */
    @GetMapping("/reports/occupation")
    public ApiResponse<List<CreditOccupation>> occupationReport(@RequestParam String period) {
        return ApiResponse.ok(creditService.monthReport(period, viewer()));
    }

    /** 月末占用明细导出 CSV（BOM，Excel 打开不乱码）。 */
    @GetMapping("/reports/occupation/export")
    public ResponseEntity<byte[]> exportOccupation(@RequestParam String period) {
        byte[] csv = creditService.occupationCsv(period, viewer()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"credit-occupation-" + period + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
