package com.evops.install.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.install.InstallEnums;
import com.evops.install.dto.CertRegisterRequest;
import com.evops.install.dto.CertRevokeRequest;
import com.evops.install.dto.TeamCreateRequest;
import com.evops.install.entity.InstallCert;
import com.evops.install.entity.InstallTeam;
import com.evops.install.mapper.InstallCertMapper;
import com.evops.install.mapper.InstallTeamMapper;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 安装队与资质档案：建档、停用、资质登记/吊销；资质是否合格在这里一处判定。
 *
 * 高空点位硬门槛：队在册（ACTIVE）且持有状态 VALID、到期日不早于计划日的高空作业证。
 * 资质到期不静默改状态——状态列留 VALID，匹配时按 expiry_date 实时判过期（以计划日为准）。
 */
@Service
public class InstallTeamService {

    private final InstallTeamMapper teamMapper;
    private final InstallCertMapper certMapper;

    public InstallTeamService(InstallTeamMapper teamMapper, InstallCertMapper certMapper) {
        this.teamMapper = teamMapper;
        this.certMapper = certMapper;
    }

    // ---- 安装队 ----

    @Transactional(rollbackFor = Exception.class)
    public InstallTeam create(TeamCreateRequest request) {
        String name = request.getTeamName().trim();
        Long exists = teamMapper.selectCount(new LambdaQueryWrapper<InstallTeam>()
                .eq(InstallTeam::getTeamName, name));
        if (exists != null && exists > 0) {
            throw new BusinessException("安装队名称「" + name + "」已存在，队名不能重复");
        }
        String operator = Operators.resolve(request.getOperatorName());
        InstallTeam team = new InstallTeam();
        team.setTeamNo(generateNo());
        team.setTeamName(name);
        team.setLeaderName(request.getLeaderName().trim());
        team.setContact(trimTo(request.getContact(), 128));
        team.setStatus(InstallEnums.TEAM_ACTIVE);
        team.setCurrentVersionNo(1);
        team.setOperatorName(operator);
        team.setRemark(trimTo(request.getRemark(), 255));
        try {
            teamMapper.insert(team);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("安装队编号或名称冲突，请重试");
        }
        return team;
    }

    /** 停用：停用后不参与任何匹配（已派未完工的单不动）。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallTeam disable(String teamNo, String operatorName) {
        InstallTeam team = requireByNo(teamNo);
        team.setStatus(InstallEnums.TEAM_DISABLED);
        team.setOperatorName(Operators.resolve(operatorName));
        teamMapper.updateById(team);
        return team;
    }

    /** 重新启用。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallTeam enable(String teamNo, String operatorName) {
        InstallTeam team = requireByNo(teamNo);
        team.setStatus(InstallEnums.TEAM_ACTIVE);
        team.setOperatorName(Operators.resolve(operatorName));
        teamMapper.updateById(team);
        return team;
    }

    public List<InstallTeam> list(String status, String keyword) {
        LambdaQueryWrapper<InstallTeam> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(InstallTeam::getStatus, status.trim().toUpperCase());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(InstallTeam::getTeamNo, kw).or().like(InstallTeam::getTeamName, kw));
        }
        wrapper.orderByAsc(InstallTeam::getId);
        return teamMapper.selectList(wrapper);
    }

    public InstallTeam requireByNo(String teamNo) {
        if (teamNo == null || teamNo.trim().isEmpty()) {
            throw new BusinessException("安装队编号不能为空");
        }
        InstallTeam team = teamMapper.selectOne(new LambdaQueryWrapper<InstallTeam>()
                .eq(InstallTeam::getTeamNo, teamNo.trim()));
        if (team == null) {
            throw new BusinessException("安装队不存在: " + teamNo);
        }
        return team;
    }

    // ---- 资质 ----

    @Transactional(rollbackFor = Exception.class)
    public InstallCert registerCert(CertRegisterRequest request) {
        InstallTeam team = requireByNo(request.getTeamNo());
        String type = normalizeCertType(request.getCertType());
        if (request.getExpiryDate() == null) {
            throw new BusinessException("资质到期日不能为空");
        }
        String operator = Operators.resolve(request.getOperatorName());

        InstallCert cert = new InstallCert();
        cert.setTeamId(team.getId());
        cert.setCertType(type);
        cert.setCertNo(request.getCertNo().trim());
        cert.setCertName(trimTo(request.getCertName(), 128));
        cert.setIssueDate(request.getIssueDate());
        cert.setExpiryDate(request.getExpiryDate());
        // 登记时即已过期也照样建档、直接标 EXPIRED（留痕），但不参与匹配
        cert.setStatus(request.getExpiryDate().isBefore(LocalDate.now())
                ? InstallEnums.CERT_EXPIRED : InstallEnums.CERT_VALID);
        cert.setOperatorName(operator);
        cert.setRemark(trimTo(request.getRemark(), 255));
        try {
            certMapper.insert(cert);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("证书编号 " + request.getCertNo() + " 已登记，证书编号全局唯一");
        }
        bumpTeamVersion(team);
        return cert;
    }

    /** 吊销资质（高空证被吊销/暂扣）：状态置 REVOKED，之后高空点位匹配不到该队。 */
    @Transactional(rollbackFor = Exception.class)
    public InstallCert revokeCert(Long certId, CertRevokeRequest request) {
        InstallCert cert = certMapper.selectById(certId);
        if (cert == null) {
            throw new BusinessException("资质不存在: id=" + certId);
        }
        cert.setStatus(InstallEnums.CERT_REVOKED);
        cert.setRevokeReason(trimTo(request.getReason(), 255));
        cert.setOperatorName(Operators.resolve(request.getOperatorName()));
        certMapper.updateById(cert);
        InstallTeam team = teamMapper.selectById(cert.getTeamId());
        if (team != null) {
            bumpTeamVersion(team);
        }
        return cert;
    }

