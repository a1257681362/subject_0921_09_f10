package com.evops.agent.controller;

import com.evops.agent.dto.AgreementAdjustRequest;
import com.evops.agent.dto.AgreementCreateRequest;
import com.evops.agent.dto.AgreementTerminateRequest;
import com.evops.agent.entity.Agent;
import com.evops.agent.entity.AgentAgreement;
import com.evops.agent.entity.AgentAgreementVersion;
import com.evops.agent.service.AgentService;
import com.evops.agent.service.AgreementService;
import com.evops.common.AgentViewer;
import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 代理档案、点位挂协议、协议版本（中途调整下期生效/终止）翻查与操作。 */
@RestController
@RequestMapping("/api/agents")
public class AgentAgreementController {

    private final AgentService agentService;
    private final AgreementService agreementService;

    public AgentAgreementController(AgentService agentService, AgreementService agreementService) {
        this.agentService = agentService;
        this.agreementService = agreementService;
    }

    private static AgentViewer viewer() {
        AgentViewer v = AgentViewer.current();
        if (v == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return v;
    }

    /** 代理建档（财务/系统）。 */
    @PostMapping
    public ApiResponse<Agent> createAgent(@Valid @RequestBody com.evops.agent.dto.AgentCreateRequest request) {
        return ApiResponse.ok(agentService.create(request, viewer()));
    }

    /** 代理翻查：代理只见自己，财务/系统看全部。 */
    @GetMapping
    public ApiResponse<List<Agent>> listAgents(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        return ApiResponse.ok(agentService.list(viewer(), keyword, status));
    }

    /** 代理详情（代理只能看自己）。 */
    @GetMapping("/{agentNo}")
    public ApiResponse<Agent> agentDetail(@PathVariable String agentNo) {
        return ApiResponse.ok(agentService.requireViewable(agentNo, viewer()));
    }

    /** 停用代理（不参与新结算，历史账单照翻）。 */
    @PostMapping("/{agentNo}/disable")
    public ApiResponse<Agent> disableAgent(@PathVariable String agentNo) {
        return ApiResponse.ok(agentService.changeStatus(agentNo, com.evops.agent.AgentEnums.AGENT_DISABLED, viewer()));
    }

    /** 重新启用代理。 */
    @PostMapping("/{agentNo}/enable")
    public ApiResponse<Agent> enableAgent(@PathVariable String agentNo) {
        return ApiResponse.ok(agentService.changeStatus(agentNo, com.evops.agent.AgentEnums.AGENT_ACTIVE, viewer()));
    }

    /** 点位挂代理协议（比例 + 起始结算期）。 */
    @PostMapping("/agreements")
    public ApiResponse<AgentAgreement> createAgreement(@Valid @RequestBody AgreementCreateRequest request) {
        return ApiResponse.ok(agreementService.create(request, viewer()));
    }

    /** 协议翻查：?agentNo=&siteNo=&status=；代理只见自己名下。 */
    @GetMapping("/agreements")
    public ApiResponse<List<AgentAgreement>> listAgreements(@RequestParam(required = false) String agentNo,
                                                            @RequestParam(required = false) String siteNo,
                                                            @RequestParam(required = false) String status) {
        return ApiResponse.ok(agreementService.list(viewer(), agentNo, siteNo, status));
    }

    /** 协议详情（代理只能看自己名下）。 */
    @GetMapping("/agreements/{agreementNo}")
    public ApiResponse<AgentAgreement> agreementDetail(@PathVariable String agreementNo) {
        return ApiResponse.ok(agreementService.requireViewable(agreementNo, viewer()));
    }

    /** 协议版本链翻查（比例与有效期逐版留底）。 */
    @GetMapping("/agreements/{agreementNo}/versions")
    public ApiResponse<List<AgentAgreementVersion>> versions(@PathVariable String agreementNo) {
        return ApiResponse.ok(agreementService.listVersions(agreementNo, viewer()));
    }

    /** 中途调比例：从下一结算期生效，历史期不重算。 */
    @PostMapping("/agreements/{agreementNo}/adjust")
    public ApiResponse<AgentAgreementVersion> adjust(@PathVariable String agreementNo,
                                                     @Valid @RequestBody AgreementAdjustRequest request) {
        return ApiResponse.ok(agreementService.adjust(agreementNo, request, viewer()));
    }

    /** 终止协议：从下一结算期起不再分账。 */
    @PostMapping("/agreements/{agreementNo}/terminate")
    public ApiResponse<AgentAgreementVersion> terminate(@PathVariable String agreementNo,
                                                        @Valid @RequestBody AgreementTerminateRequest request) {
        return ApiResponse.ok(agreementService.terminate(agreementNo, request, viewer()));
    }
}
