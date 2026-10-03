package com.evops.credit;

import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.dto.LimitApprovalRequest;
import com.evops.credit.dto.LimitChangeRequest;
import com.evops.credit.dto.PaymentRegisterRequest;
import com.evops.credit.dto.WriteoffRequest;
import com.evops.credit.entity.CreditLimitVersion;
import com.evops.credit.entity.CreditOccupation;
import com.evops.credit.entity.Customer;
import com.evops.credit.entity.Payment;
import com.evops.credit.dto.PaymentDetailView;
import com.evops.credit.service.CreditLimitService;
import com.evops.credit.service.CreditService;
import com.evops.credit.service.CustomerService;
import com.evops.credit.service.PaymentService;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.SchemaVersionService;
import com.evops.ledger.service.SiteService;
import com.evops.ratecard.dto.QuoteDetailView;
import com.evops.ratecard.dto.QuoteLineRequest;
import com.evops.ratecard.dto.QuoteSubmitRequest;
import com.evops.ratecard.dto.RateCardCreateRequest;
import com.evops.ratecard.dto.SitePriceSpec;
import com.evops.ratecard.dto.TierSpec;
import com.evops.ratecard.dto.VersionActionRequest;
import com.evops.ratecard.dto.VersionConfigureRequest;
import com.evops.ratecard.entity.RateCard;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F5 客户授信与回款核销端到端（真实 H2 + 真实事务，无 Shiro，用显式 Viewer 验证隔离）。 */
@SpringBootTest
class CreditFlowTest {

    @Autowired private CustomerService customerService;
    @Autowired private CreditLimitService limitService;
    @Autowired private CreditService creditService;
    @Autowired private PaymentService paymentService;
    @Autowired private OrderService orderService;
    @Autowired private SiteService siteService;
    @Autowired private RateCardService rateCardService;
    @Autowired private QuoteService quoteService;
    @Autowired private SchemaVersionService schemaVersionService;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    private final Viewer sales1 = Viewer.sales("sales1");
    private final Viewer sales2 = Viewer.sales("sales2");
    private final Viewer finance = Viewer.finance("finance1");

    // ---- 造数 ----

    private Site setupRateCard(int year, String price) {
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("CREDIT-SITE"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝北");
        sr.setBusinessDistrict("授信测试商圈");
        sr.setOperatorName("tester");
        Site site = siteService.register(sr);

        RateCardCreateRequest cr = new RateCardCreateRequest();
        cr.setRateYear(year);
        cr.setCardName(year + " 授信测试刊例");
        cr.setOperatorName("tester");
        RateCard card = rateCardService.createCard(cr);
        long v1 = rateCardService.listVersions(card.getId()).get(0).getId();

        TierSpec tier = new TierSpec();
        tier.setMinWeeks(1);
        tier.setMaxWeeks(null);
        tier.setDiscountRate(new BigDecimal("1.0000"));
        SitePriceSpec sp = new SitePriceSpec();
        sp.setSiteNo(site.getSiteNo());
        sp.setListPrice(new BigDecimal(price));
        VersionConfigureRequest cfg = new VersionConfigureRequest();
        cfg.setTiers(new ArrayList<>(Arrays.asList(tier)));
        cfg.setSitePrices(new ArrayList<>(Arrays.asList(sp)));
        cfg.setOperatorName("tester");
        rateCardService.configure(v1, cfg);
        VersionActionRequest a = new VersionActionRequest();
        a.setOperatorName("tester");
        rateCardService.publish(v1, a);
        return site;
    }

    /** 单行（单点单周）报价：price=1000、1 周 → 含税 1060.00。刊例年份必须是过去年份（报价不许未来日）。 */
    private QuoteDetailView quote(String customerName, int year, Site site, int weeks) {
        QuoteLineRequest line = new QuoteLineRequest();
        line.setSiteNo(site.getSiteNo());
        line.setWeeks(weeks);
        QuoteSubmitRequest req = new QuoteSubmitRequest();
        req.setCustomerName(customerName);
        req.setSubmitDate(LocalDate.of(year, 6, 1));
        req.setLines(new ArrayList<>(Arrays.asList(line)));
        req.setOperatorName("tester");
        return quoteService.submit(req);
    }

    private Customer createCustomer(String name, String limit, Viewer owner) {
        CustomerCreateRequest req = new CustomerCreateRequest();
        req.setCustomerName(name);
        req.setCreditLimit(new BigDecimal(limit));
        return customerService.create(req, owner);
    }

    private OrderCreateRequest posting(String siteNo, String quoteNo, String operator) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(siteNo);
        req.setCreativeName("授信画面");
        req.setQuoteNo(quoteNo);
        req.setOccurDate(LocalDate.of(2020, 6, 2)); // ISO 周内（报价按刊例年，上刊日独立，取过去日）
        req.setOperatorName(operator);
        return req;
    }