    public List<InstallCert> listCerts(String teamNo) {
        InstallTeam team = requireByNo(teamNo);
        return certMapper.selectList(new LambdaQueryWrapper<InstallCert>()
                .eq(InstallCert::getTeamId, team.getId())
                .orderByAsc(InstallCert::getCertType).orderByDesc(InstallCert::getId));
    }

    /** 全部在册安装队（匹配引擎按建队顺序挑第一个合格且空闲的）。 */
    public List<InstallTeam> activeTeams() {
        return teamMapper.selectList(new LambdaQueryWrapper<InstallTeam>()
                .eq(InstallTeam::getStatus, InstallEnums.TEAM_ACTIVE)
                .orderByAsc(InstallTeam::getId));
    }

    /**
     * 资质是否合格：普通点位只要求队在册；高空点位额外要求一张「状态 VALID 且到期日不早于计划日」
     * 的高空作业证。到期当日仍有效（!expiry.isBefore(planDate)），次日起不合格。
     */
    public boolean isQualified(InstallTeam team, String workType, LocalDate planDate) {
        if (team == null || !InstallEnums.TEAM_ACTIVE.equals(team.getStatus())) {
            return false;
        }
        if (!InstallEnums.isHighAltitude(workType)) {
            return true;
        }
        Long count = certMapper.selectCount(new LambdaQueryWrapper<InstallCert>()
                .eq(InstallCert::getTeamId, team.getId())
                .eq(InstallCert::getCertType, InstallEnums.CERT_WORK_AT_HEIGHT)
                .eq(InstallCert::getStatus, InstallEnums.CERT_VALID)
                .ge(InstallCert::getExpiryDate, planDate));
        return count != null && count > 0;
    }

    /** 给出不合格原因（仅高空点位、队在册时用于报错/说明）。 */
    public String highAltitudeBlockReason(InstallTeam team, LocalDate planDate) {
        InstallCert cert = certMapper.selectOne(new LambdaQueryWrapper<InstallCert>()
                .eq(InstallCert::getTeamId, team.getId())
                .eq(InstallCert::getCertType, InstallEnums.CERT_WORK_AT_HEIGHT)
                .orderByDesc(InstallCert::getExpiryDate).last("LIMIT 1"));
        if (cert == null) {
            return "该队没有登记高空作业证，不能接高空点位";
        }
        if (InstallEnums.CERT_REVOKED.equals(cert.getStatus())) {
            return "该队高空作业证已吊销，不能接高空点位";
        }
        if (InstallEnums.CERT_EXPIRED.equals(cert.getStatus())
                || cert.getExpiryDate().isBefore(planDate)) {
            return "该队高空作业证已于 " + cert.getExpiryDate() + " 到期（计划作业日 " + planDate
                    + "），高空证过期接不了高空点位";
        }
        return "该队资质不符合高空点位要求";
    }

    private void bumpTeamVersion(InstallTeam team) {
        team.setCurrentVersionNo((team.getCurrentVersionNo() == null ? 1 : team.getCurrentVersionNo()) + 1);
        teamMapper.updateById(team);
    }

    private String normalizeCertType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new BusinessException("资质类型不能为空");
        }
        String t = raw.trim().toUpperCase();
        switch (t) {
            case InstallEnums.CERT_WORK_AT_HEIGHT:
            case InstallEnums.CERT_ELECTRICIAN:
            case InstallEnums.CERT_SAFETY_OTHER:
                return t;
            default:
                throw new BusinessException("不支持的资质类型: " + raw
                        + "（WORK_AT_HEIGHT 高空作业证 / ELECTRICIAN 电工作业证 / SAFETY_OTHER 其他安全证）");
        }
    }

    private String generateNo() {
        long seq = teamMapper.nextTeamSeq();
        return String.format("IT%010d", seq);
    }

    private String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
