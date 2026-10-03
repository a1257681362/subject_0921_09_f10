package com.evops.ratecard;

import com.evops.common.BusinessException;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.Site;
import com.evops.ledger.service.SchemaVersionService;
import com.evops.ledger.service.SiteService;
import com.evops.ratecard.dto.PricedLine;
import com.evops.ratecard.dto.PricingResult;
import com.evops.ratecard.dto.QuoteDetailView;
import com.evops.ratecard.dto.QuoteLineRequest;
import com.evops.ratecard.dto.QuoteSubmitRequest;
import com.evops.ratecard.dto.RateCardCreateRequest;
import com.evops.ratecard.dto.SitePriceSpec;
import com.evops.ratecard.dto.TierSpec;
import com.evops.ratecard.dto.VersionActionRequest;
import com.evops.ratecard.dto.VersionConfigureRequest;
import com.evops.ratecard.dto.VersionDetailView;
import com.evops.ratecard.entity.RateCard;
import com.evops.ratecard.entity.RateCardVersion;
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

/** F4 年度刊例与统一计价口径端到端测试（真实 H2 + 真实事务）。 */
@SpringBootTest
class RateCardFlowTest {

    @Autowired private RateCardService rateCardService;
    @Autowired private QuoteService quoteService;
    @Autowired private SiteService siteService;
    @Autowired private SchemaVersionService schemaVersionService;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    // ---- 构造帮手 ----

    private Site registerSite(String district) {
        SiteCreateRequest req = new SiteCreateRequest();
        req.setSiteNo(uniqueNo("RC-SITE"));
        req.setSizeSpec("18m×6m");
        req.setOrientation("朝北");
        req.setBusinessDistrict(district);
        req.setOperatorName("tester");
        return siteService.register(req);
    }

    private RateCard createCard(int year) {
        RateCardCreateRequest req = new RateCardCreateRequest();
        req.setRateYear(year);
        req.setCardName(year + " 年度户外 LED 刊例");
        req.setOperatorName("tester");
        return rateCardService.createCard(req);
    }

    private TierSpec tier(int min, Integer max, String rate) {
        TierSpec t = new TierSpec();
        t.setMinWeeks(min);
        t.setMaxWeeks(max);
        t.setDiscountRate(new BigDecimal(rate));
        return t;
    }

    private SitePriceSpec price(String siteNo, String listPrice) {
        SitePriceSpec s = new SitePriceSpec();
        s.setSiteNo(siteNo);
        s.setListPrice(new BigDecimal(listPrice));
        return s;
    }

    private List<TierSpec> standardTiers() {
        // 1~4 周不打折，5~12 周九折，13 周及以上八折；首尾相接、买越长越便宜
        return Arrays.asList(tier(1, 4, "1.0000"), tier(5, 12, "0.9000"), tier(13, null, "0.8000"));
    }

    private VersionConfigureRequest config(List<TierSpec> tiers, SitePriceSpec... prices) {
        VersionConfigureRequest req = new VersionConfigureRequest();
        req.setTiers(tiers);
        req.setSitePrices(new ArrayList<>(Arrays.asList(prices)));
        req.setOperatorName("tester");
        return req;
    }

    private VersionActionRequest action() {
        VersionActionRequest a = new VersionActionRequest();
        a.setOperatorName("tester");
        return a;
    }

    private long firstVersionId(RateCard card) {
        return rateCardService.listVersions(card.getId()).stream()
                .filter(v -> v.getVersionNo() == 1).findFirst().get().getId();
    }

    private QuoteLineRequest line(String siteNo, int weeks) {
        QuoteLineRequest l = new QuoteLineRequest();
        l.setSiteNo(siteNo);
        l.setWeeks(weeks);
        return l;
    }

    private QuoteSubmitRequest quoteReq(String customer, LocalDate date, QuoteLineRequest... lines) {
        QuoteSubmitRequest req = new QuoteSubmitRequest();
        req.setCustomerName(customer);
        req.setSubmitDate(date);
        req.setLines(new ArrayList<>(Arrays.asList(lines)));
        req.setOperatorName("tester");
        return req;
    }

