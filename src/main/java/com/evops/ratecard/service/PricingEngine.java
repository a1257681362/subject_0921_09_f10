package com.evops.ratecard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ratecard.RateCardEnums;
import com.evops.ratecard.dto.PricedLine;
import com.evops.ratecard.dto.PricingResult;
import com.evops.ratecard.dto.QuoteLineRequest;
import com.evops.ratecard.entity.RateCard;
import com.evops.ratecard.entity.RateCardSite;
import com.evops.ratecard.entity.RateCardTier;
import com.evops.ratecard.entity.RateCardVersion;
import com.evops.ratecard.mapper.RateCardMapper;
import com.evops.ratecard.mapper.RateCardSiteMapper;
import com.evops.ratecard.mapper.RateCardTierMapper;
import com.evops.ratecard.mapper.RateCardVersionMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 刊例计价的统一入口——全系统算价只走这一处，不散在各接口里。
 *
 * 口径：
 * 1. 报价按提交当天所在自然年取“生效中”的年度刊例与其生效版本；没发布的年度刊例不能报价。
 * 2. 每个点位必须挂在该版本的刊例上（有未税挂牌周单价），否则拒绝，不猜价。
 * 3. 按购买档期长度（周）落阶梯档：档位 [minWeeks, maxWeeks] 闭区间，maxWeeks 空表示及以上。
 * 4. 折后未税单价 = 挂牌单价 × 档位比例，四舍五入到分；行金额 = 折后单价 × 周数；
 *    税额 = 未税金额 × 税率；含税金额 = 未税 + 税额。含税、未税并列算出，默认展示含税。
 * 算价结果不写库；是否固化、怎么固化由调用方（报价服务）决定，历史报价因此天然不被回写。
 */
@Service
public class PricingEngine {

    /** 金额一律保留到分 */
    public static final int MONEY_SCALE = 2;

    private final RateCardMapper cardMapper;
    private final RateCardVersionMapper versionMapper;
    private final RateCardTierMapper tierMapper;
    private final RateCardSiteMapper cardSiteMapper;
    private final SiteMapper siteMapper;

    public PricingEngine(RateCardMapper cardMapper,
                         RateCardVersionMapper versionMapper,
                         RateCardTierMapper tierMapper,
                         RateCardSiteMapper cardSiteMapper,
                         SiteMapper siteMapper) {
        this.cardMapper = cardMapper;
        this.versionMapper = versionMapper;
        this.tierMapper = tierMapper;
        this.cardSiteMapper = cardSiteMapper;
        this.siteMapper = siteMapper;
    }

    /**
     * 按提交日取生效刊例并给整批报价行算价。
     *
     * @param priceDate 提交日（决定用哪年的哪版刊例）
     * @param requests  客户购买的点位 + 周数（价格一律不接受外部传入）
     */
    public PricingResult price(LocalDate priceDate, List<QuoteLineRequest> requests) {
        EffectiveRate effective = requireEffective(priceDate);
        List<RateCardTier> tiers = tierMapper.selectList(new LambdaQueryWrapper<RateCardTier>()
                .eq(RateCardTier::getVersionId, effective.version.getId())
                .orderByAsc(RateCardTier::getTierNo));
        List<RateCardSite> cardSites = cardSiteMapper.selectList(new LambdaQueryWrapper<RateCardSite>()
                .eq(RateCardSite::getVersionId, effective.version.getId()));
        Map<Long, RateCardSite> priceBySite = new HashMap<>();
        for (RateCardSite cs : cardSites) {
            priceBySite.put(cs.getSiteId(), cs);
        }

        List<PricedLine> lines = new ArrayList<>();
        BigDecimal totalEx = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalIncl = BigDecimal.ZERO;

        int lineNo = 0;
        for (QuoteLineRequest req : requests) {
            lineNo++;
            String siteNo = req.getSiteNo().trim();
            Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>().eq(Site::getSiteNo, siteNo));
            if (site == null) {
                throw new BusinessException("第 " + lineNo + " 行点位不存在: " + siteNo);
            }
            RateCardSite cardSite = priceBySite.get(site.getId());
            if (cardSite == null) {
                throw new BusinessException("点位 " + siteNo + " 未挂在 " + effective.card.getRateYear()
                        + " 年度生效刊例上，无法报价");
            }
            RateCardTier tier = matchTier(tiers, req.getWeeks());
            if (tier == null) {
                // 正常不会走到：发布前档位已校验连续覆盖；这是计价入口的最后一道防线
                throw new BusinessException("点位 " + siteNo + " 购买 " + req.getWeeks()
                        + " 周，落不进任何阶梯档位，刊例档位配置有缺口");
            }

            BigDecimal listPrice = cardSite.getListPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal unitPrice = listPrice.multiply(tier.getDiscountRate())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal amountEx = unitPrice.multiply(BigDecimal.valueOf(req.getWeeks()))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal taxAmount = amountEx.multiply(effective.version.getTaxRate())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal amountIncl = amountEx.add(taxAmount);

            PricedLine line = new PricedLine();
            line.setSiteId(site.getId());
            line.setSiteNo(site.getSiteNo());
            line.setSizeSpec(site.getSizeSpec());
            line.setOrientation(site.getOrientation());
            line.setBusinessDistrict(site.getBusinessDistrict());
            line.setWeeks(req.getWeeks());
            line.setTierNo(tier.getTierNo());
            line.setMinWeeks(tier.getMinWeeks());
            line.setMaxWeeks(tier.getMaxWeeks());
            line.setDiscountRate(tier.getDiscountRate());
            line.setListPrice(listPrice);
            line.setUnitPrice(unitPrice);
            line.setAmountExTax(amountEx);
            line.setTaxRate(effective.version.getTaxRate());
            line.setTaxAmount(taxAmount);
            line.setAmountInclTax(amountIncl);
            lines.add(line);

            totalEx = totalEx.add(amountEx);
            totalTax = totalTax.add(taxAmount);
            totalIncl = totalIncl.add(amountIncl);
        }

