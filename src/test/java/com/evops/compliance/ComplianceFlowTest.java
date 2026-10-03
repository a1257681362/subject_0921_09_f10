package com.evops.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.compliance.dto.CategoryItemSpec;
import com.evops.compliance.dto.FreezePreviewView;
import com.evops.compliance.dto.PublishResult;
import com.evops.compliance.entity.CategoryListVersion;
import com.evops.compliance.entity.ComplianceFreezeBatch;
import com.evops.compliance.entity.ComplianceFreezeRecord;
import com.evops.compliance.service.CategoryListService;
import com.evops.compliance.service.ComplianceFreezeService;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.entity.Customer;
import com.evops.credit.mapper.CustomerMapper;
import com.evops.credit.service.CustomerService;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.OrderEditRequest;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.entity.OrderEvent;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.SiteService;
import com.evops.ledger.dto.SiteCreateRequest;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F10 受限行业类目名单端到端（真实 H2 + 真实事务，无 Shiro，用显式 Viewer）。
 * 一条主流水跑通：发布名单 → 两个口拦截 → 老客户进名单批量冻结 → 解冻/强制落架 → 补录与版本链。
 * 受限类目名专用（"受限医美A"/"受限保健B"），不影响其他测试共用的内存库。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComplianceFlowTest {

    @Autowired private CategoryListService categoryListService;
    @Autowired private ComplianceFreezeService freezeService;
    @Autowired private CustomerService customerService;
    @Autowired private OrderService orderService;
    @Autowired private SiteService siteService;
    @Autowired private RateCardService rateCardService;
    @Autowired private QuoteService quoteService;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private RateCardMapper rateCardMapper;

    private static final String CAT_A = "受限医美A";
    private static final String CAT_B = "受限保健B";
    private static final String BASIS_A = "《广告法》第十六条及监管通报甲号";
    private static final String BASIS_B = "市场监管总局 2026 年第 12 号通报";

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    private final Viewer compliance = Viewer.compliance("compliance1");
    private final Viewer sales1 = Viewer.sales("sales1");

    // ---- 造数 ----

    private Site newSite() {
        SiteCreateRequest sr = new SiteCreateRequest();
        sr.setSiteNo(uniqueNo("COMP-SITE"));
        sr.setSizeSpec("18m×6m");
        sr.setOrientation("朝南");
        sr.setBusinessDistrict("合规测试商圈");
        sr.setOperatorName("tester");
        return siteService.register(sr);
    }

    /** 找一个没人用的历史年份建刊例（与其他测试共用内存库，年份唯一）。 */
    private int freeYear() {
        int thisYear = LocalDate.now().getYear();
        for (int y = 1990; y <= thisYear; y++) {
            Long count = rateCardMapper.selectCount(new LambdaQueryWrapper<RateCard>()
                    .eq(RateCard::getRateYear, y));
            if (count == null || count == 0) {
                return y;
            }
        }
        throw new IllegalStateException("没有可用的历史年份");
    }

    private void setupRateCard(int year, Site site) {
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
        sp.setListPrice(new BigDecimal("1000"));
        VersionConfigureRequest cfg = new VersionConfigureRequest();
        cfg.setTiers(new ArrayList<>(Arrays.asList(tier)));
        cfg.setSitePrices(new ArrayList<>(Arrays.asList(sp)));
        cfg.setOperatorName("tester");
        rateCardService.configure(v1, cfg);
        VersionActionRequest a = new VersionActionRequest();
        a.setOperatorName("tester");
        rateCardService.publish(v1, a);
    }

    private String quote(String customerName, int year, Site site) {
        QuoteLineRequest line = new QuoteLineRequest();
        line.setSiteNo(site.getSiteNo());
        line.setWeeks(1);
        QuoteSubmitRequest req = new QuoteSubmitRequest();
        req.setCustomerName(customerName);
        req.setSubmitDate(LocalDate.of(year, 6, 1));
        req.setLines(new ArrayList<>(Arrays.asList(line)));
        req.setOperatorName("tester");
        QuoteDetailView view = quoteService.submit(req);
        return view.getQuote().getQuoteNo();
    }

    private Customer createCustomer(String name, String category, Viewer owner) {
        CustomerCreateRequest req = new CustomerCreateRequest();
        req.setCustomerName(name);
        req.setIndustryCategory(category);
        req.setCreditLimit(new BigDecimal("100000.00"));
        return customerService.create(req, owner);
    }

    private PostingOrder postNormal(Site site, String category) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setCreativeName("普通画面");
        req.setIndustryCategory(category);
        req.setOccurDate(LocalDate.of(2020, 6, 2));
        req.setOperatorName("tester");
        return orderService.createPosting(req);
    }

    private PostingOrder postCredit(Site site, String quoteNo, String operator, LocalDate occurDate) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setCreativeName("授信画面");
        req.setQuoteNo(quoteNo);
        req.setOccurDate(occurDate);
        req.setOperatorName(operator);
        return orderService.createPosting(req);
    }

    private CategoryItemSpec item(String category, String basis) {
        CategoryItemSpec spec = new CategoryItemSpec();
        spec.setCategoryName(category);
        spec.setBasis(basis);
        return spec;
    }

    private CategoryListVersion publishList(List<CategoryItemSpec> items, String summary) {
        CategoryListVersion draft = categoryListService.newVersion(compliance);
        categoryListService.configure(draft.getId(), items, compliance);
        return categoryListService.publish(draft.getId(), summary, compliance).getVersion();
    }

    // ---- 主流水 ----

    @Test
    @Order(1)
    void fullFlow() {
        // 1. 初始无生效版：全部放行
        assertNull(categoryListService.currentEffective());
        categoryListService.requireNotRestricted(CAT_A, "客户建档"); // 不抛

        // 2. 合规岗发布 v1（受限医美A）：即时生效，版本即操作记录
        CategoryListVersion v1 = publishList(Collections.singletonList(item(CAT_A, BASIS_A)), "依监管通报新增受限医美A");
        assertEquals(ComplianceEnums.VERSION_EFFECTIVE, v1.getStatus());
        assertNotNull(v1.getPublishTime());
        assertEquals("compliance1", v1.getOperatorName());
        assertEquals("依监管通报新增受限医美A", v1.getChangeSummary());
        assertNotNull(categoryListService.currentEffective());
        // 即时生效：发布完立刻拦
        BusinessException immediate = assertThrows(BusinessException.class,
                () -> categoryListService.requireNotRestricted(CAT_A, "客户建档"));
        assertTrue(immediate.getMessage().contains(BASIS_A));

        // 3. 建档口：命中拦（提示带类目+依据+版本），未命中放行
        BusinessException createBlocked = assertThrows(BusinessException.class,
                () -> createCustomer(uniqueNo("拦截客户"), CAT_A, sales1));
        assertTrue(createBlocked.getMessage().contains(CAT_A));
        assertTrue(createBlocked.getMessage().contains(BASIS_A));
        assertTrue(createBlocked.getMessage().contains("客户建档"));
        Customer okCustomer = createCustomer(uniqueNo("正常客户"), "快消品", sales1);
        assertEquals("快消品", okCustomer.getIndustryCategory());

        // 4. 上刊口（普通单）：命中拦、类目必填拦、未命中放行且快照落单
        BusinessException postBlocked = assertThrows(BusinessException.class,
                () -> postNormal(newSite(), CAT_A));
        assertTrue(postBlocked.getMessage().contains(BASIS_A));
        assertTrue(postBlocked.getMessage().contains("上刊提交"));
        OrderCreateRequest noCategory = new OrderCreateRequest();
        Site siteNoCat = newSite();
        noCategory.setSiteNo(siteNoCat.getSiteNo());
        noCategory.setCreativeName("无类目画面");
        noCategory.setOccurDate(LocalDate.of(2020, 6, 2));
        noCategory.setOperatorName("tester");
        BusinessException required = assertThrows(BusinessException.class,
                () -> orderService.createPosting(noCategory));
        assertTrue(required.getMessage().contains("行业类目"));
        PostingOrder normalOrder = postNormal(newSite(), "快消品");
        assertEquals("快消品", normalOrder.getIndustryCategory());
        assertEquals(LedgerEnums.COMPLIANCE_NORMAL, normalOrder.getComplianceStatus());

        // 5. 权限：销售不能维护名单、不能冻结
        assertThrows(BusinessException.class, () -> categoryListService.newVersion(sales1));
        assertThrows(BusinessException.class,
                () -> freezeService.freeze(null, null, sales1));

        // 6. 老客户后进名单：C2（受限保健B）先有在途单，v2 把 受限保健B 收进名单
        int year = freeYear();
        Site creditSite = newSite();
        setupRateCard(year, creditSite);
        Customer c2 = createCustomer(uniqueNo("保健客户"), CAT_B, sales1);
        String quote1 = quote(c2.getCustomerName(), year, creditSite);
        // v2 发布前：授信上刊成功（类目快照=客户类目）
        PostingOrder o1 = postCredit(creditSite, quote1, "sales1", LocalDate.of(2020, 6, 2));
        assertEquals(CAT_B, o1.getIndustryCategory());
        assertEquals(1, o1.getCreditUsed());
        // 普通单（受限保健B）也在 v2 前上刊
        PostingOrder o2 = postNormal(newSite(), CAT_B);

        // 发布 v2（受限医美A + 受限保健B）：v1 自动取代
        CategoryListVersion v2 = publishList(Arrays.asList(item(CAT_A, BASIS_A), item(CAT_B, BASIS_B)),
                "新增受限保健B");
        assertEquals(ComplianceEnums.VERSION_EFFECTIVE, v2.getStatus());
        CategoryListVersion v1After = categoryListService.requireVersion(v1.getId());
        assertEquals(ComplianceEnums.VERSION_SUPERSEDED, v1After.getStatus());
        // 已生效/已取代版本不能再配再发
        assertThrows(BusinessException.class,
                () -> categoryListService.configure(v2.getId(), Collections.singletonList(item("X", "Y")), compliance));
        assertThrows(BusinessException.class,
                () -> categoryListService.publish(v1.getId(), "重发", compliance));

        // 7. 授信上刊口：客户类目已受限 → 拦（提示带依据），单据档期整体回滚
        // （换下一周避开 o1 的档期，确保走到类目校验而不是档期冲突）
        String quote2 = quote(c2.getCustomerName(), year, creditSite);
        BusinessException creditBlocked = assertThrows(BusinessException.class,
                () -> postCredit(creditSite, quote2, "sales1", LocalDate.of(2020, 6, 8)));
        assertTrue(creditBlocked.getMessage().contains(BASIS_B));
        // 整体回滚：该站点只有 o1 一张挂起单（被拦的没落下）
        List<PostingOrder> siteOrders = orderService.search(null, creditSite.getSiteNo(), LedgerEnums.ORDER_HUNG, null, null);
        assertEquals(1, siteOrders.size());
        assertEquals(o1.getOrderNo(), siteOrders.get(0).getOrderNo());

        // 8. 批量冻结预览 + 执行：命中 o1（授信单）与 o2（普通单）
        FreezePreviewView preview = freezeService.preview(Collections.singletonList(CAT_B), compliance);
        assertEquals(2, preview.getOrderCount());
        assertEquals(1, preview.getCustomerCount());
        ComplianceFreezeBatch batch = freezeService.freeze(Collections.singletonList(CAT_B), "v2 新增类目冻结", compliance);
        assertEquals(2, batch.getOrderCount());
        assertEquals(1, batch.getCustomerCount());
        assertEquals(v2.getVersionNo(), batch.getListVersionNo());
        assertEquals(LedgerEnums.COMPLIANCE_FROZEN, orderService.requireByNo(o1.getOrderNo()).getComplianceStatus());
        assertEquals(LedgerEnums.COMPLIANCE_FROZEN, orderService.requireByNo(o2.getOrderNo()).getComplianceStatus());
        // 记录行留底（含客户快照与类目）
        List<ComplianceFreezeRecord> records = freezeService.recordsOf(batch.getId(), compliance);
        assertEquals(2, records.size());
        ComplianceFreezeRecord r1 = records.stream()
                .filter(r -> r.getOrderNo().equals(o1.getOrderNo())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(c2.getCustomerName(), r1.getCustomerName());
        assertEquals(CAT_B, r1.getIndustryCategory());
        assertEquals(ComplianceEnums.RECORD_FROZEN, r1.getStatus());
        // 单据事件留底
        List<String> o1Events = orderService.events(o1.getId()).stream()
                .map(OrderEvent::getEventType).collect(Collectors.toList());
        assertTrue(o1Events.contains(LedgerEnums.EVENT_COMPLIANCE_FREEZE));

        // 9. 冻结中：落架/对冲/更正/回退全拦
        assertThrows(BusinessException.class,
                () -> orderService.takeDown(o1.getOrderNo(), new ActionRequest()));
        assertThrows(BusinessException.class,
                () -> orderService.offset(o1.getOrderNo(), new ActionRequest()));
        OrderEditRequest editReq = new OrderEditRequest();
        editReq.setCreativeName("改名");
        assertThrows(BusinessException.class,
                () -> orderService.edit(o1.getOrderNo(), editReq));

        // 10. 重复触发幂等：同类目再冻 → 0 张
        ComplianceFreezeBatch dup = freezeService.freeze(Collections.singletonList(CAT_B), "重复触发", compliance);
        assertEquals(0, dup.getOrderCount());

        // 11. 解冻特批：恢复后可正常落架；同类目不再重复冻结
        freezeService.unfreeze(o1.getOrderNo(), "监管特批放行", compliance);
        assertEquals(LedgerEnums.COMPLIANCE_NORMAL, orderService.requireByNo(o1.getOrderNo()).getComplianceStatus());
        ComplianceFreezeBatch afterRelease = freezeService.freeze(Collections.singletonList(CAT_B), "再冻", compliance);
        assertEquals(0, afterRelease.getOrderCount()); // o1 已特批、o2 仍冻着
        ActionRequest takeDownReq = new ActionRequest();
        takeDownReq.setOperatorName("tester");
        orderService.takeDown(o1.getOrderNo(), takeDownReq);
        assertEquals(LedgerEnums.ORDER_TAKEN_DOWN, orderService.requireByNo(o1.getOrderNo()).getStatus());
        ComplianceFreezeRecord r1After = freezeService.recordsOf(batch.getId(), compliance).stream()
                .filter(r -> r.getOrderNo().equals(o1.getOrderNo())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(ComplianceEnums.RECORD_RELEASED, r1After.getStatus());
        assertEquals("监管特批放行", r1After.getDisposeReason());

        // 12. 强制落架：冻结单下画，记录置 FORCE_CLOSED
        freezeService.forceTakeDown(o2.getOrderNo(), "受限行业下画", compliance);
        PostingOrder o2After = orderService.requireByNo(o2.getOrderNo());
        assertEquals(LedgerEnums.ORDER_TAKEN_DOWN, o2After.getStatus());
        ComplianceFreezeRecord r2After = freezeService.recordsOf(batch.getId(), compliance).stream()
                .filter(r -> r.getOrderNo().equals(o2.getOrderNo())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(ComplianceEnums.RECORD_FORCE_CLOSED, r2After.getStatus());

        // 13. 历史客户类目补录：仅空可补、补后不可改；补成受限类目给出命中提示
        Customer legacy = createCustomer(uniqueNo("历史客户"), "快消品", sales1);
        customerMapper.update(null, new LambdaUpdateWrapper<Customer>()
                .eq(Customer::getId, legacy.getId())
                .set(Customer::getIndustryCategory, null));
        Map<String, Object> fill = customerService.fillIndustryCategory(legacy.getCustomerNo(), CAT_A, compliance);
        assertEquals(Boolean.TRUE, fill.get("restrictedHit"));
        assertEquals(BASIS_A, fill.get("restrictedBasis"));
        assertThrows(BusinessException.class,
                () -> customerService.fillIndustryCategory(legacy.getCustomerNo(), "快消品", compliance));
        assertThrows(BusinessException.class,
                () -> customerService.fillIndustryCategory(legacy.getCustomerNo(), "快消品", sales1));

        // 14. 版本链即操作记录：v1 已取代、v2 生效中，经手人/说明都在
        List<CategoryListVersion> versions = categoryListService.listVersions();
        assertEquals(2, versions.size());
        assertEquals(v2.getVersionNo(), versions.get(0).getVersionNo());
        assertEquals("新增受限保健B", versions.get(0).getChangeSummary());
    }

    @Test
    @Order(2)
    void publishResultReportsAddedAndRemoved() {
        // 发布结果带类目增减（前端据此提示一键冻结）；与主流水共享名单，这里只验证增减口径
        CategoryListVersion draft = categoryListService.newVersion(compliance);
        categoryListService.configure(draft.getId(),
                Arrays.asList(item(CAT_A, BASIS_A), item("临时类目C", "依据丙")), compliance);
        PublishResult result = categoryListService.publish(draft.getId(), "换下受限保健B、上加临时类目C", compliance);
        assertTrue(result.getAddedCategories().contains("临时类目C"));
        assertTrue(result.getRemovedCategories().contains(CAT_B));
        assertFalse(result.getAddedCategories().contains(CAT_A));
        // 收尾：把名单恢复到不含临时类目（避免影响同库其他断言）
        CategoryListVersion d2 = categoryListService.newVersion(compliance);
        categoryListService.configure(d2.getId(),
                Arrays.asList(item(CAT_A, BASIS_A), item(CAT_B, BASIS_B)), compliance);
        categoryListService.publish(d2.getId(), "恢复", compliance);
    }
}
