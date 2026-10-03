package com.evops.ratecard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ratecard.RateCardEnums;
import com.evops.ratecard.dto.SitePriceSpec;
import com.evops.ratecard.dto.SitePriceView;
import com.evops.ratecard.dto.TierSpec;
import com.evops.ratecard.dto.VersionActionRequest;
import com.evops.ratecard.dto.VersionConfigureRequest;
import com.evops.ratecard.dto.VersionDetailView;
import com.evops.ratecard.entity.RateCard;
import com.evops.ratecard.entity.RateCardSite;
import com.evops.ratecard.entity.RateCardTier;
import com.evops.ratecard.entity.RateCardVersion;
import com.evops.ratecard.mapper.RateCardMapper;
import com.evops.ratecard.mapper.RateCardSiteMapper;
import com.evops.ratecard.mapper.RateCardTierMapper;
import com.evops.ratecard.mapper.RateCardVersionMapper;
import com.evops.ratecard.support.RateCardProperties;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 年度刊例配置域：建档 → 草稿配档位/点位价 → 发布；调价只准另起新版再发布。
 *
 * 版本状态口径：
 * - DRAFT 草稿：可反复整表替换档位与点位价；
 * - EFFECTIVE 生效中：冻结，档位/点位价一个字都不能改，刊例头也不回写；
 * - SUPERSEDED 已被新版取代：新版发布时由系统置位，历史永久可翻；
 * - REVOKED 已作废：生效版发现录错时作废，作废后该年度需重新发布版本才能报价。
 * 年度刊例状态：DRAFT 编制中 / EFFECTIVE 生效中 / CLOSED 已封存（封存后不可起新版、不可报价）。
 */
@Service
public class RateCardService {

    private final RateCardMapper cardMapper;
    private final RateCardVersionMapper versionMapper;
    private final RateCardTierMapper tierMapper;
    private final RateCardSiteMapper cardSiteMapper;
    private final SiteMapper siteMapper;
    private final RateCardProperties properties;

    public RateCardService(RateCardMapper cardMapper,
                           RateCardVersionMapper versionMapper,
                           RateCardTierMapper tierMapper,
                           RateCardSiteMapper cardSiteMapper,
                           SiteMapper siteMapper,
                           RateCardProperties properties) {
        this.cardMapper = cardMapper;
        this.versionMapper = versionMapper;
        this.tierMapper = tierMapper;
        this.cardSiteMapper = cardSiteMapper;
        this.siteMapper = siteMapper;
        this.properties = properties;
    }

    /** 建年度刊例：一个自然年一份，同年自动带 v1 草稿。 */
    @Transactional(rollbackFor = Exception.class)
    public RateCard createCard(com.evops.ratecard.dto.RateCardCreateRequest request) {
        int year = request.getRateYear();
        Long exists = cardMapper.selectCount(
                new LambdaQueryWrapper<RateCard>().eq(RateCard::getRateYear, year));
        if (exists != null && exists > 0) {
            throw new BusinessException(year + " 年度刊例已存在，调价请在原刊例上另起新版本");
        }
        String operator = Operators.resolve(request.getOperatorName());

        RateCard card = new RateCard();
        card.setCardNo("RC" + year);
        card.setRateYear(year);
        card.setCardName(request.getCardName().trim());
        card.setStatus(RateCardEnums.CARD_DRAFT);
        card.setOperatorName(operator);
        card.setRemark(request.getRemark());
        try {
            cardMapper.insert(card);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(year + " 年度刊例已存在，调价请在原刊例上另起新版本");
        }

        RateCardVersion v1 = new RateCardVersion();
        v1.setCardId(card.getId());
        v1.setVersionNo(RateCardEnums.FIRST_VERSION_NO);
        v1.setStatus(RateCardEnums.VERSION_DRAFT);
        v1.setTaxRate(normalizeTaxRate(request.getTaxRate()));
        v1.setOperatorName(operator);
        versionMapper.insert(v1);
        return card;
    }

    /** 翻查年度刊例，可按年份过滤，新年份在前。 */
    public List<RateCard> listCards(Integer year) {
        LambdaQueryWrapper<RateCard> wrapper = new LambdaQueryWrapper<>();
        if (year != null) {
            wrapper.eq(RateCard::getRateYear, year);
        }
        return cardMapper.selectList(wrapper.orderByDesc(RateCard::getRateYear));
    }

