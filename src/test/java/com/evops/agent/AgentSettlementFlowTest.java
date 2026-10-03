package com.evops.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.agent.dto.AgentCreateRequest;
import com.evops.agent.dto.AgreementAdjustRequest;
import com.evops.agent.dto.AgreementCreateRequest;
import com.evops.agent.dto.AgreementTerminateRequest;
import com.evops.agent.dto.SettlementDetailView;
import com.evops.agent.entity.Agent;
import com.evops.agent.entity.AgentAgreement;
import com.evops.agent.entity.AgentAgreementVersion;
import com.evops.agent.entity.AgentSettlement;
import com.evops.agent.entity.AgentSettlementLine;
import com.evops.agent.service.AgentService;
import com.evops.agent.service.AgreementService;
import com.evops.agent.service.SettlementService;
import com.evops.agent.support.Periods;
import com.evops.common.AgentViewer;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.entity.Customer;
import com.evops.credit.service.CustomerService;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.SiteService;
import com.evops.ratecard.dto.QuoteLineRequest;
import com.evops.ratecard.dto.QuoteSubmitRequest;
import com.evops.ratecard.dto.RateCardCreateRequest;
import com.evops.ratecard.dto.SitePriceSpec;
import com.evops.ratecard.dto.TierSpec;
import com.evops.ratecard.dto.VersionActionRequest;
import com.evops.ratecard.dto.VersionConfigureRequest;
import com.evops.ratecard.entity.RateCard;
import com.evops.ratecard.entity.RateCardSite;
import com.evops.ratecard.mapper.RateCardMapper;
import com.evops.ratecard.mapper.RateCardSiteMapper;
import com.evops.ratecard.service.QuoteService;
import com.evops.ratecard.service.RateCardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F9 代理协议与结算快照端到端（真实 H2 + 真实事务，无 Shiro，用显式 AgentViewer 验证隔离）。
 * 流水链路：客户授信 → 刊例（税率 0，金额干净）→ 单点单周报价 → 授信上刊 → 当期落架。
 */
@SpringBootTest
class AgentSettlementFlowTest {

    @Autowired private AgentService agentService;
    @Autowired private AgreementService agreementService;
    @Autowired private SettlementService settlementService;
    @Autowired private SiteService siteService;
    @Autowired private RateCardService rateCardService;
    @Autowired private QuoteService quoteService;
    @Autowired private RateCardMapper rateCardMapper;
    @Autowired private RateCardSiteMapper rateCardSiteMapper;
    @Autowired private CustomerService customerService;
    @Autowired private OrderService orderService;