    // ---- 年度刊例 / 版本 / 状态 ----

    @Test
    void 建年度刊例自动带v1草稿_同年不能重复建() {
        int year = 2001;
        RateCard card = createCard(year);
        assertEquals("RC" + year, card.getCardNo());
        assertEquals("DRAFT", card.getStatus());
        assertNull(card.getCurrentVersionId());

        List<RateCardVersion> versions = rateCardService.listVersions(card.getId());
        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0).getVersionNo());
        assertEquals("DRAFT", versions.get(0).getStatus());
        assertEquals(0, versions.get(0).getTaxRate().compareTo(new BigDecimal("0.0600")));

        BusinessException ex = assertThrows(BusinessException.class, () -> createCard(year));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void 档位校验_首档从1周起_断档倒挂开口档位置_都拒收() {
        int year = 2002;
        RateCard card = createCard(year);
        long v1 = firstVersionId(card);
        Site site = registerSite("中关村");

        // 首档不从 1 周起
        BusinessException e1 = assertThrows(BusinessException.class, () -> rateCardService.configure(v1,
                config(Arrays.asList(tier(2, 4, "1.0"), tier(5, null, "0.8")),
                        price(site.getSiteNo(), "1000"))));
        assertTrue(e1.getMessage().contains("首档必须从 1 周起"));

        // 断档：第一档止于 3，下一档应从 4 起
        BusinessException e2 = assertThrows(BusinessException.class, () -> rateCardService.configure(v1,
                config(Arrays.asList(tier(1, 3, "1.0"), tier(5, 12, "0.9"), tier(13, null, "0.8")),
                        price(site.getSiteNo(), "1000"))));
        assertTrue(e2.getMessage().contains("首尾相接"));

        // 开口档放在中间
        BusinessException e3 = assertThrows(BusinessException.class, () -> rateCardService.configure(v1,
                config(Arrays.asList(tier(1, 4, "1.0"), tier(5, null, "0.9"), tier(20, 30, "0.8")),
                        price(site.getSiteNo(), "1000"))));
        assertTrue(e3.getMessage().contains("开口档只能放在最后一档"));

        // 比例倒挂：买得越长反而越贵
        BusinessException e4 = assertThrows(BusinessException.class, () -> rateCardService.configure(v1,
                config(Arrays.asList(tier(1, 4, "0.90"), tier(5, null, "1.00")),
                        price(site.getSiteNo(), "1000"))));
        assertTrue(e4.getMessage().contains("买得越长越便宜"));

        // 全被拒后 v1 仍是没配置的草稿，点位价一条都没落
        VersionDetailView detail = rateCardService.versionDetail(v1);
        assertEquals(0, detail.getTiers().size());
        assertEquals(0, detail.getSitePrices().size());
    }

    @Test
    void 点位刊例价_不存在与重复都拒收() {
        int year = 2003;
        RateCard card = createCard(year);
        long v1 = firstVersionId(card);
        Site site = registerSite("陆家嘴");

        BusinessException e1 = assertThrows(BusinessException.class, () -> rateCardService.configure(v1,
                config(standardTiers(), price("NO-SUCH-SITE-999", "1000"))));
        assertTrue(e1.getMessage().contains("不存在"));

        BusinessException e2 = assertThrows(BusinessException.class, () -> rateCardService.configure(v1,
                config(standardTiers(),
                        price(site.getSiteNo(), "1000"),
                        price(site.getSiteNo(), "2000"))));
        assertTrue(e2.getMessage().contains("重复配置"));
    }

    @Test
    void 草稿配置后发布生效_发布即冻结不能改() {
        int year = 2004;
        Site site = registerSite("天河城");
        RateCard card = createCard(year);
        long v1 = firstVersionId(card);

        // 没配档位/点位价的草稿不许发布
        BusinessException empty = assertThrows(BusinessException.class,
                () -> rateCardService.publish(v1, action()));
        assertTrue(empty.getMessage().contains("阶梯档位"));

        rateCardService.configure(v1, config(standardTiers(), price(site.getSiteNo(), "1000.00")));

        RateCardVersion published = rateCardService.publish(v1, action());
        assertEquals("EFFECTIVE", published.getStatus());
        assertNotNull(published.getPublishTime());

        RateCard reloaded = rateCardService.requireCard(card.getId());
        assertEquals("EFFECTIVE", reloaded.getStatus());
        assertEquals(v1, reloaded.getCurrentVersionId());
        assertEquals(1, reloaded.getCurrentVersionNo());

        // 生效版本冻结：再配置直接拒
        BusinessException ex = assertThrows(BusinessException.class, () -> rateCardService.configure(v1,
                config(standardTiers(), price(site.getSiteNo(), "1200"))));
        assertTrue(ex.getMessage().contains("已冻结"));

        // 生效版本不能重复发布
        BusinessException republish = assertThrows(BusinessException.class,
                () -> rateCardService.publish(v1, action()));
        assertTrue(republish.getMessage().contains("只有草稿版本可发布"));
    }

    @Test
    void 调价另起新版_拷贝档位点位价_发布后旧版置SUPERSEDED() {
        int year = 2005;
        Site site = registerSite("武林广场");
        RateCard card = createCard(year);
        long v1Id = firstVersionId(card);
        rateCardService.configure(v1Id, config(standardTiers(), price(site.getSiteNo(), "1000")));
        rateCardService.publish(v1Id, action());

        // 同一刊例同时只准一个草稿：v1 生效时可起新版
        RateCardVersion v2 = rateCardService.newVersion(card.getId(), action());
        assertEquals(2, v2.getVersionNo());
        assertEquals("DRAFT", v2.getStatus());

        // 新草稿自动继承上一版的档位与点位价
        VersionDetailView copied = rateCardService.versionDetail(v2.getId());
        assertEquals(3, copied.getTiers().size());
        assertEquals(1, copied.getSitePrices().size());
        assertEquals("1000.00", copied.getSitePrices().get(0).getListPrice().toPlainString());

        // 调价：点位挂牌价 1000 → 1200
        rateCardService.configure(v2.getId(), config(standardTiers(), price(site.getSiteNo(), "1200")));
        rateCardService.publish(v2.getId(), action());

        RateCard reloaded = rateCardService.requireCard(card.getId());
        assertEquals(v2.getId(), reloaded.getCurrentVersionId());
        assertEquals(2, reloaded.getCurrentVersionNo());
        RateCardVersion old = rateCardService.versionDetail(v1Id).getVersion();
        assertEquals("SUPERSEDED", old.getStatus()); // 旧版留底不抹、不再生效

        // 历史版的点位价还是 1000，没被回写
        VersionDetailView oldDetail = rateCardService.versionDetail(v1Id);
        assertEquals("1000.00", oldDetail.getSitePrices().get(0).getListPrice().toPlainString());
        VersionDetailView newDetail = rateCardService.versionDetail(v2.getId());
        assertEquals("1200.00", newDetail.getSitePrices().get(0).getListPrice().toPlainString());
    }

    // ---- 计价 / 报价 ----

    @Test
    void 按购买周数落阶梯档_含税未税并列_金额精确到分() {
        int year = 2006;
        Site a = registerSite("新街口");
        Site b = registerSite("新街口");
        RateCard card = createCard(year);
        long v1 = firstVersionId(card);
        rateCardService.configure(v1, config(standardTiers(),
                price(a.getSiteNo(), "1000.00"), price(b.getSiteNo(), "99.99")));
        rateCardService.publish(v1, action());

        LocalDate submitDate = LocalDate.of(year, 6, 1);
        QuoteSubmitRequest req = quoteReq(uniqueNo("客户"), submitDate,
                line(a.getSiteNo(), 2),    // 一档：不打折
                line(a.getSiteNo(), 10),   // 二档：九折
                line(a.getSiteNo(), 20),   // 三档：八折
                line(b.getSiteNo(), 20));  // 99.99 × 0.8 = 79.992 → 79.99，验四舍五入到分

        // 先试算：不落库
        PricingResult preview = quoteService.preview(submitDate, req);
        assertEquals(4, preview.getLines().size());
        assertEquals(0, quoteService.list(req.getCustomerName(), null, null, null).size());

        QuoteDetailView saved = quoteService.submit(req);
        assertEquals(1, quoteService.list(req.getCustomerName(), null, null, null).size());
        assertEquals(year, saved.getQuote().getRateYear());
        assertEquals(1, saved.getQuote().getVersionNo());
        assertEquals(0, saved.getQuote().getTaxRate().compareTo(new BigDecimal("0.0600")));

        List<PricedLine> pl = preview.getLines();
        // 2 周 × 1000 不打折：未税 2000，税 120，含税 2120
        assertEquals(1, pl.get(0).getTierNo());
        assertEquals("1000.00", pl.get(0).getUnitPrice().toPlainString());
        assertEquals("2000.00", pl.get(0).getAmountExTax().toPlainString());
        assertEquals("120.00", pl.get(0).getTaxAmount().toPlainString());
        assertEquals("2120.00", pl.get(0).getAmountInclTax().toPlainString());
        // 10 周 × 九折单价 900：未税 9000，含税 9540
        assertEquals(2, pl.get(1).getTierNo());
        assertEquals("900.00", pl.get(1).getUnitPrice().toPlainString());
        assertEquals("9540.00", pl.get(1).getAmountInclTax().toPlainString());
        // 20 周 × 八折单价 800：未税 16000，含税 16960
        assertEquals(3, pl.get(2).getTierNo());
        assertEquals("800.00", pl.get(2).getUnitPrice().toPlainString());
        assertEquals("16960.00", pl.get(2).getAmountInclTax().toPlainString());
        // 四舍五入到分：99.99 × 0.8 = 79.992 → 79.99
        assertEquals("79.99", pl.get(3).getUnitPrice().toPlainString());
        assertEquals("1599.80", pl.get(3).getAmountExTax().toPlainString()); // 79.99 × 20

        // 未税/税额/含税三合计并列
        assertEquals("28599.80", saved.getQuote().getTotalExTax().toPlainString());
        assertEquals("1715.99", saved.getQuote().getTotalTax().toPlainString()); // 各行税额分别入账
        assertEquals("30315.79", saved.getQuote().getTotalInclTax().toPlainString());
        // 每行都固化了税率快照
        assertTrue(saved.getLines().stream()
                .allMatch(l -> l.getTaxRate().compareTo(new BigDecimal("0.0600")) == 0));
    }

    @Test
    void 历史报价不回写_后面调价旧单仍是当时单价快照() {
        int year = 2007;
        Site site = registerSite("春熙路");
        RateCard card = createCard(year);
        long v1Id = firstVersionId(card);
        rateCardService.configure(v1Id, config(standardTiers(), price(site.getSiteNo(), "1000")));
        rateCardService.publish(v1Id, action());

        String customer = uniqueNo("老客户");
        LocalDate day1 = LocalDate.of(year, 3, 1);
        QuoteDetailView oldQuote = quoteService.submit(
                quoteReq(customer, day1, line(site.getSiteNo(), 10)));
        String oldNo = oldQuote.getQuote().getQuoteNo();
        assertEquals("900.00", oldQuote.getLines().get(0).getUnitPrice().toPlainString());
        assertEquals(1, oldQuote.getQuote().getVersionNo());

        // 调价出 v2：1000 → 1200，档位不变
        RateCardVersion v2 = rateCardService.newVersion(card.getId(), action());
        rateCardService.configure(v2.getId(), config(standardTiers(), price(site.getSiteNo(), "1200")));
        rateCardService.publish(v2.getId(), action());

        // 新报价按新版算：1200 × 0.9 = 1080
        QuoteDetailView newQuote = quoteService.submit(
                quoteReq(customer, LocalDate.of(year, 8, 1), line(site.getSiteNo(), 10)));
        assertEquals(2, newQuote.getQuote().getVersionNo());
        assertEquals("1080.00", newQuote.getLines().get(0).getUnitPrice().toPlainString());

        // 翻旧单：单价、挂牌价、档位比例、税率、刊例版本全部是当时快照，一个数都没变
        QuoteDetailView refetched = quoteService.detail(oldNo);
        assertEquals(1, refetched.getQuote().getVersionNo());
        assertEquals(v1Id, refetched.getQuote().getVersionId());
        assertEquals("1000.00", refetched.getLines().get(0).getListPrice().toPlainString());
        assertEquals("0.9000", refetched.getLines().get(0).getDiscountRate().toPlainString());
        assertEquals("900.00", refetched.getLines().get(0).getUnitPrice().toPlainString());
        assertEquals("9540.00", refetched.getLines().get(0).getAmountInclTax().toPlainString());
        assertEquals("9540.00", refetched.getQuote().getTotalInclTax().toPlainString());
        // 点位档案字段也快照在行了
        assertEquals(site.getSiteNo(), refetched.getLines().get(0).getSiteNo());
        assertEquals("18m×6m", refetched.getLines().get(0).getSizeSpec());
    }

    @Test
    void 报价按提交当天的刊例_留空提交日取今天() {
        int year = LocalDate.now().getYear(); // 本年只此一用例占用
        Site site = registerSite("滨江道");
        RateCard card = createCard(year);
        long v1 = firstVersionId(card);
        rateCardService.configure(v1, config(standardTiers(), price(site.getSiteNo(), "1000")));
        rateCardService.publish(v1, action());

        QuoteDetailView quote = quoteService.submit(
                quoteReq(uniqueNo("当天客户"), null, line(site.getSiteNo(), 1)));
        assertEquals(year, quote.getQuote().getRateYear());
        assertEquals(LocalDate.now(), quote.getQuote().getSubmitDate());
        assertEquals(card.getId(), quote.getQuote().getRateCardId());
    }

    @Test
    void 未发布刊例_未挂价点位_未来日期_都不能报价() {
        int year = 2008;
        Site priced = registerSite("中山路");
        Site unpriced = registerSite("中山路");
        RateCard card = createCard(year);
        long v1 = firstVersionId(card);
        rateCardService.configure(v1, config(standardTiers(), price(priced.getSiteNo(), "1000")));
        // 故意不发布：年度刊例还在编制中

        BusinessException notPublished = assertThrows(BusinessException.class, () -> quoteService.submit(
                quoteReq(uniqueNo("客户"), LocalDate.of(year, 6, 1), line(priced.getSiteNo(), 1))));
        assertTrue(notPublished.getMessage().contains("尚未发布生效"));

        rateCardService.publish(v1, action());

        // 点位没挂进这版刊例
        BusinessException noPrice = assertThrows(BusinessException.class, () -> quoteService.submit(
                quoteReq(uniqueNo("客户"), LocalDate.of(year, 6, 1), line(unpriced.getSiteNo(), 1))));
        assertTrue(noPrice.getMessage().contains("未挂在"));

        // 报价日期晚于今天
        BusinessException future = assertThrows(BusinessException.class, () -> quoteService.submit(
                quoteReq(uniqueNo("客户"), LocalDate.now().plusDays(1), line(priced.getSiteNo(), 1))));
        assertTrue(future.getMessage().contains("不能晚于今天"));

        // 根本没建刊例的年份（1999 年无人建刊例）
        BusinessException noCard = assertThrows(BusinessException.class, () -> quoteService.submit(
                quoteReq(uniqueNo("客户"), LocalDate.of(1999, 6, 1), line(priced.getSiteNo(), 1))));
        assertTrue(noCard.getMessage().contains("尚未建档"));
    }

    @Test
    void 作废生效版本后不能报价_重新发布新版才恢复() {
        int year = 2009;
        Site site = registerSite("观前街");
        RateCard card = createCard(year);
        long v1Id = firstVersionId(card);
        rateCardService.configure(v1Id, config(standardTiers(), price(site.getSiteNo(), "1000")));
        rateCardService.publish(v1Id, action());

        quoteService.submit(quoteReq(uniqueNo("客户"), LocalDate.of(year, 6, 1), line(site.getSiteNo(), 2)));

        RateCardVersion revoked = rateCardService.revoke(v1Id, action());
        assertEquals("REVOKED", revoked.getStatus());
        assertNotNull(revoked.getRevokeTime());
        assertEquals("DRAFT", rateCardService.requireCard(card.getId()).getStatus());
        assertNull(rateCardService.requireCard(card.getId()).getCurrentVersionId());

        BusinessException ex = assertThrows(BusinessException.class, () -> quoteService.submit(
                quoteReq(uniqueNo("客户"), LocalDate.of(year, 7, 1), line(site.getSiteNo(), 2))));
        assertTrue(ex.getMessage().contains("尚未发布生效"));

        // 起新版重新发布后恢复报价（v2 从 v1 继承配置）
        RateCardVersion v2 = rateCardService.newVersion(card.getId(), action());
        rateCardService.publish(v2.getId(), action());
        QuoteDetailView again = quoteService.submit(
                quoteReq(uniqueNo("客户"), LocalDate.of(year, 8, 1), line(site.getSiteNo(), 2)));
        assertEquals(2, again.getQuote().getVersionNo());
    }

    @Test
    void 年度封存后不能起新版不能报价_历史报价仍可翻() {
        int year = 2010;
        Site site = registerSite("夫子庙");
        RateCard card = createCard(year);
        long v1Id = firstVersionId(card);
        rateCardService.configure(v1Id, config(standardTiers(), price(site.getSiteNo(), "1000")));
        rateCardService.publish(v1Id, action());
        QuoteDetailView old = quoteService.submit(
                quoteReq(uniqueNo("封存客户"), LocalDate.of(year, 1, 1), line(site.getSiteNo(), 2)));

        RateCard closed = rateCardService.closeCard(card.getId(), action());
        assertEquals("CLOSED", closed.getStatus());

        BusinessException noQuote = assertThrows(BusinessException.class, () -> quoteService.submit(
                quoteReq(uniqueNo("客户"), LocalDate.of(year, 6, 1), line(site.getSiteNo(), 2))));
        assertTrue(noQuote.getMessage().contains("已封存"));
        BusinessException noVersion = assertThrows(BusinessException.class,
                () -> rateCardService.newVersion(card.getId(), action()));
        assertTrue(noVersion.getMessage().contains("已封存"));

        // 历史报价照翻不误
        assertEquals(old.getQuote().getQuoteNo(),
                quoteService.detail(old.getQuote().getQuoteNo()).getQuote().getQuoteNo());
    }

    // ---- 财务阶梯对照表 ----

    @Test
    void 财务随查随拉_年度版本详情与阶梯对照表CSV() {
        int year = 2011;
        Site a = registerSite("东门");
        Site b = registerSite("西华门");
        RateCard card = createCard(year);
        long v1 = firstVersionId(card);
        rateCardService.configure(v1, config(standardTiers(),
                price(a.getSiteNo(), "1000"), price(b.getSiteNo(), "2000")));
        rateCardService.publish(v1, action());

        VersionDetailView detail = rateCardService.yearDetail(year);
        assertEquals(card.getId(), detail.getCardId());
        assertEquals("EFFECTIVE", detail.getVersion().getStatus());
        assertEquals(Arrays.asList(1, 2, 3),
                detail.getTiers().stream().map(t -> t.getTierNo()).collect(Collectors.toList()));
        assertEquals(2, detail.getSitePrices().size());

        String csv = rateCardService.tierTableCsv(year);
        assertEquals('﻿', csv.charAt(0)); // BOM：Excel 打开不乱码
        String[] lines = csv.split("\n");
        assertEquals(4, lines.length); // 表头 + 三档
        assertTrue(lines[0].contains("档位比例"));
        assertTrue(lines[1].contains("1~4 周") && lines[1].contains("1.0000") && lines[1].contains("不打折"));
        assertTrue(lines[2].contains("5~12 周") && lines[2].contains("0.9000") && lines[2].contains("9 折"));
        assertTrue(lines[3].contains("13 周及以上") && lines[3].contains("0.8000") && lines[3].contains("8 折"));
        assertTrue(lines[1].contains(String.valueOf(year)));
    }

    @Test
    void 底账版本登记包含f4起步版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f4-rate-card-1"));
    }
}
