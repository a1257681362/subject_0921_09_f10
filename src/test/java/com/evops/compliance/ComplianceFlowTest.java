package com.evops.compliance;

import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.compliance.dto.CategoryCreateRequest;
import com.evops.compliance.dto.CategoryModifyRequest;
import com.evops.compliance.dto.CategoryRestrictRequest;
import com.evops.compliance.entity.IndustryCategory;
import com.evops.compliance.entity.IndustryCategoryOpLog;
import com.evops.compliance.entity.IndustryCategoryVersion;
import com.evops.compliance.service.ComplianceFreezeService;
import com.evops.compliance.service.IndustryCategoryService;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.entity.Customer;
import com.evops.credit.service.CustomerService;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.OrderEditRequest;
import com.evops.ledger.dto.RollbackRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.OrderEvent;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.OrderService;
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
import com.evops.ratecard.mapper.RateCardMapper;
import com.evops.ratecard.service.QuoteService;
import com.evops.ratecard.service.RateCardService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F10 受限行业名单端到端（真实 H2 + 真实事务，无 Shiro，用显式 Viewer 验证权限与隔离）：
 * 建档口拦截、授信上刊提交口拦截、在途单冻结/解冻、版本链与操作记录、普通单不受影响、页面预检。
 */
@SpringBootTest
class ComplianceFlowTest {

    @Autowired private IndustryCategoryService categoryService;
    @Autowired private ComplianceFreezeService freezeService;
    @Autowired private CustomerService customerService;
    @Autowired private OrderService orderService;
    @Autowired private SiteService siteService;
    @Autowired private RateCardService rateCardService;
    @Autowired private QuoteService quoteService;
    @Autowired private RateCardMapper rateCardMapper;
    @Autowired private ObjectMapper objectMapper;

    /**
     * 刊例按自然年唯一，且整个测试套件共用同一个内存库上下文。
     * 1960 起动态挑空闲历史年（F4 用 2001–2011、F5 用 2012–2022、F9 从 1990 起抢），谁先跑都不撞。
     */
    private static int yearCursor = 1960;
    private synchronized int freeYear() {
        while (yearCursor < 2026) {
            int candidate = yearCursor++;
            Long count = rateCardMapper.selectCount(new LambdaQueryWrapper<RateCard>()
                    .eq(RateCard::getRateYear, candidate));
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("没有可用的历史年份来建测试刊例");
    }

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + System.nanoTime() + seq;
    }

    private final Viewer compliance = Viewer.compliance("compliance1");
    private final Viewer sales1 = Viewer.sales("sales1");
    private final Viewer finance = Viewer.finance("finance1");

    // ---- 造数 ----

    /** 本次造数的刊例年（JUnit 每方法一个实例，单方法内顺序使用） */
    private int cardYear;

    private Site setupRateCard(String price) {
        int year = freeYear();
        cardYear = year;
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("CP-SITE"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝北");
        sr.setBusinessDistrict("合规测试商圈");
        sr.setOperatorName("tester");
        Site site = siteService.register(sr);

        RateCardCreateRequest cr = new RateCardCreateRequest();
        cr.setRateYear(year);
        cr.setCardName(year + " 合规测试刊例");
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

    private String quoteOneLine(int year, Site site, String customerName) {
        QuoteLineRequest line = new QuoteLineRequest();
        line.setSiteNo(site.getSiteNo());
        line.setWeeks(1);
        QuoteSubmitRequest req = new QuoteSubmitRequest();
        req.setCustomerName(customerName);
        req.setSubmitDate(LocalDate.of(year, 6, 1));
        req.setLines(new ArrayList<>(Arrays.asList(line)));
        req.setOperatorName("sales1");
        return quoteService.submit(req).getQuote().getQuoteNo();
    }

    private Customer createCustomer(String categoryCode) {
        String name = uniqueNo("合规客户");
        CustomerCreateRequest req = new CustomerCreateRequest();
        req.setCustomerName(name);
        req.setIndustryCategoryCode(categoryCode);
        req.setCreditLimit(new BigDecimal("9999999.00"));
        return customerService.create(req, sales1);
    }

    private OrderCreateRequest creditPosting(Site site, String quoteNo) {
        return creditPosting(site, quoteNo, LocalDate.of(2020, 6, 2));
    }

    private OrderCreateRequest creditPosting(Site site, String quoteNo, LocalDate occurDate) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setCreativeName("合规测试画面");
        req.setQuoteNo(quoteNo);
        req.setOccurDate(occurDate);
        req.setOperatorName("sales1");
        return req;
    }

    private OrderCreateRequest normalPosting(Site site, String creative) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setCreativeName(creative);
        req.setOccurDate(LocalDate.of(2020, 6, 3));
        req.setOperatorName("sales1");
        return req;
    }

