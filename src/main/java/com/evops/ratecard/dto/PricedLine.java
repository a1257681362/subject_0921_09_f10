package com.evops.ratecard.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 计价结果行（不落库的传输模型）：刊例价落档、折后单价、含税/未税都在这里一次算齐。
 * QuoteService 提交时把它复制成报价行快照；试算接口直接返回。
 */
@Data
public class PricedLine {
    private Long siteId;
    private String siteNo;
    private String sizeSpec;
    private String orientation;
    private String businessDistrict;
    private Integer weeks;
    /** 落档档位序号 */
    private Integer tierNo;
    private Integer minWeeks;
    private Integer maxWeeks;
    private BigDecimal discountRate;
    /** 挂牌周单价（未税，折前） */
    private BigDecimal listPrice;
    /** 折后周单价（未税） */
    private BigDecimal unitPrice;
    /** 未税金额 */
    private BigDecimal amountExTax;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    /** 含税金额（默认展示口径） */
    private BigDecimal amountInclTax;
}