    public RateCard requireCard(Long cardId) {
        RateCard card = cardMapper.selectById(cardId);
        if (card == null) {
            throw new BusinessException("年度刊例不存在: id=" + cardId);
        }
        return card;
    }

    /** 按自然年取刊例（含未发布），取不到返回 null。 */
    public RateCard findByYear(int year) {
        return cardMapper.selectOne(
                new LambdaQueryWrapper<RateCard>().eq(RateCard::getRateYear, year));
    }

    /** 版本翻查：新版本号在前。 */
    public List<RateCardVersion> listVersions(Long cardId) {
        requireCard(cardId);
        return versionMapper.selectList(new LambdaQueryWrapper<RateCardVersion>()
                .eq(RateCardVersion::getCardId, cardId)
                .orderByDesc(RateCardVersion::getVersionNo));
    }

    /**
     * 另起新版本（调价入口）：从上一版拷贝档位与点位价做起点，拿到的是可改的草稿。
     * 同一刊例同时只准有一个草稿；封存后的年度不能再起版。
     */
    @Transactional(rollbackFor = Exception.class)
    public RateCardVersion newVersion(Long cardId, VersionActionRequest request) {
        RateCard card = requireCard(cardId);
        if (RateCardEnums.CARD_CLOSED.equals(card.getStatus())) {
            throw new BusinessException(card.getRateYear() + " 年度刊例已封存，不能另起新版本");
        }
        Long drafts = versionMapper.selectCount(new LambdaQueryWrapper<RateCardVersion>()
                .eq(RateCardVersion::getCardId, cardId)
                .eq(RateCardVersion::getStatus, RateCardEnums.VERSION_DRAFT));
        if (drafts != null && drafts > 0) {
            throw new BusinessException("还有未发布的草稿版本，请先发布或作废该草稿后再起新版");
        }

        List<RateCardVersion> all = versionMapper.selectList(new LambdaQueryWrapper<RateCardVersion>()
                .eq(RateCardVersion::getCardId, cardId)
                .orderByDesc(RateCardVersion::getVersionNo));
        RateCardVersion latest = all.isEmpty() ? null : all.get(0);
        int nextNo = (latest == null ? 0 : latest.getVersionNo()) + 1;
        String operator = Operators.resolve(request.getOperatorName());

        RateCardVersion version = new RateCardVersion();
        version.setCardId(cardId);
        version.setVersionNo(nextNo);
        version.setStatus(RateCardEnums.VERSION_DRAFT);
        version.setTaxRate(latest != null ? latest.getTaxRate() : properties.getDefaultTaxRate());
        version.setOperatorName(operator);
        version.setRemark(request.getReason());
        versionMapper.insert(version);

        if (latest != null) {
            // 拷贝上一版档位与点位价做起点，改完再发布；历史版本身一个字不动
            copyTiers(latest.getId(), version.getId());
            copySitePrices(latest.getId(), version.getId());
        }
        return version;
    }

    /**
     * 草稿版本整表配置（替换式）：档位比例与点位刊例价一次性提交。
     * 只准动 DRAFT；生效版/历史版一律拒绝——调价走新版本，不许回写。
     */
    @Transactional(rollbackFor = Exception.class)
    public RateCardVersion configure(Long versionId, VersionConfigureRequest request) {
        RateCardVersion version = requireVersion(versionId);
        if (!RateCardEnums.VERSION_DRAFT.equals(version.getStatus())) {
            throw new BusinessException("只有草稿版本可配置；该版本是 " + version.getStatus()
                    + " 状态，已冻结，调价请另起新版本");
        }
        List<TierSpec> tiers = validateTiers(request.getTiers());
        Map<String, Site> sites = validateSitePrices(request.getSitePrices());
        String operator = Operators.resolve(request.getOperatorName());

        if (request.getTaxRate() != null) {
            version.setTaxRate(normalizeTaxRate(request.getTaxRate()));
        }
        version.setOperatorName(operator);
        if (request.getRemark() != null) {
            version.setRemark(request.getRemark());
        }
        versionMapper.updateById(version);

        tierMapper.delete(new LambdaQueryWrapper<RateCardTier>()
                .eq(RateCardTier::getVersionId, versionId));
        cardSiteMapper.delete(new LambdaQueryWrapper<RateCardSite>()
                .eq(RateCardSite::getVersionId, versionId));

        int tierNo = 0;
        for (TierSpec spec : tiers) {
            tierNo++;
            RateCardTier tier = new RateCardTier();
            tier.setVersionId(versionId);
            tier.setTierNo(tierNo);
            tier.setMinWeeks(spec.getMinWeeks());
            tier.setMaxWeeks(spec.getMaxWeeks());
            tier.setDiscountRate(spec.getDiscountRate().setScale(4, java.math.RoundingMode.HALF_UP));
            tierMapper.insert(tier);
        }
        for (SitePriceSpec spec : request.getSitePrices()) {
            Site site = sites.get(spec.getSiteNo().trim());
            RateCardSite cardSite = new RateCardSite();
            cardSite.setVersionId(versionId);
            cardSite.setSiteId(site.getId());
            cardSite.setListPrice(spec.getListPrice().setScale(2, java.math.RoundingMode.HALF_UP));
            cardSiteMapper.insert(cardSite);
        }
        return version;
    }

