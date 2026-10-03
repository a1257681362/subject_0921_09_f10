package com.evops.ratecard.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 点位刊例价视图：刊例行 + 点位档案信息，配置页与版本详情用。 */
@Data
public class SitePriceView {
    private Long siteId;
    private String siteNo;
    private String sizeSpec;
    private String orientation;
    private String businessDistrict;
    /** 挂牌周单价（未税，折前） */
    private BigDecimal listPrice;
}
