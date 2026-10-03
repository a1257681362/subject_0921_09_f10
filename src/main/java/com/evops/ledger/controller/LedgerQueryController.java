package com.evops.ledger.controller;

import com.evops.common.ApiResponse;
import com.evops.ledger.entity.SchemaVersion;
import com.evops.ledger.entity.SlotBooking;
import com.evops.ledger.service.SchemaVersionService;
import com.evops.ledger.service.SlotCalendarService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 翻查类：月度档期周历、点位档期流水、底账版本。 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerQueryController {

    private final SlotCalendarService calendarService;
    private final SchemaVersionService schemaVersionService;

    public LedgerQueryController(SlotCalendarService calendarService,
                                 SchemaVersionService schemaVersionService) {
        this.calendarService = calendarService;
        this.schemaVersionService = schemaVersionService;
    }

    /** 月度档期周历：?period=2026-09&businessDistrict=，一眼看哪块屏哪周空着。 */
    @GetMapping("/calendar")
    public ApiResponse<Map<String, Object>> calendar(@RequestParam String period,
                                                     @RequestParam(required = false) String businessDistrict) {
        return ApiResponse.ok(calendarService.monthly(period, businessDistrict));
    }

    /** 某块屏的逐周档期流水。 */
    @GetMapping("/sites/{siteNo}/timeline")
    public ApiResponse<List<SlotBooking>> timeline(@PathVariable String siteNo) {
        return ApiResponse.ok(calendarService.siteTimeline(siteNo));
    }

    /** 起步工作区的底账版本登记。 */
    @GetMapping("/versions")
    public ApiResponse<List<SchemaVersion>> versions() {
        return ApiResponse.ok(schemaVersionService.list());
    }
}
