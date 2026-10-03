package com.evops.ratecard.controller;

import com.evops.common.ApiResponse;
import com.evops.ratecard.dto.PricingResult;
import com.evops.ratecard.dto.QuoteDetailView;
import com.evops.ratecard.dto.QuoteSubmitRequest;
import com.evops.ratecard.entity.Quote;
import com.evops.ratecard.service.QuoteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/** 报价：提交（按当天刊例计价、单价快照留底）、试算、翻查。报价单提交后只读。 */
@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    /** 提交报价：按提交当天的生效刊例算价，含税/未税并列（默认含税），单价快照固化。 */
    @PostMapping
    public ApiResponse<QuoteDetailView> submit(@Valid @RequestBody QuoteSubmitRequest request) {
        return ApiResponse.ok(quoteService.submit(request));
    }

    /** 报价试算：不落库，提交前预览；?date= 可指定提交日（不能晚于今天）。 */
    @PostMapping("/preview")
    public ApiResponse<PricingResult> preview(@RequestParam(required = false) LocalDate date,
                                              @Valid @RequestBody QuoteSubmitRequest request) {
        return ApiResponse.ok(quoteService.preview(date, request));
    }

    /** 报价单详情：单头 + 当时的单价快照行。 */
    @GetMapping("/{quoteNo}")
    public ApiResponse<QuoteDetailView> detail(@PathVariable String quoteNo) {
        return ApiResponse.ok(quoteService.detail(quoteNo));
    }

    /** 报价单翻查：?customerName=&rateYear=&from=&to=。 */
    @GetMapping
    public ApiResponse<List<Quote>> list(@RequestParam(required = false) String customerName,
                                         @RequestParam(required = false) Integer rateYear,
                                         @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to) {
        return ApiResponse.ok(quoteService.list(customerName, rateYear, from, to));
    }
}
