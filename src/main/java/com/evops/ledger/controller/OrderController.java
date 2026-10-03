package com.evops.ledger.controller;

import com.evops.common.ApiResponse;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.OrderEditRequest;
import com.evops.ledger.dto.RollbackRequest;
import com.evops.ledger.entity.OrderEvent;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.service.OrderService;
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

/** 上刊单：上刊（挂起）、落架、对冲、留痕更正、版本回退、翻查。 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 上刊：画面挂到点位的当周档期，状态挂起。 */
    @PostMapping
    public ApiResponse<PostingOrder> create(@Valid @RequestBody OrderCreateRequest request) {
        return ApiResponse.ok(orderService.createPosting(request));
    }

    /** 落架：关闭单据并释放档期。 */
    @PostMapping("/{orderNo}/take-down")
    public ApiResponse<PostingOrder> takeDown(@PathVariable String orderNo,
                                              @RequestBody(required = false) ActionRequest request) {
        return ApiResponse.ok(orderService.takeDown(orderNo, request != null ? request : new ActionRequest()));
    }

    /** 对冲：不抹原单，补一张对冲单并释放档期。 */
    @PostMapping("/{orderNo}/offset")
    public ApiResponse<PostingOrder> offset(@PathVariable String orderNo,
                                            @RequestBody(required = false) ActionRequest request) {
        return ApiResponse.ok(orderService.offset(orderNo, request != null ? request : new ActionRequest()));
    }

    /** 挂起中单据的留痕更正。 */
    @PostMapping("/{orderNo}/edit")
    public ApiResponse<PostingOrder> edit(@PathVariable String orderNo,
                                          @Valid @RequestBody OrderEditRequest request) {
        return ApiResponse.ok(orderService.edit(orderNo, request));
    }

    /** 版本选择与回退：恢复到某历史版本，回退动作也留底。 */
    @PostMapping("/{orderNo}/rollback")
    public ApiResponse<PostingOrder> rollback(@PathVariable String orderNo,
                                              @Valid @RequestBody RollbackRequest request) {
        return ApiResponse.ok(orderService.rollback(orderNo, request));
    }

    /** 翻查：?period=yyyy-MM&siteNo=&status=&orderNo= */
    @GetMapping
    public ApiResponse<List<PostingOrder>> search(@RequestParam(required = false) String period,
                                                  @RequestParam(required = false) String siteNo,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String orderNo) {
        return ApiResponse.ok(orderService.search(period, siteNo, status, orderNo));
    }

    /** 单据详情。 */
    @GetMapping("/{orderNo}")
    public ApiResponse<PostingOrder> detail(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.requireByNo(orderNo));
    }

    /** 单据的逐单事件留底（版本链 + 谁经手/哪天操作）。 */
    @GetMapping("/{orderNo}/events")
    public ApiResponse<List<OrderEvent>> events(@PathVariable String orderNo) {
        PostingOrder order = orderService.requireByNo(orderNo);
        return ApiResponse.ok(orderService.events(order.getId()));
    }

    /** 同根单链：原单 + 对冲单。 */
    @GetMapping("/{orderNo}/chain")
    public ApiResponse<Map<String, Object>> chain(@PathVariable String orderNo) {
        PostingOrder order = orderService.requireByNo(orderNo);
        List<PostingOrder> chain = orderService.chain(order.getRootOrderId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rootOrderId", order.getRootOrderId());
        result.put("orders", chain);
        return ApiResponse.ok(result);
    }
}