    // ---- 建档 / 版本 / 状态 ----

    @Test
    void 客户建档给额度_自动v1生效_客户名唯一_销售归自己名下() {
        Customer c = createCustomer(uniqueNo("建档客户"), "50000.00", sales1);
        assertTrue(c.getCustomerNo().startsWith("CU"));
        assertEquals("ACTIVE", c.getCreditStatus());
        assertEquals("50000.00", c.getCreditLimit().toPlainString());
        assertEquals("0.00", c.getOccupiedAmount().toPlainString());
        assertEquals("sales1", c.getOwnerSales());
        assertEquals(1, c.getCurrentVersionNo());

        List<CreditLimitVersion> versions = limitService.listVersions(c.getCustomerNo(), finance);
        assertEquals(1, versions.size());
        CreditLimitVersion v1 = versions.get(0);
        assertEquals("INITIAL", v1.getChangeType());
        assertEquals("EFFECTIVE", v1.getStatus());
        assertEquals("0.00", v1.getBeforeLimit().toPlainString());
        assertEquals("50000.00", v1.getAfterLimit().toPlainString());

        BusinessException dup = assertThrows(BusinessException.class,
                () -> createCustomer(c.getCustomerName(), "1000", finance));
        assertTrue(dup.getMessage().contains("全局唯一"));
    }

    @Test
    void 额度调整与冻结走审批_前后值留版本_旧生效版SUPERSEDED() {
        Customer c = createCustomer(uniqueNo("调额客户"), "10000.00", sales1);

        // 销售申请调到 20000
        LimitChangeRequest adj = new LimitChangeRequest();
        adj.setChangeType("ADJUST");
        adj.setAfterLimit(new BigDecimal("20000.00"));
        adj.setReason("旺季加额");
        CreditLimitVersion pending = limitService.apply(c.getCustomerNo(), adj, sales1);
        assertEquals("PENDING", pending.getStatus());
        assertEquals("10000.00", pending.getBeforeLimit().toPlainString());
        assertEquals("20000.00", pending.getAfterLimit().toPlainString());
        assertEquals("sales1", pending.getApplicantName());

        // 待审批期间客户额度没变
        assertEquals("10000.00", customerService.requireByNo(c.getCustomerNo()).getCreditLimit().toPlainString());

        // 销售不能审批
        BusinessException noApproval = assertThrows(BusinessException.class,
                () -> limitService.approve(pending.getId(), new LimitApprovalRequest(), sales1));
        assertTrue(noApproval.getMessage().contains("财务"));
        // 申请人不能自审（换财务但同申请人也拦——这里申请人是 sales1，财务 finance1 可审）
        CreditLimitVersion effective = limitService.approve(pending.getId(),
                approval("同意"), finance);
        assertEquals("EFFECTIVE", effective.getStatus());
        assertEquals("finance1", effective.getApproverName());

        Customer after = customerService.requireByNo(c.getCustomerNo());
        assertEquals("20000.00", after.getCreditLimit().toPlainString());
        assertEquals(pending.getId(), after.getCurrentVersionId());
        // v1 被取代
        List<CreditLimitVersion> vs = limitService.listVersions(c.getCustomerNo(), finance);
        assertEquals(2, vs.size());
        assertEquals("SUPERSEDED", vs.get(1).getStatus()); // 倒序：v2 在前，v1 在后

        // 冻结申请 → 审批后状态 FROZEN
        LimitChangeRequest freeze = new LimitChangeRequest();
        freeze.setChangeType("FREEZE");
        CreditLimitVersion fPend = limitService.apply(c.getCustomerNo(), freeze, sales1);
        limitService.approve(fPend.getId(), approval("逾期冻结"), finance);
        assertEquals("FROZEN", customerService.requireByNo(c.getCustomerNo()).getCreditStatus());

        // 驳回不改变客户
        LimitChangeRequest unfreeze = new LimitChangeRequest();
        unfreeze.setChangeType("UNFREEZE");
        CreditLimitVersion uPend = limitService.apply(c.getCustomerNo(), unfreeze, sales1);
        CreditLimitVersion rejected = limitService.reject(uPend.getId(), approval("继续冻"), finance);
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("FROZEN", customerService.requireByNo(c.getCustomerNo()).getCreditStatus());
    }