    private String newNormalCategory() {
        String code = "T" + uniqueNo("");
        CategoryCreateRequest req = new CategoryCreateRequest();
        req.setCategoryCode(code);
        req.setCategoryName("测试类目" + seq);
        categoryService.addCategory(req, compliance);
        return code;
    }

    private void restrict(String code, String basis) {
        CategoryRestrictRequest req = new CategoryRestrictRequest();
        req.setLegalBasis(basis);
        categoryService.restrict(code, req, compliance);
    }

    // ---- 1. 建档口 ----

    @Test
    void 建档口_命中预置受限类目当场拒_提示带依据() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> createCustomer("MEDICAL"));
        assertTrue(ex.getMessage().contains("受限行业名单"));
        assertTrue(ex.getMessage().contains("医疗诊疗"));
        assertTrue(ex.getMessage().contains("广告法"), "拦截提示必须带法规依据: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("禁止客户建档"));
    }

    @Test
    void 建档口_不存在类目与停用类目都拒收() {
        assertThrows(BusinessException.class, () -> createCustomer("NO_SUCH_CODE_X"));

        String code = newNormalCategory();
        categoryService.disable(code, null, compliance);
        BusinessException ex = assertThrows(BusinessException.class, () -> createCustomer(code));
        assertTrue(ex.getMessage().contains("停用"));
    }

    @Test
    void 建档口_普通类目建档成功_类目编码名称随档固化() {
        String code = newNormalCategory();
        Customer c = createCustomer(code);
        IndustryCategory cat = categoryService.requireByCode(code);
        assertEquals(code, c.getIndustryCategoryCode());
        assertEquals(cat.getCategoryName(), c.getIndustryCategoryName());
    }

    // ---- 2. 授信上刊提交口 + 普通单不受影响 ----

    @Test
    void 上刊提交口_建档后类目转受限_新授信单被拒且单据档期不落() {
        Site site = setupRateCard("1000");
        String code = newNormalCategory();
        Customer c = createCustomer(code);
        String q1 = quoteOneLine(cardYear, site, c.getCustomerName());
        PostingOrder first = orderService.createPosting(creditPosting(site, q1));
        assertNotNull(first.getId());

        // 后来进了名单：即时生效，新授信单在提交口被拦
        restrict(code, "测试法规依据第 X 条");
        String q2 = quoteOneLine(cardYear, site, c.getCustomerName());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createPosting(creditPosting(site, q2, LocalDate.of(2020, 6, 9))));
        assertTrue(ex.getMessage().contains("禁止授信上刊提交"));
        assertTrue(ex.getMessage().contains("测试法规依据第 X 条"));

        // 单据不落：该客户只有第一张挂起单
        List<PostingOrder> hung = orderService.search(null, site.getSiteNo(), "HUNG", null);
        long forCustomer = hung.stream().filter(o -> c.getId().equals(o.getCustomerId())).count();
        assertEquals(1, forCustomer);
    }

    @Test
    void 上刊提交口_普通单不引用报价单_不受名单影响() {
        Site site = setupRateCard("1000");
        String code = newNormalCategory();
        createCustomer(code);
        restrict(code, "测试法规依据第 Y 条");
        // 普通上刊单不带客户，照常开（行业管控面只覆盖授信单）
        PostingOrder o = orderService.createPosting(normalPosting(site, "普通单画面"));
        assertEquals(0, o.getCreditUsed());
    }

    // ---- 3. 在途单批量冻结 / 解冻 ----

    @Test
    void 冻结工作台_预览圈定数量金额_一键冻结后禁所有动作_解冻后恢复() {
        Site site = setupRateCard("1000");
        String code = newNormalCategory();
        Customer c = createCustomer(code);
        String q1 = quoteOneLine(cardYear, site, c.getCustomerName());
        PostingOrder order = orderService.createPosting(creditPosting(site, q1));

        restrict(code, "冻结测试依据第 Z 条");
        com.evops.compliance.dto.FreezePreviewView preview = freezeService.previewFreeze(code);
        assertEquals(1, preview.getTotalCount());
        assertEquals(new BigDecimal("1060.00"), preview.getTotalAmount());
        assertEquals(order.getOrderNo(), preview.getOrders().get(0).getOrderNo());

        // 一键冻结
        com.evops.compliance.dto.FreezePreviewView done =
                freezeService.executeFreeze(code, "通报整改", compliance);
        assertEquals(1, done.getAffectedCount());
        PostingOrder frozen = orderService.requireByNo(order.getOrderNo());
        assertEquals(1, frozen.getComplianceFrozen());
        assertTrue(frozen.getFreezeReason().contains("冻结测试依据第 Z 条"));
        assertTrue(frozen.getFreezeReason().contains("compliance1"));

        // 落架/对冲/更正/回退全部被拒
        ActionRequest action = new ActionRequest();
        action.setOperatorName("sales1");
        BusinessException td = assertThrows(BusinessException.class,
                () -> orderService.takeDown(order.getOrderNo(), action));
        assertTrue(td.getMessage().contains("合规冻结"));
        assertThrows(BusinessException.class, () -> orderService.offset(order.getOrderNo(), action));
        OrderEditRequest edit = new OrderEditRequest();
        edit.setCreativeName("改名");
        edit.setOperatorName("sales1");
        assertThrows(BusinessException.class, () -> orderService.edit(order.getOrderNo(), edit));
        RollbackRequest rb = new RollbackRequest();
        rb.setTargetVersion(1);
        assertThrows(BusinessException.class, () -> orderService.rollback(order.getOrderNo(), rb));

        // 冻结事件留底
        List<OrderEvent> events = orderService.events(frozen.getId());
        assertEquals("COMPLIANCE_FREEZE", events.get(events.size() - 1).getEventType());
        assertTrue(events.get(events.size() - 1).getDetail().contains("冻结测试依据第 Z 条"));

        // 重复冻结幂等：条件更新 0→1，不重复计数
        assertEquals(0, freezeService.executeFreeze(code, null, compliance).getAffectedCount());

        // 解除受限 + 一键解冻后可正常落架
        categoryService.release(code, null, compliance);
        com.evops.compliance.dto.FreezePreviewView unfPreview = freezeService.previewUnfreeze(code);
        assertEquals(1, unfPreview.getTotalCount());
        assertEquals(1, freezeService.executeUnfreeze(code, null, compliance).getAffectedCount());
        assertEquals(0, orderService.requireByNo(order.getOrderNo()).getComplianceFrozen());
        orderService.takeDown(order.getOrderNo(), action);
        assertEquals("TAKEN_DOWN", orderService.requireByNo(order.getOrderNo()).getStatus());
        events = orderService.events(frozen.getId());
        assertEquals("COMPLIANCE_UNFREEZE",
                events.stream().filter(e -> "COMPLIANCE_UNFREEZE".equals(e.getEventType()))
                        .reduce((a, b) -> b).orElseThrow(AssertionError::new).getEventType());
    }

    private long countHungForCustomerUnused(Site site, Customer c) {
        return orderService.search(null, site.getSiteNo(), "HUNG", null)
                .stream().filter(o -> c.getId().equals(o.getCustomerId())).count();
    }

    // ---- 4. 版本链与操作记录 ----

    @Test
    void 名单维护_每次动作落版本全量快照_旧版取代_流水前后值齐全_即时生效() {
        String code = newNormalCategory();
        assertNull(categoryService.findRestricted(code));

        String basis = "版本测试依据条";
        restrict(code, basis);
        IndustryCategory hit = categoryService.findRestricted(code);
        assertNotNull(hit);
        assertEquals(basis, hit.getLegalBasis());

        CategoryModifyRequest mod = new CategoryModifyRequest();
        mod.setCategoryName("改名后的类目");
        mod.setLegalBasis(basis + "（修订）");
        categoryService.modify(code, mod, compliance);
        assertEquals("改名后的类目", categoryService.requireByCode(code).getCategoryName());
        assertEquals(basis + "（修订）", categoryService.findRestricted(code).getLegalBasis());

        categoryService.release(code, null, compliance);
        assertNull(categoryService.findRestricted(code));

        List<IndustryCategoryVersion> versions = categoryService.listVersions();
        assertEquals("EFFECTIVE", versions.get(0).getStatus());
        // 同一次维护产生的连续版本：新版生效，前版必为 SUPERSEDED
        IndustryCategoryVersion effective = versions.get(0);
        IndustryCategoryVersion previous = versions.get(1);
        assertEquals("SUPERSEDED", previous.getStatus());
        assertTrue(effective.getVersionNo() > previous.getVersionNo());
        assertTrue(effective.getRestrictedCount() >= 0);
        // 全量快照可反序列化
        assertDoesThrowNothing(() -> objectMapper.readTree(effective.getSnapshot()));

        List<IndustryCategoryOpLog> ops = categoryService.listOps(code);
        assertTrue(ops.size() >= 4); // ADD/RESTRICT/MODIFY/RELEASE
        IndustryCategoryOpLog restrictOp = ops.stream()
                .filter(o -> "RESTRICT".equals(o.getAction())).findFirst().orElseThrow(AssertionError::new);
        assertNotNull(restrictOp.getBeforeJson());
        assertNotNull(restrictOp.getAfterJson());
        assertTrue(restrictOp.getAfterJson().contains("\"restricted\":1"));
        assertEquals(basis, restrictOp.getLegalBasis());
        assertEquals("compliance1", restrictOp.getOperatorName());
    }

    private interface ThrowRunnable { void run() throws Exception; }
    private void assertDoesThrowNothing(ThrowRunnable r) {
        try { r.run(); } catch (Exception e) { throw new AssertionError(e); }
    }

    // ---- 5. 权限：销售、财务不能维护名单/冻结 ----

    @Test
    void 非合规岗_维护与冻结一律拒绝() {
        String code = newNormalCategory();
        CategoryRestrictRequest req = new CategoryRestrictRequest();
        req.setLegalBasis("越权依据");
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> categoryService.restrict(code, req, sales1));
        assertTrue(ex1.getMessage().contains("合规岗"));
        assertThrows(BusinessException.class, () -> categoryService.restrict(code, req, finance));
        assertThrows(BusinessException.class,
                () -> freezeService.executeFreeze(code, null, sales1));
        assertThrows(BusinessException.class,
                () -> freezeService.executeUnfreeze(code, null, finance));

        CategoryCreateRequest add = new CategoryCreateRequest();
        add.setCategoryCode("SHOULD_FAIL_X");
        add.setCategoryName("越权新增");
        assertThrows(BusinessException.class, () -> categoryService.addCategory(add, sales1));
    }

    // ---- 6. 上刊页预检 ----

    @Test
    void 上刊页预检_受限客户报价返回blocked与依据_未建档客户不误伤() {
        Site site = setupRateCard("1000");
        String code = newNormalCategory();
        Customer c = createCustomer(code);
        String qFiled = quoteOneLine(cardYear, site, c.getCustomerName());
        Map<String, Object> before = freezeService.checkQuoteOrder(qFiled);
        assertEquals(false, before.get("blocked"));

        restrict(code, "预检依据条");
        Map<String, Object> after = freezeService.checkQuoteOrder(qFiled);
        assertEquals(true, after.get("blocked"));
        assertEquals("预检依据条", after.get("legalBasis"));
        assertTrue(((String) after.get("message")).contains("禁止授信上刊提交"));

        // 报价客户未建档：不判受限（建档环节本来也还没有行业），不误伤
        String qNoCustomer = quoteOneLine(cardYear, site, uniqueNo("未建档报价客户"));
        Map<String, Object> unfiled = freezeService.checkQuoteOrder(qNoCustomer);
        assertEquals(false, unfiled.get("blocked"));
        assertEquals(false, unfiled.get("customerFiled"));
    }
}
