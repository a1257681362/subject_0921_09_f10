package com.evops.install.controller;

import com.evops.common.ApiResponse;
import com.evops.install.dto.CertRegisterRequest;
import com.evops.install.dto.CertRevokeRequest;
import com.evops.install.dto.TeamCreateRequest;
import com.evops.install.entity.InstallCert;
import com.evops.install.entity.InstallTeam;
import com.evops.install.service.InstallTeamService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * F8 安装队与资质档案：建档、停用/启用、资质登记（高空作业证等）与吊销、翻查。
 */
@RestController
@RequestMapping("/api/install")
public class InstallTeamController {

    private final InstallTeamService teamService;

    public InstallTeamController(InstallTeamService teamService) {
        this.teamService = teamService;
    }

    /** 安装队建档（队名全局唯一）。 */
    @PostMapping("/teams")
    public ApiResponse<InstallTeam> create(@Valid @RequestBody TeamCreateRequest request) {
        return ApiResponse.ok(teamService.create(request));
    }

    /** 翻查安装队：?status=&keyword= */
    @GetMapping("/teams")
    public ApiResponse<List<InstallTeam>> list(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(teamService.list(status, keyword));
    }

    @GetMapping("/teams/{teamNo}")
    public ApiResponse<InstallTeam> detail(@PathVariable String teamNo) {
        return ApiResponse.ok(teamService.requireByNo(teamNo));
    }

    /** 停用：停用后不参与匹配（已派未完工的单不动）。 */
    @PostMapping("/teams/{teamNo}/disable")
    public ApiResponse<InstallTeam> disable(@PathVariable String teamNo,
                                            @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(teamService.disable(teamNo, operatorName));
    }

    /** 重新启用。 */
    @PostMapping("/teams/{teamNo}/enable")
    public ApiResponse<InstallTeam> enable(@PathVariable String teamNo,
                                           @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(teamService.enable(teamNo, operatorName));
    }

    /** 资质登记（一张证一行，证书编号全局唯一；高空作业证 WORK_AT_HEIGHT 是高空点位硬门槛）。 */
    @PostMapping("/certs")
    public ApiResponse<InstallCert> registerCert(@Valid @RequestBody CertRegisterRequest request) {
        return ApiResponse.ok(teamService.registerCert(request));
    }

    /** 翻查某安装队的全部资质。 */
    @GetMapping("/teams/{teamNo}/certs")
    public ApiResponse<List<InstallCert>> listCerts(@PathVariable String teamNo) {
        return ApiResponse.ok(teamService.listCerts(teamNo));
    }

    /** 吊销资质（高空证被吊销/暂扣后，该队接不了高空点位）。 */
    @PostMapping("/certs/{certId}/revoke")
    public ApiResponse<InstallCert> revokeCert(@PathVariable Long certId,
                                               @Valid @RequestBody CertRevokeRequest request) {
        return ApiResponse.ok(teamService.revokeCert(certId, request));
    }
}