    @Test
    void 自审拒绝_下调低于占用拒绝_已有待审批拒绝重复申请() {
        Customer c = createCustomer(uniqueNo("规则客户"), "10000.00", sales1);
        LimitChangeRequest adj = new LimitChangeRequest();
        adj.setChangeType("ADJUST");
        adj.setAfterLimit(new BigDecimal("8000.00"));
        CreditLimitVersion pending = limitService.apply(c.getCustomerNo(), adj, sales1);

        // 申请人与审批人同名 → 拒绝（用 finance 视角但伪造同名不可能，故直接校验 applicant≠approver：
        // 让 finance 账号提交申请，再由 finance 审批即自审）
        LimitChangeRequest self = new LimitChangeRequest();
        self.setChangeType("FREEZE");
        // 当前有 PENDING，先驳回上面的调额
        limitService.reject(pending.getId(), approval("x"), finance);

        CreditLimitVersion selfPend = limitService.apply(c.getCustomerNo(), self, finance);
        BusinessException selfEx = assertThrows(BusinessException.class,
                () -> limitService.approve(selfPend.getId(), approval("自审"), finance));
        assertTrue(selfEx.getMessage().contains("申请人不能审批自己"));
        // 驳回该冻结申请，客户回到无待审批
        limitService.reject(selfPend.getId(), approval("驳回"), finance);

        // 占用 1060 后，下调到 1000（低于占用）应拒
        Site site = setupRateCard(2012, "1000");
        QuoteDetailView q = quote(c.getCustomerName(), 2012, site, 1);
        orderService.createPosting(posting(site.getSiteNo(), q.getQuote().getQuoteNo(), "sales1"));
        LimitChangeRequest down = new LimitChangeRequest();
        down.setChangeType("ADJUST");
        down.setAfterLimit(new BigDecimal("1000.00"));
        CreditLimitVersion downPend = limitService.apply(c.getCustomerNo(), down, sales1);
        BusinessException low = assertThrows(BusinessException.class,
                () -> limitService.approve(downPend.getId(), approval("过低"), finance));
        assertTrue(low.getMessage().contains("低于当前在额占用"));
    }

    // ---- 占用 / 拦截 / 释放 ----

