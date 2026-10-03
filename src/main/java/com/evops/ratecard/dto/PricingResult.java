package com.evops.ratecard.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 一次计价结果：按哪天、哪份刊例、哪版算的，加上各明细行与合计。
 * 报价提交与试算共用；提交时其中刊例坐标与价格字段全量固化进报价单/行。
 */
@Data
public class PricingResult {
    private LocalDate priceDate;
    private Long rateCardId;
    private String rateCardNo;
    private Integer rateYear;
    private Long versionId;
    private Integer versionNo;
    private BigDecimal taxRate;
    private List<PricedLine> lines;
    private BigDecimal totalExTax;
    private BigDecimal totalTax;
    private BigDecimal totalInclTax;
}