    /** 发布草稿：校验配置齐全；旧生效版置 SUPERSEDED，本版挂为年度当前版本。 */
    @Transactional(rollbackFor = Exception.class)
    public RateCardVersion publish(Long versionId, VersionActionRequest request) {
        RateCardVersion version = requireVersion(versionId);
        RateCard card = requireCard(version.getCardId());
        if (RateCardEnums.CARD_CLOSED.equals(card.getStatus())) {
            throw new BusinessException(card.getRateYear() + " 年度刊例已封存，不能发布新版本");
        }
        if (!RateCardEnums.VERSION_DRAFT.equals(version.getStatus())) {
            throw new BusinessException("只有草稿版本可发布，该版本当前状态: " + version.getStatus());
        }
        Long tierCount = tierMapper.selectCount(new LambdaQueryWrapper<RateCardTier>()
                .eq(RateCardTier::getVersionId, versionId));
        Long siteCount = cardSiteMapper.selectCount(new LambdaQueryWrapper<RateCardSite>()
                .eq(RateCardSite::getVersionId, versionId));
        if (tierCount == null || tierCount == 0) {
            throw new BusinessException("阶梯档位还没配置，不能发布");
        }
        if (siteCount == null || siteCount == 0) {
            throw new BusinessException("还没给任何点位挂刊例价，不能发布");
        }
        // 发布前再用库里实际配置过一遍连续性（防直接落库的脏数据）
        validateTiers(loadTierSpecs(versionId));

        Operators.resolve(request.getOperatorName());

        RateCardVersion previous = null;
        if (card.getCurrentVersionId() != null) {
            previous = versionMapper.selectById(card.getCurrentVersionId());
        }
        LocalDateTime now = LocalDateTime.now();

        version.setStatus(RateCardEnums.VERSION_EFFECTIVE);
        version.setPublishTime(now);
        versionMapper.updateById(version);

        if (previous != null && RateCardEnums.VERSION_EFFECTIVE.equals(previous.getStatus())) {
            RateCardVersion superseded = new RateCardVersion();
            superseded.setId(previous.getId());
            superseded.setStatus(RateCardEnums.VERSION_SUPERSEDED);
            versionMapper.updateById(superseded);
        }

        RateCard cardUpdate = new RateCard();
        cardUpdate.setId(card.getId());
        cardUpdate.setStatus(RateCardEnums.CARD_EFFECTIVE);
        cardUpdate.setCurrentVersionId(version.getId());
        cardUpdate.setCurrentVersionNo(version.getVersionNo());
        cardMapper.updateById(cardUpdate);
        return version;
    }

    /** 作废生效版本：版本置 REVOKED 留底；年度回到“无生效版本”，重新发布新版后才能报价。 */
    @Transactional(rollbackFor = Exception.class)
    public RateCardVersion revoke(Long versionId, VersionActionRequest request) {
        RateCardVersion version = requireVersion(versionId);
        if (!RateCardEnums.VERSION_EFFECTIVE.equals(version.getStatus())) {
            throw new BusinessException("只有生效中的版本可作废，该版本当前状态: " + version.getStatus());
        }
        Operators.resolve(request.getOperatorName());

        version.setStatus(RateCardEnums.VERSION_REVOKED);
        version.setRevokeTime(LocalDateTime.now());
        version.setRevokeReason(request.getReason());
        versionMapper.updateById(version);

        RateCard card = requireCard(version.getCardId());
        // updateById 默认不更新 null，清空当前版本指针必须用 UpdateWrapper#set 显式置空
        cardMapper.update(null, new LambdaUpdateWrapper<RateCard>()
                .eq(RateCard::getId, card.getId())
                .set(RateCard::getStatus, RateCardEnums.CARD_DRAFT)
                .set(RateCard::getCurrentVersionId, null)
                .set(RateCard::getCurrentVersionNo, null));
        return version;
    }