    private final AgentViewer finance = AgentViewer.all("finance1");

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }
    /** 代理登录账号也要全局唯一（库在同类多方法间共享），用唯一名，视角按它派生。 */
    private String uniqueLogin() {
        return "agt" + System.nanoTime() + seq;
    }

    /**
     * F9 全部点位共用一张刊例（整类只占一个年份）。JUnit5 每个测试方法新建实例，
     * 故刊例坐标用 static 跨方法共享；每次用时重新查库拿最新版本指针。
     */
    private static volatile Long sharedCardId;
    private static int sharedYear;

    private synchronized RateCard sharedCard() {
        if (sharedCardId == null) {
            int thisYear = java.time.LocalDate.now().getYear();
            int year = -1;
            for (int y = 1990; y <= thisYear; y++) {
                Long count = rateCardMapper.selectCount(new LambdaQueryWrapper<RateCard>()
                        .eq(RateCard::getRateYear, y));
                if (count == null || count == 0) {
                    year = y;
                    break;
                }
            }
            if (year < 0) {
                throw new IllegalStateException("没有可用的历史年份来建测试刊例");
            }
            RateCardCreateRequest cr = new RateCardCreateRequest();
            cr.setRateYear(year);
            cr.setCardName("F9 代理结算共用刊例");
            cr.setOperatorName("tester");
            RateCard created = rateCardService.createCard(cr);
            sharedCardId = created.getId();
            sharedYear = year;
        }
        return rateCardMapper.selectById(sharedCardId);
    }

    /** 一份造数夹具：在营点位 + 挂到 F9 共用刊例上（税率 0、不打折）。 */
    static class Fixture {
        Site site;
        int year;
    }

    // ---- 造数 ----

    private Agent createAgent() {
        AgentCreateRequest req = new AgentCreateRequest();
        req.setAgentName(uniqueNo("代理"));
        req.setLoginUsername(uniqueLogin());
        req.setContactName("联系人");
        return agentService.create(req, finance);
    }

    /**
     * 建在营点位，并在 F9 共用刊例上「另起新版本（自动拷贝历史点位价）+ 给新点位挂价 + 发布」。
     * 这样 17 个点位只需一个刊例年份，且报价始终按当前生效版本计价。
     */
    private Fixture setupSiteWithPrice(String price) {
        RateCard card = sharedCard();
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("AG-SITE"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝北");
        sr.setBusinessDistrict("代理测试商圈");
        sr.setOperatorName("tester");
        Site site = siteService.register(sr);

        VersionActionRequest action = new VersionActionRequest();
        action.setOperatorName("tester");
        com.evops.ratecard.entity.RateCardVersion draft;
        if (card.getCurrentVersionId() == null) {
            // 首版：建档自带 v1 草稿，配一档不打折 + 本点位价
            draft = rateCardService.listVersions(card.getId()).get(0);
            VersionConfigureRequest cfg = new VersionConfigureRequest();
            cfg.setTaxRate(new BigDecimal("0.0000"));
            TierSpec tier = new TierSpec();
            tier.setMinWeeks(1);
            tier.setMaxWeeks(null);
            tier.setDiscountRate(new BigDecimal("1.0000"));
            SitePriceSpec sp = new SitePriceSpec();
            sp.setSiteNo(site.getSiteNo());
            sp.setListPrice(new BigDecimal(price));
            cfg.setTiers(new ArrayList<>(java.util.Arrays.asList(tier)));
            cfg.setSitePrices(new ArrayList<>(java.util.Arrays.asList(sp)));
            cfg.setOperatorName("tester");
            rateCardService.configure(draft.getId(), cfg);
        } else {
            // 后续点位：另起新版本（拷贝上一版档位与点位价），直接给新版本补一条本点位价
            draft = rateCardService.newVersion(card.getId(), action);
            RateCardSite rcs = new RateCardSite();
            rcs.setVersionId(draft.getId());
            rcs.setSiteId(site.getId());
            rcs.setListPrice(new BigDecimal(price));
            rateCardSiteMapper.insert(rcs);
        }
        rateCardService.publish(draft.getId(), action);

        Fixture f = new Fixture();
        f.site = site;
        f.year = sharedYear;
        return f;
    }

    /** 挂协议：代理在点位、比例 rate、当期生效（默认）。 */
    private AgentAgreement attach(Agent agent, Fixture f, String rate) {
        AgreementCreateRequest req = new AgreementCreateRequest();
        req.setAgentNo(agent.getAgentNo());
        req.setSiteNo(f.site.getSiteNo());
        req.setCommissionRate(new BigDecimal(rate));
        req.setOperatorName("finance1");
        return agreementService.create(req, finance);
    }

    private Customer createCustomer() {
        CustomerCreateRequest req = new CustomerCreateRequest();
        req.setCustomerName(uniqueNo("代理客户"));
        req.setIndustryCategory("快消品");
        req.setCreditLimit(new BigDecimal("9999999.00"));
        return customerService.create(req, Viewer.finance("finance1"));
    }

    private String quoteOneLine(Fixture f, Customer customer) {
        QuoteLineRequest line = new QuoteLineRequest();
        line.setSiteNo(f.site.getSiteNo());
        line.setWeeks(1);
        QuoteSubmitRequest q = new QuoteSubmitRequest();
        q.setCustomerName(customer.getCustomerName());
        q.setSubmitDate(LocalDate.of(f.year, 6, 1));
        q.setLines(new ArrayList<>(Arrays.asList(line)));
        q.setOperatorName("finance1");
        return quoteService.submit(q).getQuote().getQuoteNo();
    }

    /** 授信上刊。 */
    private PostingOrder post(Fixture f, Customer customer, String creative) {
        OrderCreateRequest posting = new OrderCreateRequest();
        posting.setSiteNo(f.site.getSiteNo());
        posting.setCreativeName(creative);
        posting.setQuoteNo(quoteOneLine(f, customer));
        posting.setOccurDate(LocalDate.of(f.year, 6, 2));
        posting.setOperatorName("finance1");
        return orderService.createPosting(posting);
    }

    /** 授信上刊 → 当期落架，返回落架单。 */
    private PostingOrder postAndTakeDown(Fixture f, Customer customer) {
        PostingOrder order = post(f, customer, "代理流水画面");
        ActionRequest takeDown = new ActionRequest();
        takeDown.setOperatorName("finance1");
        // 落架即当期（closed_time=现在），流水归属结算期按落架月
        return orderService.takeDown(order.getOrderNo(), takeDown);
    }

    private AgentSettlement settleCurrent(Agent agent) {
        SettlementService.SettlementRunView run = settlementService.settle(
                Periods.current(), agent.getAgentNo(), "finance1", finance);
        assertEquals(1, run.getCreated().size(), "应生成一张结算单");
        return run.getCreated().get(0);
    }

    // ---- 协议建档 / 版本 / 状态 ----

    @Test
    void 点位挂协议_比例与有效期定清楚_自动v1当期生效() {
        Agent agent = createAgent();
        Fixture f = setupSiteWithPrice("100.00");
        AgentAgreement agreement = attach(agent, f, "0.1500");

        assertEquals("ACTIVE", agreement.getStatus());
        assertEquals(1, agreement.getCurrentVersionNo());
        assertEquals(Periods.current(), agreement.getEffectiveFromPeriod());

        List<AgentAgreementVersion> versions = agreementService.listVersions(agreement.getAgreementNo(), finance);
        assertEquals(1, versions.size());
        AgentAgreementVersion v1 = versions.get(0);
        assertEquals("INITIAL", v1.getChangeType());
        assertEquals("EFFECTIVE", v1.getStatus());
        assertEquals("0.1500", v1.getCommissionRate().toPlainString());
        assertNotNull(v1.getEffectiveTime());
    }

    @Test
    void 协议起始期可指定未来期_未到期为待生效_到期转生效() {
        Agent agent = createAgent();
        Fixture f = setupSiteWithPrice("100.00");
        String future = Periods.next(Periods.current());

        AgreementCreateRequest req = new AgreementCreateRequest();
        req.setAgentNo(agent.getAgentNo());
        req.setSiteNo(f.site.getSiteNo());
        req.setCommissionRate(new BigDecimal("0.2000"));
        req.setEffectiveFromPeriod(future);
        req.setOperatorName("finance1");
        AgentAgreement agreement = agreementService.create(req, finance);

        assertNull(agreement.getCurrentVersionId());
        assertNotNull(agreement.getPendingVersionId());
        assertNull(agreementService.rateVersionForPeriod(agreement, Periods.current()));

        agreementService.activateDueVersions(future);
        AgentAgreement after = agreementService.requireByNo(agreement.getAgreementNo());
        assertEquals(1, after.getCurrentVersionNo());
        assertEquals(future, after.getEffectiveFromPeriod());
        assertNotNull(agreementService.rateVersionForPeriod(after, future));
    }

    @Test
    void 建档校验_过去期拒收_比例越界拒收_同一代理同点位重复挂拒收() {
        Agent agent = createAgent();
        Fixture f = setupSiteWithPrice("100.00");
        attach(agent, f, "0.1000");

        AgreementCreateRequest past = new AgreementCreateRequest();
        past.setAgentNo(agent.getAgentNo());
        past.setSiteNo(setupSiteWithPrice("100.00").site.getSiteNo());
        past.setCommissionRate(new BigDecimal("0.1000"));
        past.setEffectiveFromPeriod("2000-01");
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> agreementService.create(past, finance));
        assertTrue(e1.getMessage().contains("不能早于当前结算期"));

        AgreementCreateRequest badRate = new AgreementCreateRequest();
        badRate.setAgentNo(agent.getAgentNo());
        badRate.setSiteNo(setupSiteWithPrice("100.00").site.getSiteNo());
        badRate.setCommissionRate(new BigDecimal("1.2000"));
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> agreementService.create(badRate, finance));
        assertTrue(e2.getMessage().contains("0~1"));

        AgreementCreateRequest dup = new AgreementCreateRequest();
        dup.setAgentNo(agent.getAgentNo());
        dup.setSiteNo(f.site.getSiteNo());
        dup.setCommissionRate(new BigDecimal("0.1000"));
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> agreementService.create(dup, finance));
        assertTrue(e3.getMessage().contains("已挂过协议"));
    }

    @Test
    void 中途调比例从下期生效_历史期不重算_旧版SUPERSEDED() {
        Agent agent = createAgent();
        Fixture f = setupSiteWithPrice("100.00");
        AgentAgreement agreement = attach(agent, f, "0.1000");
        String current = Periods.current();
        String next = Periods.next(current);

        AgreementAdjustRequest adj = new AgreementAdjustRequest();
        adj.setCommissionRate(new BigDecimal("0.2000"));
        adj.setReason("旺季提比例");
        AgentAgreementVersion v2 = agreementService.adjust(agreement.getAgreementNo(), adj, finance);
        assertEquals("PENDING", v2.getStatus());
        assertEquals(next, v2.getEffectiveFromPeriod());

        // 当期仍按 v1，下期才是 v2
        assertEquals(1, agreementService.rateVersionForPeriod(agreement, current).getVersionNo());
        assertEquals(2, agreementService.rateVersionForPeriod(agreement, next).getVersionNo());

        // 不能当期生效/追溯
        AgreementAdjustRequest tooEarly = new AgreementAdjustRequest();
        tooEarly.setCommissionRate(new BigDecimal("0.3000"));
        tooEarly.setEffectiveFromPeriod(current);
        BusinessException e = assertThrows(BusinessException.class,
                () -> agreementService.adjust(agreement.getAgreementNo(), tooEarly, finance));
        assertTrue(e.getMessage().contains("下一结算期"), e.getMessage());

        // 有待生效版时不能再调
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> agreementService.adjust(agreement.getAgreementNo(), adj, finance));
        assertTrue(e2.getMessage().contains("待生效"));

        // 下期到期：v2 生效，v1 置 SUPERSEDED
        agreementService.activateDueVersions(next);
        List<AgentAgreementVersion> versions = agreementService.listVersions(agreement.getAgreementNo(), finance);
        assertEquals("EFFECTIVE", versions.get(0).getStatus()); // 倒序 v2 在前
        assertEquals("SUPERSEDED", versions.get(1).getStatus());
        AgentAgreement after = agreementService.requireByNo(agreement.getAgreementNo());
        assertEquals(2, after.getCurrentVersionNo());
    }

    @Test
    void 终止从下期生效_当期仍结算_终止期起不分账() {
        Agent agent = createAgent();
        Fixture f = setupSiteWithPrice("100.00");
        AgentAgreement agreement = attach(agent, f, "0.1000");
        String current = Periods.current();
        String next = Periods.next(current);

        AgreementTerminateRequest term = new AgreementTerminateRequest();
        term.setReason("合作到期");
        AgentAgreementVersion tv = agreementService.terminate(agreement.getAgreementNo(), term, finance);
        assertEquals(next, tv.getEffectiveFromPeriod());

        assertNotNull(agreementService.rateVersionForPeriod(agreement, current));
        assertNull(agreementService.rateVersionForPeriod(agreement, next));

        agreementService.activateDueVersions(next);
        assertEquals("TERMINATED", agreementService.requireByNo(agreement.getAgreementNo()).getStatus());
    }

    // ---- 结算：流水口径 / 四舍五入 / 对平 / 冻结 ----

    @Test
    void 结算按实际落架流水_四舍五入到分_尾差标出且平台兜底对平() {
        Agent agent = createAgent();
        Customer customer = createCustomer();
        // 流水 10.00，比例 0.1235：代理 1.235→1.24，平台原始 8.765→8.77，行尾差 -0.01
        Fixture f = setupSiteWithPrice("10.00");
        attach(agent, f, "0.1235");
        postAndTakeDown(f, customer);

        AgentSettlement s = settleCurrent(agent);
        assertEquals(1, s.getOrderCount());
        assertEquals("10.00", s.getTotalRevenue().toPlainString());
        assertEquals("1.24", s.getTotalCommission().toPlainString());
        assertEquals("8.77", s.getTotalPlatformRaw().toPlainString());
        assertEquals("-0.01", s.getRoundingDiff().toPlainString());
        assertEquals("8.76", s.getTotalPlatform().toPlainString());
        assertEquals(1, s.getBalanced());
        assertEquals(0, s.getTotalCommission().add(s.getTotalPlatform()).compareTo(s.getTotalRevenue()));

        SettlementDetailView detail = settlementService.detail(s.getSettlementNo(), finance);
        AgentSettlementLine line = detail.getLines().get(0);
        assertEquals(1, line.getAgreementVersionNo());
        assertEquals("0.1235", line.getCommissionRate().toPlainString());
        assertEquals("-0.01", line.getLineRoundingDiff().toPlainString());
        assertEquals(0, line.getCommissionAmount().add(line.getPlatformAmount()).compareTo(line.getRevenueAmount()));
        assertNotNull(detail.getSettlement().getSnapshot());
    }

    @Test
    void 多笔流水各方合计与总额对平_挂起单不形成流水() {
        Agent agent = createAgent();
        Customer customer = createCustomer();
        Fixture f1 = setupSiteWithPrice("10.00");
        Fixture f2 = setupSiteWithPrice("10.00");
        attach(agent, f1, "0.1235"); // 每行尾差 -0.01
        attach(agent, f2, "0.1235");
        postAndTakeDown(f1, customer);
        postAndTakeDown(f2, customer);

        AgentSettlement s = settleCurrent(agent);
        assertEquals(2, s.getOrderCount());
        assertEquals("20.00", s.getTotalRevenue().toPlainString());
        assertEquals("2.48", s.getTotalCommission().toPlainString());
        assertEquals("17.54", s.getTotalPlatformRaw().toPlainString());
        assertEquals("-0.02", s.getRoundingDiff().toPlainString());
        assertEquals("17.52", s.getTotalPlatform().toPlainString());
        assertEquals(0, s.getTotalCommission().add(s.getTotalPlatform()).compareTo(s.getTotalRevenue()));

        // 挂起中（未落架）不进流水：再出当期结算，该代理已结被跳过，挂起单不另成账单
        post(setupSiteWithPrice("10.00"), customer, "挂起画面");
        SettlementService.SettlementRunView rerun = settlementService.settle(
                Periods.current(), agent.getAgentNo(), "finance1", finance);
        assertTrue(rerun.getCreated().isEmpty());
        assertEquals(1, rerun.getSkipped().size());
    }

    @Test
    void 已结算期冻结不重算_中途调比例不影响历史账单() {
        Agent agent = createAgent();
        Customer customer = createCustomer();
        Fixture f = setupSiteWithPrice("10.00");
        AgentAgreement agreement = attach(agent, f, "0.1000");
        postAndTakeDown(f, customer);
        AgentSettlement first = settleCurrent(agent);
        assertEquals("1.00", first.getTotalCommission().toPlainString());

        // 之后调比例（下期生效）
        AgreementAdjustRequest adj = new AgreementAdjustRequest();
        adj.setCommissionRate(new BigDecimal("0.5000"));
        agreementService.adjust(agreement.getAgreementNo(), adj, finance);

        // 历史账单金额、版本快照不变
        SettlementDetailView detail = settlementService.detail(first.getSettlementNo(), finance);
        assertEquals("1.00", detail.getSettlement().getTotalCommission().toPlainString());
        assertEquals("0.1000", detail.getLines().get(0).getCommissionRate().toPlainString());

        // 已结算期再出结算：幂等跳过，不新建、不重算
        SettlementService.SettlementRunView rerun = settlementService.settle(
                Periods.current(), agent.getAgentNo(), "finance1", finance);
        assertTrue(rerun.getCreated().isEmpty());
        assertEquals(1, rerun.getSkipped().size());
        assertTrue(rerun.getSkipped().get(0).get("reason").contains("历史期不重算"));
        assertEquals(first.getId(),
                settlementService.list(finance, Periods.current(), agent.getAgentNo()).get(0).getId());
    }

    @Test
    void 无流水不出结算单_未来期不能出结算() {
        Agent agent = createAgent();
        Fixture f = setupSiteWithPrice("100.00");
        attach(agent, f, "0.1000"); // 有协议无流水

        SettlementService.SettlementRunView run = settlementService.settle(
                Periods.current(), agent.getAgentNo(), "finance1", finance);
        assertTrue(run.getCreated().isEmpty());
        assertEquals(1, run.getSkipped().size());
        assertTrue(run.getSkipped().get(0).get("reason").contains("无实际已上刊流水"));

        BusinessException e = assertThrows(BusinessException.class, () -> settlementService.settle(
                Periods.next(Periods.current()), agent.getAgentNo(), "finance1", finance));
        assertTrue(e.getMessage().contains("未来结算期"));
    }

    // ---- 数据隔离 ----

    @Test
    void 代理只能查自己账单_不能挂协议调比例出结算() {
        Agent a1 = createAgent();
        Agent a2 = createAgent();
        AgentViewer mine = AgentViewer.agent(a1.getLoginUsername());
        AgentViewer other = AgentViewer.agent(a2.getLoginUsername());
        Customer c1 = createCustomer();
        Fixture f1 = setupSiteWithPrice("100.00");
        attach(a1, f1, "0.1000");
        postAndTakeDown(f1, c1);
        AgentSettlement st1 = settleCurrent(a1);

        // 别家代理看不到 a1 的账单、详情、CSV
        assertTrue(settlementService.list(other, Periods.current(), null).isEmpty());
        BusinessException e = assertThrows(BusinessException.class,
                () -> settlementService.detail(st1.getSettlementNo(), other));
        assertTrue(e.getMessage().contains("本代理名下账单"));

        // 本代理看得到自己
        assertEquals(1, settlementService.list(mine, Periods.current(), null).size());
        assertEquals(st1.getSettlementNo(),
                settlementService.detail(st1.getSettlementNo(), mine).getSettlement().getSettlementNo());

        // 代理不能管理协议 / 出结算
        AgreementCreateRequest req = new AgreementCreateRequest();
        req.setAgentNo(a1.getAgentNo());
        req.setSiteNo(setupSiteWithPrice("100.00").site.getSiteNo());
        req.setCommissionRate(new BigDecimal("0.1000"));
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> agreementService.create(req, mine));
        assertTrue(e2.getMessage().contains("只有财务/系统"));
        BusinessException e3 = assertThrows(BusinessException.class, () -> settlementService.settle(
                Periods.current(), null, a1.getLoginUsername(), mine));
        assertTrue(e3.getMessage().contains("只有财务/系统"));

        // 财务看全部
        assertEquals(1, settlementService.list(finance, Periods.current(), a1.getAgentNo()).size());
    }

    @Test
    void 停用代理不参与新结算_代理档案隔离() {
        Agent a1 = createAgent();
        Agent a2 = createAgent();
        AgentViewer mine = AgentViewer.agent(a1.getLoginUsername());
        AgentViewer other = AgentViewer.agent(a2.getLoginUsername());
        assertEquals(1, agentService.list(mine, null, null).size());
        // 别家代理视角看不到 a1（列表里只有它自己，且不是 a1）
        List<Agent> seenByOther = agentService.list(other, null, null);
        assertTrue(seenByOther.stream().noneMatch(a -> a.getId().equals(a1.getId())));

        agentService.changeStatus(a1.getAgentNo(), AgentEnums.AGENT_DISABLED, finance);
        BusinessException e = assertThrows(BusinessException.class,
                () -> settlementService.settle(Periods.current(), a1.getAgentNo(), "finance1", finance));
        assertTrue(e.getMessage().contains("已停用"));
        assertEquals("DISABLED", agentService.requireByNo(a1.getAgentNo()).getStatus());
    }

    @Test
    void 结算汇总与逐笔CSV导出_含对平与尾差列() {
        Agent agent = createAgent();
        Agent otherAgent = createAgent();
        AgentViewer other = AgentViewer.agent(otherAgent.getLoginUsername());
        Customer customer = createCustomer();
        Fixture f = setupSiteWithPrice("10.00");
        attach(agent, f, "0.1235");
        postAndTakeDown(f, customer);
        AgentSettlement s = settleCurrent(agent);

        String summary = settlementService.settlementsCsv(Periods.current(), finance);
        assertTrue(summary.contains("对平"));
        assertTrue(summary.contains("-0.01"), summary);
        assertTrue(summary.contains(agent.getAgentNo()));

        String lines = settlementService.linesCsv(s.getSettlementNo(), finance);
        assertTrue(lines.contains("行尾差"));
        assertTrue(lines.contains(s.getSettlementNo()));
        assertTrue(lines.contains("-0.01"), lines);
        BusinessException e = assertThrows(BusinessException.class,
                () -> settlementService.linesCsv(s.getSettlementNo(), other));
        assertTrue(e.getMessage().contains("本代理名下账单"));
    }
}