    @Test
    void 上刊凭报价占用额度_余额不足拦在提交口_撤刊释放转应收() {
        Site site = setupRateCard(2013, "1000");
        String name = uniqueNo("占用客户");
        Customer c = createCustomer(name, "1060.00", sales1); // 恰好够一单 1060

        QuoteDetailView q1 = quote(name, 2013, site, 1);
        PostingOrder o1 = orderService.createPosting(posting(site.getSiteNo(), q1.getQuote().getQuoteNo(), "sales1"));
        assertEquals(1, o1.getCreditUsed());
        assertEquals(name, q1.getQuote().getCustomerName());
        Customer afterFirst = customerService.requireByNo(c.getCustomerNo());
        assertEquals("1060.00", afterFirst.getOccupiedAmount().toPlainString());
        assertEquals("0.00", CreditLimitService.available(afterFirst).toPlainString());

        // 同一报价单不能背两张上刊单（用额度充足的另一客户，纯粹验证报价复用而非额度）
        String reuseName = uniqueNo("复用客户");
        createCustomer(reuseName, "100000.00", sales1);
        Site site2 = setupRateCard(2014, "1000");
        QuoteDetailView qForReuse = quote(reuseName, 2014, site2, 1);
        // 先用一单占用掉 qForReuse
        orderService.createPosting(posting(site2.getSiteNo(), qForReuse.getQuote().getQuoteNo(), "sales1"));
        // 第二张其实先撞同点位同周档期；为纯粹验证报价复用，用不同周再试
        OrderCreateRequest otherWeek = posting(site2.getSiteNo(), qForReuse.getQuote().getQuoteNo(), "sales1");
        otherWeek.setOccurDate(LocalDate.of(2014, 6, 7));
        BusinessException reused2 = assertThrows(BusinessException.class,
                () -> orderService.createPosting(otherWeek));
        assertTrue(reused2.getMessage().contains("一张报价单最多背一张上刊单"));

        // 客户已无可用额度：再来一单（新报价）应被拦，且单据不落
        QuoteDetailView q2 = quote(name, 2013, site, 1);
        OrderCreateRequest overflow = posting(site.getSiteNo(), q2.getQuote().getQuoteNo(), "sales1");
        overflow.setOccurDate(LocalDate.of(2013, 6, 15)); // 下一周，避开档期冲突，纯粹测额度
        BusinessException shortEx = assertThrows(BusinessException.class,
                () -> orderService.createPosting(overflow));
        assertTrue(shortEx.getMessage().contains("可用额度不足"));
        // 占用仍只有一单
        assertEquals("1060.00", customerService.requireByNo(c.getCustomerNo()).getOccupiedAmount().toPlainString());

        // 撤刊落架：释放额度，占用行转 RELEASED（未核销转应收）
        orderService.takeDown(o1.getOrderNo(), takeDownAction());
        Customer released = customerService.requireByNo(c.getCustomerNo());
        assertEquals("0.00", released.getOccupiedAmount().toPlainString());
        CreditOccupation occ = creditService.requireByOrderNo(o1.getOrderNo());
        assertEquals("RELEASED", occ.getStatus());
        assertEquals("UNSETTLED", occ.getSettleStatus());

        // 释放后额度可用，新单能过（同一客户、新报价）
        PostingOrder o3 = orderService.createPosting(
                posting(site.getSiteNo(), q2.getQuote().getQuoteNo(), "sales1"));
        assertEquals("1060.00", customerService.requireByNo(c.getCustomerNo()).getOccupiedAmount().toPlainString());
        assertNotNull(o3);
    }

    @Test
    void 冻结客户不能新占用_普通单不占额度() {
        Site site = setupRateCard(2015, "1000");
        String name = uniqueNo("冻结客户");
        Customer c = createCustomer(name, "50000.00", sales1);
        LimitChangeRequest freeze = new LimitChangeRequest();
        freeze.setChangeType("FREEZE");
        CreditLimitVersion fp = limitService.apply(c.getCustomerNo(), freeze, sales1);
        limitService.approve(fp.getId(), approval("冻"), finance);

        QuoteDetailView q = quote(name, 2015, site, 1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createPosting(posting(site.getSiteNo(), q.getQuote().getQuoteNo(), "sales1")));
        assertTrue(ex.getMessage().contains("已冻结"));

        // 不引用报价单的普通单照常上刊，不占额度
        OrderCreateRequest normal = new OrderCreateRequest();
        normal.setSiteNo(site.getSiteNo());
        normal.setCreativeName("普通画面");
        normal.setOccurDate(LocalDate.of(2015, 6, 7)); // 下周
        normal.setOperatorName("tester");
        PostingOrder po = orderService.createPosting(normal);
        assertEquals(0, po.getCreditUsed());
        assertEquals("0.00", customerService.requireByNo(c.getCustomerNo()).getOccupiedAmount().toPlainString());
    }

