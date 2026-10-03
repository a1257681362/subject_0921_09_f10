package com.evops.ratecard.dto;

import com.evops.ratecard.entity.RateCardTier;
import com.evops.ratecard.entity.RateCardVersion;
import lombok.Data;

import java.util.List;

/** 刊例版本详情：版本头 + 阶梯档位 + 点位刊例价，配置页与财务对照表共用。 */
@Data
public class VersionDetailView {
    private Long cardId;
    private String cardNo;
    private Integer rateYear;
    private String cardName;
    private RateCardVersion version;
    private List<RateCardTier> tiers;
    private List<SitePriceView> sitePrices;
}
