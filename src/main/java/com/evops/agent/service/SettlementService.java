package com.evops.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.agent.AgentEnums;
import com.evops.agent.dto.SettlementDetailView;
import com.evops.agent.entity.Agent;
import com.evops.agent.entity.AgentAgreement;
import com.evops.agent.entity.AgentAgreementVersion;
import com.evops.agent.entity.AgentSettlement;
import com.evops.agent.entity.AgentSettlementLine;
import com.evops.agent.mapper.AgentAgreementMapper;
import com.evops.agent.mapper.AgentSettlementLineMapper;
import com.evops.agent.mapper.AgentSettlementMapper;
import com.evops.agent.support.Periods;
import com.evops.common.AgentViewer;
import com.evops.common.BusinessException;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.mapper.PostingOrderMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理结算域：结算期一到，按“实际已上刊流水”给在册代理逐点分账，落结算快照后冻结。
 *
 * 流水口径：当期【落架 TAKEN_DOWN】的授信原始上刊单（order_kind=POSTING、credit_used=1），
 *           归属期按落架时刻（closed_time）所在自然月固化；对冲更正单不形成流水，
 *           挂起未下架的单留待落架期结算，金额取报价含税总额快照（occupied_amount）。
 * 分账口径：按结算时点适用的协议版本（effective_from_period &lt;= 当期的最大版本）取比例，
 *           版本号与比例复制进结算行——之后调比例、换版与历史账单无关。
 * 对平口径：各方金额四舍五入到分；行尾差 = 流水 - 代理 - 平台原始，由平台方兜底吸收，
 *           结算头合计 totalCommission + totalPlatform = totalRevenue 分毫不差，roundingDiff 非零（差一分）也标出。
 * 冻结口径：(代理, 结算期) 唯一，已结算期不重算；批量结算幂等跳过已结代理。
 */
@Service
public class SettlementService {

    static final int MONEY_SCALE = 2;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentService agentService;
    private final AgentAgreementMapper agreementMapper;
    private final AgreementService agreementService;
    private final AgentSettlementMapper settlementMapper;
    private final AgentSettlementLineMapper lineMapper;
    private final PostingOrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    public SettlementService(AgentService agentService,
                             AgentAgreementMapper agreementMapper,
                             AgreementService agreementService,
                             AgentSettlementMapper settlementMapper,
                             AgentSettlementLineMapper lineMapper,
                             PostingOrderMapper orderMapper,
                             ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.agreementMapper = agreementMapper;
        this.agreementService = agreementService;
        this.settlementMapper = settlementMapper;
        this.lineMapper = lineMapper;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
    }

    /** 批量结算结果：新建的结算单 + 跳过（已结/无流水/停用）明细。 */
    public static class SettlementRunView {
        private String period;
        private List<AgentSettlement> created = new ArrayList<>();
        private List<Map<String, String>> skipped = new ArrayList<>();

        public String getPeriod() { return period; }
        public void setPeriod(String period) { this.period = period; }
        public List<AgentSettlement> getCreated() { return created; }
        public List<Map<String, String>> getSkipped() { return skipped; }
    }

    /**
     * 出结算：先把到期的协议调整版本转生效，再逐代理归集流水分账。
     * 已结算期不重算（已结代理幂等跳过）；代理视角无权出结算。
     */
    @Transactional(rollbackFor = Exception.class)
    public SettlementRunView settle(String requestedPeriod, String requestedAgentNo,
                                    String operator, AgentViewer viewer) {
        viewer.requireManage();
        String period = requestedPeriod != null && !requestedPeriod.trim().isEmpty()
                ? Periods.requireValid(requestedPeriod.trim()) : Periods.current();
        if (Periods.compare(period, Periods.current()) > 0) {
            throw new BusinessException("不能对未来结算期出结算：" + period + "，当前结算期 " + Periods.current());
        }
        String op = viewer.getUsername();

        // 中途调整版本到点转生效，当期即按新比例/终止口径
        agreementService.activateDueVersions(period);

        List<Agent> targets;
        if (requestedAgentNo != null && !requestedAgentNo.trim().isEmpty()) {
            Agent only = agentService.requireByNo(requestedAgentNo.trim());
            if (!AgentEnums.AGENT_ACTIVE.equals(only.getStatus())) {
                throw new BusinessException("代理「" + only.getAgentName() + "」已停用，不参与新结算；历史账单照翻");
            }
            targets = new ArrayList<>();
            targets.add(only);
        } else {
            targets = agentService.list(viewer, null, AgentEnums.AGENT_ACTIVE);
        }

        SettlementRunView run = new SettlementRunView();
        run.setPeriod(period);
        for (Agent agent : targets) {
            AgentSettlement existing = findSettlement(agent.getId(), period);
            if (existing != null) {
                run.getSkipped().add(skip(agent, "该代理 " + period + " 期已结算（结算单 "
                        + existing.getSettlementNo() + "），历史期不重算"));
                continue;
            }
            AgentSettlement settlement = settleOne(agent, period, op);
            if (settlement == null) {
                run.getSkipped().add(skip(agent, period + " 期无实际已上刊流水，不生成结算单"));
            } else {
                run.getCreated().add(settlement);
            }
        }
        return run;
    }

