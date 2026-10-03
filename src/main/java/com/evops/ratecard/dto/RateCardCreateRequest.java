package com.evops.ratecard.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 年度刊例建档请求：一个自然年一份。 */
@Data
public class RateCardCreateRequest {
    /** 刊例年份，例如 2026 */
    @NotNull(message = "刊例年份不能为空")
    @Min(value = 2000, message = "刊例年份不合法")
    @Max(value = 2200, message = "刊例年份不合法")
    private Integer rateYear;
    /** 刊例名称 */
    @NotBlank(message = "刊例名称不能为空")
    private String cardName;
    /** 税率（小数，0.06=6%），留空取系统默认税率 */
    private BigDecimal taxRate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