    /** 年度封存：封存后不能再起新版、不能报价（历史报价不受影响）。 */
    @Transactional(rollbackFor = Exception.class)
    public RateCard closeCard(Long cardId, VersionActionRequest request) {
        RateCard card = requireCard(cardId);
        if (RateCardEnums.CARD_CLOSED.equals(card.getStatus())) {
            throw new BusinessException(card.getRateYear() + " 年度刊例已封存，请勿重复操作");
        }
        if (!RateCardEnums.CARD_EFFECTIVE.equals(card.getStatus())) {
            throw new BusinessException("只有生效中的年度刊例可封存（编制中的刊例请先发布生效版本）");
        }
        Operators.resolve(request.getOperatorName());

        RateCard update = new RateCard();
        update.setId(cardId);
        update.setStatus(RateCardEnums.CARD_CLOSED);
        if (request.getReason() != null) {
            update.setRemark(request.getReason());
        }
        cardMapper.updateById(update);
        return cardMapper.selectById(cardId);
    }

    /** 版本详情：版本头 + 档位（档位序号升序）+ 点位刊例价（点位编号升序）。 */
    public VersionDetailView versionDetail(Long versionId) {
        RateCardVersion version = requireVersion(versionId);
        RateCard card = requireCard(version.getCardId());
        return buildDetail(card, version);
    }

    /** 财务随查随拉：某年当前版本（生效版优先，否则最新版）的阶梯对照详情。 */
    public VersionDetailView yearDetail(int year) {
        RateCard card = findByYear(year);
        if (card == null) {
            throw new BusinessException(year + " 年度刊例尚未建档");
        }
        RateCardVersion version;
        if (card.getCurrentVersionId() != null) {
            version = versionMapper.selectById(card.getCurrentVersionId());
        } else {
            version = versionMapper.selectList(new LambdaQueryWrapper<RateCardVersion>()
                            .eq(RateCardVersion::getCardId, card.getId())
                            .orderByDesc(RateCardVersion::getVersionNo))
                    .stream().findFirst().orElse(null);
        }
        if (version == null) {
            throw new BusinessException(year + " 年度刊例还没有任何版本");
        }
        return buildDetail(card, version);
    }

    /**
     * 阶梯对照表 CSV（财务直接拉）：年份、版本、档位、周数区间、档位比例、中文折扣、状态。
     * BOM 头由调用方按 UTF-8 写出，Excel 打开不乱码。
     */
    public String tierTableCsv(int year) {
        VersionDetailView detail = yearDetail(year);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("刊例年份,刊例编号,版本号,版本状态,税率,档位,周数区间,档位比例,折扣(中文)\n");
        for (RateCardTier tier : detail.getTiers()) {
            csv.append(detail.getRateYear()).append(',')
                    .append(escape(detail.getCardNo())).append(',')
                    .append("v").append(detail.getVersion().getVersionNo()).append(',')
                    .append(escape(versionStatusLabel(detail.getVersion().getStatus()))).append(',')
                    .append(detail.getVersion().getTaxRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString())
                    .append("%,").append(tier.getTierNo()).append(',')
                    .append(escape(weekRange(tier))).append(',')
                    .append(tier.getDiscountRate().toPlainString()).append(',')
                    .append(escape(chineseDiscount(tier.getDiscountRate())))
                    .append('\n');
        }
        return csv.toString();
    }

