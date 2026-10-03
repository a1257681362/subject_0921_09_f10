package com.evops.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.agent.AgentEnums;
import com.evops.agent.dto.AgreementAdjustRequest;
import com.evops.agent.dto.AgreementCreateRequest;
import com.evops.agent.dto.AgreementTerminateRequest;
import com.evops.agent.entity.Agent;
import com.evops.agent.entity.AgentAgreement;
import com.evops.agent.entity.AgentAgreementVersion;
import com.evops.agent.mapper.AgentAgreementMapper;
import com.evops.agent.mapper.AgentAgreementVersionMapper;
import com.evops.agent.support.Periods;
import com.evops.common.AgentViewer;
import com.evops.common.BusinessException;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.SiteMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 代理协议域：点位挂代理协议，比例与有效期（按结算期）定清楚。
 *
 * 版本口径：
 * - 建档挂协议即 v1（INITIAL）：起始期默认为当期、可指定未来期，不许追溯过去期；当期已到则立即生效。
 * - 中途调比例 / 终止一律另起版本（只追加），起始期最早只能是“下一结算期”，历史期不重算。
 * - 同一协议同时只准有一个待生效版本；待生效版到期在出结算时转生效，上一生效版置 SUPERSEDED。
 * - 某结算期用哪版：effective_from_period &lt;= 该期 的最大版本号；命中 TERMINATE 版即该期起不再分账。
 */
@Service
public class AgreementService {

    static final int RATE_SCALE = 4;

    private final AgentAgreementMapper agreementMapper;
    private final AgentAgreementVersionMapper versionMapper;
    private final AgentService agentService;
    private final SiteMapper siteMapper;

    public AgreementService(AgentAgreementMapper agreementMapper,
                            AgentAgreementVersionMapper versionMapper,
                            AgentService agentService,
                            SiteMapper siteMapper) {
        this.agreementMapper = agreementMapper;
        this.versionMapper = versionMapper;
        this.agentService = agentService;
        this.siteMapper = siteMapper;
    }