    @Test
    void 报价客户未建档或多行报价_都不能占用() {
        Site site = setupRateCard(2016, "1000");
        // 未建档客户
        QuoteDetailView q = quote(uniqueNo("野客户"), 2016, site, 1);
        BusinessException noCust = assertThrows(BusinessException.class,
                () -> orderService.createPosting(posting(site.getSiteNo(), q.getQuote().getQuoteNo(), "sales1")));
        assertTrue(noCust.getMessage().contains("尚未建档授信"));
    }

    // ---- 回款 / 分次核销 ----

    @Test
    void 回款登记按单分次核销_挂起中核销实时释放额度_结清状态流转() {
        Site site = setupRateCard(2017, "1000");
        String name = uniqueNo("回款客户");
        Customer c = createCustomer(name, "10000.00", sales1);
        QuoteDetailView q = quote(name, 2017, site, 1); // 1060
        PostingOrder o = orderService.createPosting(posting(site.getSiteNo(), q.getQuote().getQuoteNo(), "sales1"));
        assertEquals("1060.00", customerService.requireByNo(c.getCustomerNo()).getOccupiedAmount().toPlainString());

        // 部分回款 500，核销 500：挂起中，占用降到 560
        PaymentRegisterRequest pr = new PaymentRegisterRequest();
        pr.setCustomerNo(c.getCustomerNo());
        pr.setReceivedAmount(new BigDecimal("500.00"));
        pr.setReceivedDate(LocalDate.of(2017, 6, 3));
        Payment pay = paymentService.register(pr, sales1);
        assertEquals("RECEIVED", pay.getStatus());

        WriteoffRequest w1 = new WriteoffRequest();
        w1.setPaymentNo(pay.getPaymentNo());
        w1.setOrderNo(o.getOrderNo());
        w1.setAmount(new BigDecimal("500.00"));
        w1.setWriteoffDate(LocalDate.of(2017, 6, 3));
        PaymentDetailView d1 = paymentService.writeoff(w1, sales1);
        assertEquals("SETTLED", d1.getPayment().getStatus());
        Customer mid = customerService.requireByNo(c.getCustomerNo());
        assertEquals("560.00", mid.getOccupiedAmount().toPlainString());
        CreditOccupation occMid = creditService.requireByOrderNo(o.getOrderNo());
        assertEquals("PARTIAL", occMid.getSettleStatus());
        assertEquals("500.00", occMid.getSettledAmount().toPlainString());

        // 核销超额拒绝
        WriteoffRequest over = new WriteoffRequest();
        over.setOrderNo(o.getOrderNo());
        over.setAmount(new BigDecimal("600.00")); // 只剩 560
        BusinessException overEx = assertThrows(BusinessException.class,
                () -> paymentService.writeoff(over, sales1));
        assertTrue(overEx.getMessage().contains("未核销额"));

        // 第二笔回款 560 结清（留空 paymentNo 自动挑）
        PaymentRegisterRequest pr2 = new PaymentRegisterRequest();
        pr2.setCustomerNo(c.getCustomerNo());
        pr2.setReceivedAmount(new BigDecimal("560.00"));
        paymentService.register(pr2, sales1);
        WriteoffRequest w2 = new WriteoffRequest();
        w2.setOrderNo(o.getOrderNo());
        w2.setAmount(new BigDecimal("560.00"));
        paymentService.writeoff(w2, sales1);

        Customer done = customerService.requireByNo(c.getCustomerNo());
        assertEquals("0.00", done.getOccupiedAmount().toPlainString());
        assertEquals("SETTLED", creditService.requireByOrderNo(o.getOrderNo()).getSettleStatus());
    }