    private VersionDetailView buildDetail(RateCard card, RateCardVersion version) {
        VersionDetailView view = new VersionDetailView();
        view.setCardId(card.getId());
        view.setCardNo(card.getCardNo());
        view.setRateYear(card.getRateYear());
        view.setCardName(card.getCardName());
        view.setVersion(version);

        List<RateCardTier> tiers = tierMapper.selectList(new LambdaQueryWrapper<RateCardTier>()
                .eq(RateCardTier::getVersionId, version.getId())
                .orderByAsc(RateCardTier::getTierNo));
        view.setTiers(tiers);

        List<RateCardSite> cardSites = cardSiteMapper.selectList(new LambdaQueryWrapper<RateCardSite>()
                .eq(RateCardSite::getVersionId, version.getId()));
        if (cardSites.isEmpty()) {
            view.setSitePrices(new ArrayList<>());
            return view;
        }
        List<Site> sites = siteMapper.selectBatchIds(cardSites.stream()
                .map(RateCardSite::getSiteId).collect(Collectors.toList()));
        Map<Long, Site> siteById = sites.stream()
                .collect(Collectors.toMap(Site::getId, s -> s));
        List<SitePriceView> prices = new ArrayList<>();
        for (RateCardSite cs : cardSites) {
            Site site = siteById.get(cs.getSiteId());
            SitePriceView price = new SitePriceView();
            price.setSiteId(cs.getSiteId());
            if (site != null) {
                price.setSiteNo(site.getSiteNo());
                price.setSizeSpec(site.getSizeSpec());
                price.setOrientation(site.getOrientation());
                price.setBusinessDistrict(site.getBusinessDistrict());
            }
            price.setListPrice(cs.getListPrice());
            prices.add(price);
        }
        prices.sort(Comparator.comparing(
                (SitePriceView p) -> p.getSiteNo() == null ? "" : p.getSiteNo()));
        view.setSitePrices(prices);
        return view;
    }

    private void copyTiers(Long fromVersionId, Long toVersionId) {
        List<RateCardTier> tiers = tierMapper.selectList(new LambdaQueryWrapper<RateCardTier>()
                .eq(RateCardTier::getVersionId, fromVersionId)
                .orderByAsc(RateCardTier::getTierNo));
        for (RateCardTier src : tiers) {
            RateCardTier tier = new RateCardTier();
            tier.setVersionId(toVersionId);
            tier.setTierNo(src.getTierNo());
            tier.setMinWeeks(src.getMinWeeks());
            tier.setMaxWeeks(src.getMaxWeeks());
            tier.setDiscountRate(src.getDiscountRate());
            tierMapper.insert(tier);
        }
    }

    private void copySitePrices(Long fromVersionId, Long toVersionId) {
        List<RateCardSite> prices = cardSiteMapper.selectList(new LambdaQueryWrapper<RateCardSite>()
                .eq(RateCardSite::getVersionId, fromVersionId));
        for (RateCardSite src : prices) {
            RateCardSite price = new RateCardSite();
            price.setVersionId(toVersionId);
            price.setSiteId(src.getSiteId());
            price.setListPrice(src.getListPrice());
            cardSiteMapper.insert(price);
        }
    }