        PricingResult result = new PricingResult();
        result.setPriceDate(priceDate);
        result.setRateCardId(effective.card.getId());
        result.setRateCardNo(effective.card.getCardNo());
        result.setRateYear(effective.card.getRateYear());
        result.setVersionId(effective.version.getId());
        result.setVersionNo(effective.version.getVersionNo());
        result.setTaxRate(effective.version.getTaxRate());
        result.setLines(lines);
        result.setTotalExTax(totalEx);
        result.setTotalTax(totalTax);
        result.setTotalInclTax(totalIncl);
        return result;
    }

    /** 取某天所在年度的生效刊例 + 生效版本；缺失即拒收。 */
    public EffectiveRate requireEffective(LocalDate priceDate) {
        if (priceDate == null) {
            throw new BusinessException("报价日期不能为空");
        }
        if (priceDate.isAfter(LocalDate.now())) {
            throw new BusinessException("报价日期不能晚于今天，刊例按提交当天算");
        }
        RateCard card = cardMapper.selectOne(new LambdaQueryWrapper<RateCard>()
                .eq(RateCard::getRateYear, priceDate.getYear()));
        if (card == null) {
            throw new BusinessException(priceDate.getYear() + " 年度刊例尚未建档，无法按 " + priceDate + " 报价");
        }
        if (RateCardEnums.CARD_CLOSED.equals(card.getStatus())) {
            throw new BusinessException(priceDate.getYear() + " 年度刊例已封存，不能再按该年度提交新报价");
        }
        if (!RateCardEnums.CARD_EFFECTIVE.equals(card.getStatus()) || card.getCurrentVersionId() == null) {
            throw new BusinessException(priceDate.getYear() + " 年度刊例尚未发布生效，无法按 "
                    + priceDate + " 报价");
        }
        RateCardVersion version = versionMapper.selectById(card.getCurrentVersionId());
        if (version == null || !RateCardEnums.VERSION_EFFECTIVE.equals(version.getStatus())) {
            // 数据不自洽：刊例指向的版本不是生效版，属配置异常，宁可拒绝也不按错版报价
            throw new BusinessException(priceDate.getYear() + " 年度刊例当前版本不是生效状态，请检查刊例配置");
        }
        return new EffectiveRate(card, version);
    }

    /** 按周数落阶梯档：命中 [minWeeks, maxWeeks] 闭区间；maxWeeks 空表示及以上。 */
    public static RateCardTier matchTier(List<RateCardTier> tiers, int weeks) {
        for (RateCardTier tier : tiers) {
            boolean geMin = weeks >= tier.getMinWeeks();
            boolean leMax = tier.getMaxWeeks() == null || weeks <= tier.getMaxWeeks();
            if (geMin && leMax) {
                return tier;
            }
        }
        return null;
    }

    /** 生效刊例与版本的一对坐标。 */
    public static class EffectiveRate {
        private final RateCard card;
        private final RateCardVersion version;

        public EffectiveRate(RateCard card, RateCardVersion version) {
            this.card = card;
            this.version = version;
        }

        public RateCard getCard() { return card; }
        public RateCardVersion getVersion() { return version; }
    }
}