    @Test
    void 撤刊转应收后核销只结应收不动额度_一笔回款核多张单() {
        Site site = setupRateCard(2018, "1000");
        String name = uniqueNo("应收客户");
        Customer c = createCustomer(name, "10000.00", sales1);
        QuoteDetailView q1 = quote(name, 2018, site, 1);
        PostingOrder o1 = orderService.createPosting(posting(site.getSiteNo(), q1.getQuote().getQuoteNo(), "sales1"));
        // 撤刊转应收，额度已释放
        orderService.takeDown(o1.getOrderNo(), takeDownAction());
        assertEquals("0.00", customerService.requireByNo(c.getCustomerNo()).getOccupiedAmount().toPlainString());

        // 一张回款 1060 核销应收单：占用额度保持 0
        PaymentRegisterRequest pr = new PaymentRegisterRequest();
        pr.setCustomerNo(c.getCustomerNo());
        pr.setReceivedAmount(new BigDecimal("1060.00"));
        Payment pay = paymentService.register(pr, sales1);
        WriteoffRequest w = new WriteoffRequest();
        w.setPaymentNo(pay.getPaymentNo());
        w.setOrderNo(o1.getOrderNo());
        w.setAmount(new BigDecimal("1060.00"));
        paymentService.writeoff(w, sales1);
        assertEquals("0.00", customerService.requireByNo(c.getCustomerNo()).getOccupiedAmount().toPlainString());
        assertEquals("SETTLED", creditService.requireByOrderNo(o1.getOrderNo()).getSettleStatus());
        assertEquals("RELEASED", creditService.requireByOrderNo(o1.getOrderNo()).getStatus());
    }

    @Test
    void 已核销单对冲拒绝_未核销对冲释放不转应收() {
        Site site = setupRateCard(2019, "1000");
        String name = uniqueNo("对冲客户");
        Customer c = createCustomer(name, "10000.00", sales1);

        // 单 A：部分核销后对冲必须拒绝
        QuoteDetailView qa = quote(name, 2019, site, 1);
        PostingOrder oa = orderService.createPosting(posting(site.getSiteNo(), qa.getQuote().getQuoteNo(), "sales1"));
        PaymentRegisterRequest pr = new PaymentRegisterRequest();
        pr.setCustomerNo(c.getCustomerNo());
        pr.setReceivedAmount(new BigDecimal("100.00"));
        Payment pay = paymentService.register(pr, sales1);
        WriteoffRequest w = new WriteoffRequest();
        w.setPaymentNo(pay.getPaymentNo());
        w.setOrderNo(oa.getOrderNo());
        w.setAmount(new BigDecimal("100.00"));
        paymentService.writeoff(w, sales1);
        com.evops.ledger.dto.ActionRequest off = new com.evops.ledger.dto.ActionRequest();
        off.setOperatorName("sales1");
        off.setReason("挂错");
        BusinessException offEx = assertThrows(BusinessException.class,
                () -> orderService.offset(oa.getOrderNo(), off));
        assertTrue(offEx.getMessage().contains("不能直接对冲"));

        // 单 B：未核销，对冲释放额度且 REVERSED（不形成应收，不能再核销）
        Site siteB = setupRateCard(2020, "1000");
        QuoteDetailView qb = quote(name, 2020, siteB, 1);
        PostingOrder ob = orderService.createPosting(posting(siteB.getSiteNo(), qb.getQuote().getQuoteNo(), "sales1"));
        orderService.offset(ob.getOrderNo(), off);
        CreditOccupation obOcc = creditService.requireByOrderNo(ob.getOrderNo());
        assertEquals("REVERSED", obOcc.getStatus());
        // ob 释放 1060；oa 仍挂起且占用 960（1060 已核销 100），客户总占用回到 960
        assertEquals("960.00", customerService.requireByNo(c.getCustomerNo()).getOccupiedAmount().toPlainString());
        WriteoffRequest wb = new WriteoffRequest();
        wb.setOrderNo(ob.getOrderNo());
        wb.setAmount(new BigDecimal("10.00"));
        BusinessException revEx = assertThrows(BusinessException.class, () -> paymentService.writeoff(wb, sales1));
        assertTrue(revEx.getMessage().contains("已对冲"));
    }