    private RateCardVersion requireVersion(Long versionId) {
        RateCardVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("刊例版本不存在: id=" + versionId);
        }
        return version;
    }

    private List<TierSpec> loadTierSpecs(Long versionId) {
        return tierMapper.selectList(new LambdaQueryWrapper<RateCardTier>()
                        .eq(RateCardTier::getVersionId, versionId)
                        .orderByAsc(RateCardTier::getTierNo)).stream().map(t -> {
            TierSpec spec = new TierSpec();
            spec.setMinWeeks(t.getMinWeeks());
            spec.setMaxWeeks(t.getMaxWeeks());
            spec.setDiscountRate(t.getDiscountRate());
            return spec;
        }).collect(Collectors.toList());
    }

    /**
     * 档位口径校验：
     * 首档必须从 1 周起；按 minWeeks 升序后区间首尾相接（上档 maxWeeks+1 = 下档 minWeeks）、不重叠；
     * 开口档（maxWeeks 空）只能是最后一档；比例在 (0,1] 且随周数不增（买得越长越便宜）。
     */
    private List<TierSpec> validateTiers(List<TierSpec> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException("至少要配一档阶梯");
        }
        List<TierSpec> tiers = new ArrayList<>(raw);
        tiers.sort(Comparator.comparing(TierSpec::getMinWeeks));
        if (tiers.get(0).getMinWeeks() != 1) {
            throw new BusinessException("阶梯首档必须从 1 周起");
        }
        for (int i = 0; i < tiers.size(); i++) {
            TierSpec t = tiers.get(i);
            if (t.getMaxWeeks() != null && t.getMaxWeeks() < t.getMinWeeks()) {
                throw new BusinessException("第 " + (i + 1) + " 档周数区间不合法：最长周数不能小于最短周数");
            }
            if (t.getDiscountRate() == null
                    || t.getDiscountRate().signum() <= 0
                    || t.getDiscountRate().compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessException("第 " + (i + 1) + " 档档位比例必须在 (0,1] 之间");
            }
            if (i < tiers.size() - 1) {
                if (t.getMaxWeeks() == null) {
                    throw new BusinessException("“及以上”开口档只能放在最后一档");
                }
                TierSpec next = tiers.get(i + 1);
                if (next.getMinWeeks() != t.getMaxWeeks() + 1) {
                    throw new BusinessException("阶梯周数必须首尾相接不许断档/重叠：第 " + (i + 1)
                            + " 档止于 " + t.getMaxWeeks() + " 周，下一档应从 " + (t.getMaxWeeks() + 1)
                            + " 周起，实际从 " + next.getMinWeeks() + " 周起");
                }
                if (next.getDiscountRate().compareTo(t.getDiscountRate()) > 0) {
                    throw new BusinessException("档位比例应随购买周数递减（买得越长越便宜），第 "
                            + (i + 2) + " 档比例不能高于第 " + (i + 1) + " 档");
                }
            }
        }
        return tiers;
    }

    /** 点位刊例价校验：点位必须已挂牌、编号不重复、单价为正。 */
    private Map<String, Site> validateSitePrices(List<SitePriceSpec> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException("至少要给一个点位挂刊例价");
        }
        Set<String> seen = new HashSet<>();
        for (SitePriceSpec spec : raw) {
            String no = spec.getSiteNo().trim();
            if (!seen.add(no)) {
                throw new BusinessException("点位 " + no + " 的刊例价重复配置了，请合并成一行");
            }
            if (spec.getListPrice() == null || spec.getListPrice().signum() <= 0) {
                throw new BusinessException("点位 " + no + " 的挂牌单价必须大于 0");
            }
        }
        List<Site> sites = siteMapper.selectList(new LambdaQueryWrapper<Site>()
                .in(Site::getSiteNo, seen));
        Map<String, Site> byNo = new HashMap<>();
        for (Site site : sites) {
            byNo.put(site.getSiteNo(), site);
        }
        for (String no : seen) {
            if (!byNo.containsKey(no)) {
                throw new BusinessException("点位 " + no + " 不存在，请先挂牌建档再挂刊例价");
            }
        }
        return byNo;
    }

    /** 税率口径：[0,1) 之间的四位小数，留空取默认 6%。 */
    private BigDecimal normalizeTaxRate(BigDecimal taxRate) {
        if (taxRate == null) {
            return properties.getDefaultTaxRate();
        }
        if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new BusinessException("税率必须是 0 到 1 之间的小数（如 0.06 表示 6%）");
        }
        return taxRate.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /** 周数区间文本：1~4 周 / 13 周及以上。 */
    public static String weekRange(RateCardTier tier) {
        if (tier.getMaxWeeks() == null) {
            return tier.getMinWeeks() + " 周及以上";
        }
        if (tier.getMinWeeks().equals(tier.getMaxWeeks())) {
            return tier.getMinWeeks() + " 周";
        }
        return tier.getMinWeeks() + "~" + tier.getMaxWeeks() + " 周";
    }

    /** 档位比例转中文折扣：0.85 → 8.5 折，1 → 不打折。 */
    public static String chineseDiscount(BigDecimal rate) {
        if (rate.compareTo(BigDecimal.ONE) == 0) {
            return "不打折";
        }
        BigDecimal zhe = rate.multiply(BigDecimal.TEN).stripTrailingZeros();
        return zhe.toPlainString() + " 折";
    }

    /** 版本状态中文标签。 */
    public static String versionStatusLabel(String status) {
        switch (status) {
            case RateCardEnums.VERSION_DRAFT: return "草稿";
            case RateCardEnums.VERSION_EFFECTIVE: return "生效中";
            case RateCardEnums.VERSION_SUPERSEDED: return "已被新版取代";
            case RateCardEnums.VERSION_REVOKED: return "已作废";
            default: return status;
        }
    }
}
