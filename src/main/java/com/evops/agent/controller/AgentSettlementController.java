package com.evops.agent.controller;

import com.evops.agent.dto.SettlementCreateRequest;
import com.evops.agent.dto.SettlementDetailView;
import com.evops.agent.entity.AgentSettlement;
import com.evops.agent.service.SettlementService;
import com.evops.common.AgentViewer;
import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 代理结算：出结算（财务/系统）、账单查询（代理只能查自己）、CSV 导出。 */
@RestController
@RequestMapping("/api/agent-settlements")
public class AgentSettlementController {

    private final SettlementService settlementService;

    public AgentSettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    private static AgentViewer viewer() {
        AgentViewer v = AgentViewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    /** 出结算：按结算期对指定代理或全部在册代理落结算快照（已结代理幂等跳过，历史期不重算）。 */
    @PostMapping
    public ApiResponse<SettlementService.SettlementRunView> settle(
            @RequestBody(required = false) SettlementCreateRequest request) {
        SettlementCreateRequest req = request == null ? new SettlementCreateRequest() : request;
        return ApiResponse.ok(settlementService.settle(req.getPeriod(), req.getAgentNo(),
                req.getOperatorName(), viewer()));
    }

    /** 账单翻查：?period=yyyy-MM&agentNo=；代理只见自己，财务/系统看全部。 */
    @GetMapping
    public ApiResponse<List<AgentSettlement>> list(@RequestParam(required = false) String period,
                                                   @RequestParam(required = false) String agentNo) {
        return ApiResponse.ok(settlementService.list(viewer(), period, agentNo));
    }

    /** 账单详情（头 + 逐笔流水分账行）；代理只能看自己。 */
    @GetMapping("/{settlementNo}")
    public ApiResponse<SettlementDetailView> detail(@PathVariable String settlementNo) {
        return ApiResponse.ok(settlementService.detail(settlementNo, viewer()));
    }

    /** 按期导出结算汇总 CSV（BOM）；代理导出只含自己。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportSummary(@RequestParam String period) {
        byte[] csv = settlementService.settlementsCsv(period, viewer()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"agent-settlement-" + period + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    /** 导出单张结算单逐笔行 CSV（BOM）；代理只能导自己。 */
    @GetMapping("/{settlementNo}/export")
    public ResponseEntity<byte[]> exportLines(@PathVariable String settlementNo) {
        byte[] csv = settlementService.linesCsv(settlementNo, viewer()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"agent-settlement-lines-" + settlementNo + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