    /** 点位挂代理协议：一个代理在一个点位一份，比例与起始结算期定清楚。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentAgreement create(AgreementCreateRequest request, AgentViewer viewer) {
        viewer.requireManage();
        Agent agent = agentService.requireByNo(request.getAgentNo());
        if (!AgentEnums.AGENT_ACTIVE.equals(agent.getStatus())) {
            throw new BusinessException("代理「" + agent.getAgentName() + "」已停用，不能新挂协议");
        }
        Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>()
                .eq(Site::getSiteNo, request.getSiteNo().trim()));
        if (site == null) {
            throw new BusinessException("点位不存在: " + request.getSiteNo());
        }
        if (!LedgerEnums.SITE_LISTED.equals(site.getStatus())) {
            throw new BusinessException("点位 " + site.getSiteNo() + " 不是在营（已挂牌）状态，不能挂代理协议");
        }
        BigDecimal rate = normalizeRate(request.getCommissionRate());
        String nowPeriod = Periods.current();
        String from = request.getEffectiveFromPeriod() != null && !request.getEffectiveFromPeriod().trim().isEmpty()
                ? Periods.requireValid(request.getEffectiveFromPeriod().trim()) : nowPeriod;
        if (Periods.compare(from, nowPeriod) < 0) {
            throw new BusinessException("协议起始生效期不能早于当前结算期 " + nowPeriod + "：" + from);
        }

        AgentAgreement agreement = new AgentAgreement();
        agreement.setAgreementNo(generateAgreementNo());
        agreement.setAgentId(agent.getId());
        agreement.setAgentNo(agent.getAgentNo());
        agreement.setAgentNameSnapshot(agent.getAgentName());
        agreement.setSiteId(site.getId());
        agreement.setSiteNo(site.getSiteNo());
        agreement.setStatus(AgentEnums.AGREEMENT_ACTIVE);
        agreement.setOperatorName(viewer.getUsername());
        agreement.setRemark(request.getRemark());
        try {
            agreementMapper.insert(agreement);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("代理「" + agent.getAgentName() + "」在点位 " + site.getSiteNo()
                    + " 已挂过协议，同一代理同一点位只许一份协议");
        }

        boolean effectiveNow = Periods.compare(from, nowPeriod) <= 0;
        AgentAgreementVersion v1 = newVersion(agreement.getId(), AgentEnums.FIRST_VERSION_NO,
                AgentEnums.CHANGE_INITIAL, rate, from,
                effectiveNow ? AgentEnums.VERSION_EFFECTIVE : AgentEnums.VERSION_PENDING,
                "建档挂协议", viewer.getUsername());
        if (effectiveNow) {
            v1.setEffectiveTime(LocalDateTime.now());
        }
        versionMapper.insert(v1);

        AgentAgreement patch = new AgentAgreement();
        patch.setId(agreement.getId());
        if (effectiveNow) {
            patch.setCurrentVersionId(v1.getId());
            patch.setCurrentVersionNo(v1.getVersionNo());
            patch.setEffectiveFromPeriod(from);
        } else {
            patch.setPendingVersionId(v1.getId());
        }
        agreementMapper.updateById(patch);
        agreement.setCurrentVersionId(effectiveNow ? v1.getId() : null);
        agreement.setCurrentVersionNo(effectiveNow ? v1.getVersionNo() : null);
        agreement.setEffectiveFromPeriod(effectiveNow ? from : null);
        agreement.setPendingVersionId(effectiveNow ? null : v1.getId());
        return agreement;
    }

    /** 中途调比例：另起版本，从下一结算期（或指定未来期）生效，历史期不重算。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentAgreementVersion adjust(String agreementNo, AgreementAdjustRequest request, AgentViewer viewer) {
        viewer.requireManage();
        AgentAgreement agreement = requireActive(agreementNo);
        BigDecimal rate = normalizeRate(request.getCommissionRate());
        // 先校验起始期（入参合法性），再查待生效版，避免“当期生效”这类入参错误被“待生效”抢先报错
        String from = resolveFutureFrom(request.getEffectiveFromPeriod());
        requireNoPending(agreement);
        AgentAgreementVersion current = requireCurrentRateVersion(agreement);
        if (rate.compareTo(current.getCommissionRate()) == 0) {
            throw new BusinessException("调整后比例 " + rate.toPlainString() + " 与当前生效比例一致，无需调整");
        }
        return appendPending(agreement, AgentEnums.CHANGE_ADJUST, rate, from,
                request.getReason(), viewer.getUsername());
    }

    /** 终止协议：从下一结算期起不再分账；当期仍按协议结算，历史账单不动。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentAgreementVersion terminate(String agreementNo, AgreementTerminateRequest request, AgentViewer viewer) {
        viewer.requireManage();
        AgentAgreement agreement = requireActive(agreementNo);
        requireNoPending(agreement);
        String from = resolveFutureFrom(request.getEffectiveFromPeriod());
        // 终止版沿用上一生效版比例留底（该比例用于终止前当期的结算，终止期起本身不再分账）
        BigDecimal rate = requireCurrentRateVersion(agreement).getCommissionRate();
        return appendPending(agreement, AgentEnums.CHANGE_TERMINATE, rate, from,
                request.getReason(), viewer.getUsername());
    }

    /** 版本链翻查（新版本号在前）；代理只能翻自己名下协议。 */
    public List<AgentAgreementVersion> listVersions(String agreementNo, AgentViewer viewer) {
        AgentAgreement agreement = requireViewable(agreementNo, viewer);
        return versionMapper.selectList(new LambdaQueryWrapper<AgentAgreementVersion>()
                .eq(AgentAgreementVersion::getAgreementId, agreement.getId())
                .orderByDesc(AgentAgreementVersion::getVersionNo));
    }

