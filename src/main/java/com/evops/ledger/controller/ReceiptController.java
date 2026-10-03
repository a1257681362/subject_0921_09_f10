package com.evops.ledger.controller;

import com.evops.common.ApiResponse;
import com.evops.ledger.dto.ReceiptRequest;
import com.evops.ledger.entity.OrderReceipt;
import com.evops.ledger.service.ReceiptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 外部回执：挂回原单据（给对冲单号自动回溯根单）。 */
@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @PostMapping
    public ApiResponse<OrderReceipt> attach(@Valid @RequestBody ReceiptRequest request) {
        return ApiResponse.ok(receiptService.attach(request));
    }

    /** 某张单据自己的回执。 */
    @GetMapping("/order/{orderNo}")
    public ApiResponse<List<OrderReceipt>> byOrder(@PathVariable String orderNo) {
        return ApiResponse.ok(receiptService.listByOrderNo(orderNo));
    }
}