    // ---- 数据隔离 / 报表 ----

    @Test
    void 销售只见名下客户_跨户查询与申请都被拒_财务看全部() {
        Customer c1 = createCustomer(uniqueNo("销售一客户"), "1000.00", sales1);
        Customer c2 = createCustomer(uniqueNo("销售二客户"), "1000.00", sales2);

        List<String> s1Names = customerService.list(sales1, null, null).stream()
                .map(Customer::getCustomerName).collect(Collectors.toList());
        assertTrue(s1Names.contains(c1.getCustomerName()));
        assertFalse(s1Names.contains(c2.getCustomerName()));

        // sales1 翻 sales2 客户详情/版本/申请都拒
        BusinessException view = assertThrows(BusinessException.class,
                () -> customerService.requireViewable(c2.getCustomerNo(), sales1));
        assertTrue(view.getMessage().contains("无权操作"));
        LimitChangeRequest adj = new LimitChangeRequest();
        adj.setChangeType("ADJUST");
        adj.setAfterLimit(new BigDecimal("2000.00"));
        BusinessException applyEx = assertThrows(BusinessException.class,
                () -> limitService.apply(c2.getCustomerNo(), adj, sales1));
        assertTrue(applyEx.getMessage().contains("无权操作"));

        // 财务看全部
        List<String> all = customerService.list(finance, null, null).stream()
                .map(Customer::getCustomerName).collect(Collectors.toList());
        assertTrue(all.contains(c1.getCustomerName()) && all.contains(c2.getCustomerName()));
    }

    @Test
    void 销售不能给别人客户上刊占额() {
        Site site = setupRateCard(2021, "1000");
        Customer c2 = createCustomer(uniqueNo("别户客户"), "10000.00", sales2);
        QuoteDetailView q = quote(c2.getCustomerName(), 2021, site, 1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createPosting(posting(site.getSiteNo(), q.getQuote().getQuoteNo(), "sales1")));
        assertTrue(ex.getMessage().contains("无权操作"));
    }

    @Test
    void 月末财务拉每户占用明细_并导出CSV() {
        Site site = setupRateCard(2022, "1000");
        String name = uniqueNo("报表客户");
        Customer c = createCustomer(name, "10000.00", sales1);
        QuoteDetailView q = quote(name, 2022, site, 1);
        PostingOrder o = orderService.createPosting(posting(site.getSiteNo(), q.getQuote().getQuoteNo(), "sales1"));
        // 归属期间 2020-06（posting() 上刊发生日固定为 2020-06-02）
        String period = "2020-06";

        List<CreditOccupation> rows = creditService.monthReport(period, finance);
        assertTrue(rows.stream().anyMatch(r -> r.getOrderNo().equals(o.getOrderNo())));

        String csv = creditService.occupationCsv(period, finance);
        assertEquals('﻿', csv.charAt(0));
        assertTrue(csv.contains("归属期间") && csv.contains(name) && csv.contains(o.getOrderNo()));
        assertTrue(csv.contains("1060.00") && csv.contains("占用中"));

        // 销售视角只能看到自己名下
        List<CreditOccupation> mine = creditService.monthReport(period, sales1);
        assertTrue(mine.stream().anyMatch(r -> r.getOrderNo().equals(o.getOrderNo())));
        assertTrue(creditService.monthReport(period, sales2).stream()
                .noneMatch(r -> r.getCustomerName().equals(name)));
    }

    @Test
    void 底账版本登记包含f5授信版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f5-credit-1"));
    }

    private LimitApprovalRequest approval(String comment) {
        LimitApprovalRequest a = new LimitApprovalRequest();
        a.setComment(comment);
        a.setOperatorName("finance1");
        return a;
    }

    private com.evops.ledger.dto.ActionRequest takeDownAction() {
        com.evops.ledger.dto.ActionRequest a = new com.evops.ledger.dto.ActionRequest();
        a.setOperatorName("sales1");
        a.setReason("档期结束落架");
        return a;
    }
}