    /** 协议翻查：代理视角只见自己名下；财务/系统看全部；可按代理/点位/状态过滤。 */
    public List<AgentAgreement> list(AgentViewer viewer, String agentNo, String siteNo, String status) {
        LambdaQueryWrapper<AgentAgreement> wrapper = new LambdaQueryWrapper<>();
        if (viewer.isScoped()) {
            List<Long> own = agentService.list(viewer, null, null).stream()
                    .map(Agent::getId).collect(java.util.stream.Collectors.toList());
            if (own.isEmpty()) {
                wrapper.apply("1 = 0");
            } else {
                wrapper.in(AgentAgreement::getAgentId, own);
            }
        }
        if (agentNo != null && !agentNo.trim().isEmpty()) {
            Agent agent = agentService.requireByNo(agentNo);
            viewer.checkOwn(agent.getLoginUsername());
            wrapper.eq(AgentAgreement::getAgentId, agent.getId());
        }
        if (siteNo != null && !siteNo.trim().isEmpty()) {
            Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>().eq(Site::getSiteNo, siteNo.trim()));
            if (site == null) {
                return java.util.Collections.emptyList();
            }
            wrapper.eq(AgentAgreement::getSiteId, site.getId());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(AgentAgreement::getStatus, status.trim());
        }
        return agreementMapper.selectList(wrapper.orderByDesc(AgentAgreement::getId));
    }

    public AgentAgreement requireByNo(String agreementNo) {
        AgentAgreement agreement = agreementMapper.selectOne(new LambdaQueryWrapper<AgentAgreement>()
                .eq(AgentAgreement::getAgreementNo, agreementNo == null ? "" : agreementNo.trim()));
        if (agreement == null) {
            throw new BusinessException("代理协议不存在: " + agreementNo);
        }
        return agreement;
    }

    /** 读协议并做代理归属校验。 */
    public AgentAgreement requireViewable(String agreementNo, AgentViewer viewer) {
        AgentAgreement agreement = requireByNo(agreementNo);
        Agent agent = agentService.requireById(agreement.getAgentId());
        viewer.checkOwn(agent.getLoginUsername());
        return agreement;
    }

    /**
     * 出结算前把到期的待生效版本转生效（幂等）：起始期 &lt;= 本期的 PENDING 版转 EFFECTIVE，
     * 上一生效版置 SUPERSEDED；TERMINATE 版生效时协议置 TERMINATED。按版本号顺序逐一切换。
     */
    @Transactional(rollbackFor = Exception.class)
    public void activateDueVersions(String period) {
        String p = Periods.requireValid(period);
        List<AgentAgreementVersion> due = versionMapper.selectList(new LambdaQueryWrapper<AgentAgreementVersion>()
                .eq(AgentAgreementVersion::getStatus, AgentEnums.VERSION_PENDING)
                .le(AgentAgreementVersion::getEffectiveFromPeriod, p)
                .orderByAsc(AgentAgreementVersion::getId));
        for (AgentAgreementVersion v : due) {
            AgentAgreement agreement = agreementMapper.selectById(v.getAgreementId());
            if (agreement == null) {
                continue;
            }
            if (agreement.getCurrentVersionId() != null) {
                AgentAgreementVersion superseded = new AgentAgreementVersion();
                superseded.setId(agreement.getCurrentVersionId());
                superseded.setStatus(AgentEnums.VERSION_SUPERSEDED);
                versionMapper.updateById(superseded);
            }
            v.setStatus(AgentEnums.VERSION_EFFECTIVE);
            v.setEffectiveTime(LocalDateTime.now());
            versionMapper.updateById(v);

            AgentAgreement patch = new AgentAgreement();
            patch.setId(agreement.getId());
            patch.setCurrentVersionId(v.getId());
            patch.setCurrentVersionNo(v.getVersionNo());
            patch.setEffectiveFromPeriod(v.getEffectiveFromPeriod());
            patch.setPendingVersionId(null);
            if (AgentEnums.CHANGE_TERMINATE.equals(v.getChangeType())) {
                patch.setStatus(AgentEnums.AGREEMENT_TERMINATED);
                patch.setTerminateFromPeriod(v.getEffectiveFromPeriod());
            }
            agreementMapper.updateById(patch);
        }
    }

    /**
     * 取某协议在某结算期适用的分账比例版本；该期不适用（起始期未到、已终止）返回 null。
     * 口径：effective_from_period &lt;= 该期 的最大版本号；命中 TERMINATE 版即该期起不分账。
     */
    public AgentAgreementVersion rateVersionForPeriod(AgentAgreement agreement, String period) {
        List<AgentAgreementVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<AgentAgreementVersion>()
                .eq(AgentAgreementVersion::getAgreementId, agreement.getId())
                .le(AgentAgreementVersion::getEffectiveFromPeriod, period)
                .orderByDesc(AgentAgreementVersion::getVersionNo));
        if (versions.isEmpty()) {
            return null;
        }
        AgentAgreementVersion latest = versions.get(0);
        if (AgentEnums.CHANGE_TERMINATE.equals(latest.getChangeType())) {
            return null;
        }
        return latest;
    }

    // ---- 内部方法 ----

    private AgentAgreement requireActive(String agreementNo) {
        AgentAgreement agreement = requireByNo(agreementNo);
        if (!AgentEnums.AGREEMENT_ACTIVE.equals(agreement.getStatus())) {
            throw new BusinessException("协议 " + agreement.getAgreementNo() + " 已终止，不能再调整；历史账单照翻");
        }
        return agreement;
    }

    private void requireNoPending(AgentAgreement agreement) {
        if (agreement.getPendingVersionId() != null) {
            throw new BusinessException("协议 " + agreement.getAgreementNo()
                    + " 已有待生效的调整，请待其生效后再提交新调整");
        }
    }

    private AgentAgreementVersion requireCurrentRateVersion(AgentAgreement agreement) {
        if (agreement.getCurrentVersionId() == null) {
            throw new BusinessException("协议 " + agreement.getAgreementNo() + " 尚未到起始生效期，没有生效中的比例版本");
        }
        AgentAgreementVersion current = versionMapper.selectById(agreement.getCurrentVersionId());
        if (current == null || AgentEnums.CHANGE_TERMINATE.equals(current.getChangeType())) {
            throw new BusinessException("协议 " + agreement.getAgreementNo() + " 当前无有效分账比例");
        }
        return current;
    }

    /** 调整/终止起始期：留空取下期；最早下期，不许当期或追溯。 */
    private String resolveFutureFrom(String requested) {
        String next = Periods.next(Periods.current());
        if (requested == null || requested.trim().isEmpty()) {
            return next;
        }
        String from = Periods.requireValid(requested.trim());
        if (Periods.compare(from, next) < 0) {
            throw new BusinessException("协议中途调整只能从下一结算期 " + next + " 起生效，不能当期生效或追溯：" + from);
        }
        return from;
    }

    private AgentAgreementVersion appendPending(AgentAgreement agreement, String changeType, BigDecimal rate,
                                                String from, String reason, String operator) {
        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<AgentAgreementVersion>()
                        .eq(AgentAgreementVersion::getAgreementId, agreement.getId()))
                .stream().map(AgentAgreementVersion::getVersionNo).max(Integer::compareTo).orElse(0);
        AgentAgreementVersion version = newVersion(agreement.getId(), maxNo + 1, changeType, rate, from,
                AgentEnums.VERSION_PENDING, reason, operator);
        try {
            versionMapper.insert(version);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("协议在该结算期 " + from + " 已有调整版本，同一协议同一起始期只许一版");
        }
        AgentAgreement patch = new AgentAgreement();
        patch.setId(agreement.getId());
        patch.setPendingVersionId(version.getId());
        agreementMapper.updateById(patch);
        agreement.setPendingVersionId(version.getId());
        return version;
    }

    private AgentAgreementVersion newVersion(Long agreementId, int versionNo, String changeType,
                                             BigDecimal rate, String from, String status,
                                             String reason, String operator) {
        AgentAgreementVersion v = new AgentAgreementVersion();
        v.setAgreementId(agreementId);
        v.setVersionNo(versionNo);
        v.setChangeType(changeType);
        v.setCommissionRate(rate);
        v.setEffectiveFromPeriod(from);
        v.setStatus(status);
        v.setReason(reason);
        v.setOperatorName(operator);
        return v;
    }

    static BigDecimal normalizeRate(BigDecimal rate) {
        if (rate == null) {
            throw new BusinessException("分账比例不能为空（0~1，如 0.1500 表示 15%）");
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException("分账比例必须在 0~1 之间：" + rate.toPlainString());
        }
        return rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private String generateAgreementNo() {
        long seq = agreementMapper.nextAgreementSeq();
        return String.format("AA%010d", seq);
    }
}