    /** 给单个代理某期落结算快照；无流水返回 null。 */
    private AgentSettlement settleOne(Agent agent, String period, String operator) {
        List<AgentAgreement> agreements = agreementMapper.selectList(new LambdaQueryWrapper<AgentAgreement>()
                .eq(AgentAgreement::getAgentId, agent.getId()));

        List<AgentSettlementLine> lines = new ArrayList<>();
        for (AgentAgreement agreement : agreements) {
            // 该期适用的协议版本：未到起始期或该期已终止 → null，不分账
            AgentAgreementVersion rateVersion = agreementService.rateVersionForPeriod(agreement, period);
            if (rateVersion == null) {
                continue;
            }
            for (PostingOrder order : revenueOrders(agreement.getSiteId(), period)) {
                if (isSettled(order.getId())) {
                    // 流水已进过别的结算单（正常不会发生：同一点位同期只许一个在册代理），跳过防重复分账
                    continue;
                }
                lines.add(buildLine(agent, agreement, rateVersion, order, period));
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        lines.sort((a, b) -> a.getOrderNo().compareTo(b.getOrderNo()));

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalPlatformRaw = BigDecimal.ZERO;
        for (AgentSettlementLine l : lines) {
            totalRevenue = totalRevenue.add(l.getRevenueAmount());
            totalCommission = totalCommission.add(l.getCommissionAmount());
            totalPlatformRaw = totalPlatformRaw.add(l.getPlatformAmountRaw());
        }
        // 平台吸收全部行尾差后兜底对平：totalPlatform = totalRevenue - totalCommission
        BigDecimal roundingDiff = totalRevenue.subtract(totalCommission).subtract(totalPlatformRaw);
        BigDecimal totalPlatform = totalPlatformRaw.add(roundingDiff);
        boolean balanced = totalCommission.add(totalPlatform).compareTo(totalRevenue) == 0;

        AgentSettlement settlement = new AgentSettlement();
        settlement.setSettlementNo(generateSettlementNo());
        settlement.setAgentId(agent.getId());
        settlement.setAgentNo(agent.getAgentNo());
        settlement.setAgentNameSnapshot(agent.getAgentName());
        settlement.setBizPeriod(period);
        settlement.setStatus(AgentEnums.SETTLEMENT_SETTLED);
        settlement.setOrderCount(lines.size());
        settlement.setTotalRevenue(totalRevenue);
        settlement.setTotalCommission(totalCommission);
        settlement.setTotalPlatformRaw(totalPlatformRaw);
        settlement.setTotalPlatform(totalPlatform);
        settlement.setRoundingDiff(roundingDiff);
        settlement.setTailParty(AgentEnums.TAIL_PARTY_PLATFORM);
        settlement.setBalanced(balanced ? 1 : 0);
        settlement.setSettleTime(LocalDateTime.now());
        settlement.setOperatorName(operator);
        try {
            settlementMapper.insert(settlement);
        } catch (DuplicateKeyException e) {
            // 并发出结算：(代理, 期) 唯一兜底，后到者不重算
            throw new BusinessException("代理「" + agent.getAgentName() + "」" + period
                    + " 期已在结算中/已结算，历史期不重算");
        }

        for (AgentSettlementLine line : lines) {
            line.setSettlementId(settlement.getId());
            try {
                lineMapper.insert(line);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("流水单 " + line.getOrderNo() + " 已进过结算单，一张流水只许分账一次");
            }
        }
        settlement.setSnapshot(toSnapshot(settlement, lines));
        settlementMapper.updateById(settlement);
        return settlement;
    }

    /** 一行流水分账：代理与平台各自四舍五入到分，尾差由平台行吸收对平。 */
    private AgentSettlementLine buildLine(Agent agent, AgentAgreement agreement,
                                          AgentAgreementVersion rateVersion, PostingOrder order, String period) {
        BigDecimal revenue = money(order.getOccupiedAmount());
        BigDecimal commission = revenue.multiply(rateVersion.getCommissionRate())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal platformRate = BigDecimal.ONE.subtract(rateVersion.getCommissionRate());
        BigDecimal platformRaw = revenue.multiply(platformRate)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal lineDiff = revenue.subtract(commission).subtract(platformRaw);
        BigDecimal platform = platformRaw.add(lineDiff);

        AgentSettlementLine line = new AgentSettlementLine();
        line.setAgentId(agent.getId());
        line.setOrderId(order.getId());
        line.setOrderNo(order.getOrderNo());
        line.setSiteId(order.getSiteId());
        line.setSiteNo(agreement.getSiteNo());
        line.setCreativeNameSnapshot(order.getCreativeName());
        line.setBizPeriod(period);
        line.setAgreementId(agreement.getId());
        line.setAgreementVersionId(rateVersion.getId());
        line.setAgreementVersionNo(rateVersion.getVersionNo());
        line.setCommissionRate(rateVersion.getCommissionRate());
        line.setRevenueAmount(revenue);
        line.setCommissionAmount(commission);
        line.setPlatformAmountRaw(platformRaw);
        line.setPlatformAmount(platform);
        line.setLineRoundingDiff(lineDiff);
        return line;
    }

    /** 当期落架的授信原始上刊单：归属期按 closed_time 所在自然月落半开区间 [月初, 下月初)。 */
    private List<PostingOrder> revenueOrders(Long siteId, String period) {
        YearMonth ym = YearMonth.parse(period, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();
        return orderMapper.selectList(new LambdaQueryWrapper<PostingOrder>()
                .eq(PostingOrder::getSiteId, siteId)
                .eq(PostingOrder::getOrderKind, LedgerEnums.KIND_POSTING)
                .eq(PostingOrder::getStatus, LedgerEnums.ORDER_TAKEN_DOWN)
                .eq(PostingOrder::getCreditUsed, 1)
                .ge(PostingOrder::getClosedTime, from)
                .lt(PostingOrder::getClosedTime, to)
                .orderByAsc(PostingOrder::getId));
    }

    private boolean isSettled(Long orderId) {
        Long count = lineMapper.selectCount(new LambdaQueryWrapper<AgentSettlementLine>()
                .eq(AgentSettlementLine::getOrderId, orderId));
        return count != null && count > 0;
    }

    private AgentSettlement findSettlement(Long agentId, String period) {
        return settlementMapper.selectOne(new LambdaQueryWrapper<AgentSettlement>()
                .eq(AgentSettlement::getAgentId, agentId)
                .eq(AgentSettlement::getBizPeriod, period));
    }

    /** 结算单翻查：代理只见自己；财务/系统可按期/代理过滤。 */
    public List<AgentSettlement> list(AgentViewer viewer, String period, String agentNo) {
        LambdaQueryWrapper<AgentSettlement> wrapper = new LambdaQueryWrapper<>();
        if (viewer.isScoped()) {
            List<Long> own = agentService.list(viewer, null, null).stream()
                    .map(Agent::getId).collect(java.util.stream.Collectors.toList());
            if (own.isEmpty()) {
                wrapper.apply("1 = 0");
            } else {
                wrapper.in(AgentSettlement::getAgentId, own);
            }
        }
        if (agentNo != null && !agentNo.trim().isEmpty()) {
            Agent agent = agentService.requireViewable(agentNo.trim(), viewer);
            wrapper.eq(AgentSettlement::getAgentId, agent.getId());
        }
        if (period != null && !period.trim().isEmpty()) {
            wrapper.eq(AgentSettlement::getBizPeriod, Periods.requireValid(period.trim()));
        }
        return settlementMapper.selectList(wrapper.orderByDesc(AgentSettlement::getId));
    }

    /** 结算单详情（头 + 逐笔分账行），代理只能看自己。 */
    public SettlementDetailView detail(String settlementNo, AgentViewer viewer) {
        AgentSettlement settlement = settlementMapper.selectOne(new LambdaQueryWrapper<AgentSettlement>()
                .eq(AgentSettlement::getSettlementNo, settlementNo.trim()));
        if (settlement == null) {
            throw new BusinessException("结算单不存在: " + settlementNo);
        }
        Agent agent = agentService.requireById(settlement.getAgentId());
        viewer.checkOwn(agent.getLoginUsername());
        SettlementDetailView view = new SettlementDetailView();
        view.setSettlement(settlement);
        view.setLines(lineMapper.selectList(new LambdaQueryWrapper<AgentSettlementLine>()
                .eq(AgentSettlementLine::getSettlementId, settlement.getId())
                .orderByAsc(AgentSettlementLine::getId)));
        return view;
    }

    /** 结算明细 CSV（BOM）：代理导出只含自己；财务可按期导出全部代理。 */
    public String settlementsCsv(String period, AgentViewer viewer) {
        List<AgentSettlement> headers = list(viewer, period, null);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("结算期,结算单号,代理编号,代理名称,流水笔数,流水总额,代理分账合计,平台分账(原始),尾差,平台分账(兜底后),是否对平,结算时间\n");
        for (AgentSettlement s : headers) {
            csv.append(esc(s.getBizPeriod())).append(',')
                    .append(esc(s.getSettlementNo())).append(',')
                    .append(esc(s.getAgentNo())).append(',')
                    .append(esc(s.getAgentNameSnapshot())).append(',')
                    .append(s.getOrderCount()).append(',')
                    .append(s.getTotalRevenue().toPlainString()).append(',')
                    .append(s.getTotalCommission().toPlainString()).append(',')
                    .append(s.getTotalPlatformRaw().toPlainString()).append(',')
                    .append(s.getRoundingDiff().toPlainString()).append(',')
                    .append(s.getTotalPlatform().toPlainString()).append(',')
                    .append(s.getBalanced() != null && s.getBalanced() == 1 ? "对平" : "未对平").append(',')
                    .append(s.getSettleTime() == null ? "" : s.getSettleTime().format(TS))
                    .append('\n');
        }
        return csv.toString();
    }

    /** 单张结算单的逐笔分账行 CSV（代理只能导自己）。 */
    public String linesCsv(String settlementNo, AgentViewer viewer) {
        SettlementDetailView view = detail(settlementNo, viewer);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("结算期,结算单号,代理编号,上刊单号,点位,画面,协议版本,分账比例,流水金额,代理分账,平台分账(原始),行尾差,平台分账(兜底后)\n");
        AgentSettlement s = view.getSettlement();
        for (AgentSettlementLine l : view.getLines()) {
            csv.append(esc(s.getBizPeriod())).append(',')
                    .append(esc(s.getSettlementNo())).append(',')
                    .append(esc(s.getAgentNo())).append(',')
                    .append(esc(l.getOrderNo())).append(',')
                    .append(esc(l.getSiteNo())).append(',')
                    .append(esc(l.getCreativeNameSnapshot())).append(',')
                    .append("v").append(l.getAgreementVersionNo()).append(',')
                    .append(l.getCommissionRate().toPlainString()).append(',')
                    .append(l.getRevenueAmount().toPlainString()).append(',')
                    .append(l.getCommissionAmount().toPlainString()).append(',')
                    .append(l.getPlatformAmountRaw().toPlainString()).append(',')
                    .append(l.getLineRoundingDiff().toPlainString()).append(',')
                    .append(l.getPlatformAmount().toPlainString())
                    .append('\n');
        }
        return csv.toString();
    }

    private static Map<String, String> skip(Agent agent, String reason) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("agentNo", agent.getAgentNo());
        m.put("agentName", agent.getAgentName());
        m.put("reason", reason);
        return m;
    }

    private static BigDecimal money(BigDecimal v) {
        if (v == null || v.signum() < 0) {
            throw new BusinessException("流水金额异常（空或为负），无法分账");
        }
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private String toSnapshot(AgentSettlement settlement, List<AgentSettlementLine> lines) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("settlement", settlement);
        snap.put("lines", lines);
        try {
            return objectMapper.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("结算快照写入失败", e);
        }
    }

    private String generateSettlementNo() {
        long seq = settlementMapper.nextSettlementSeq();
        return String.format("ST%010d", seq);
    }

    static String esc(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
