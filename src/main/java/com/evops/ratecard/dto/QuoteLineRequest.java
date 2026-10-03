package com.evops.ratecard.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 报价行请求：客户买哪个点位、买几周；价格与档位由刊例算，不许前端指定。 */
@Data
public class QuoteLineRequest {
    /** 点位编号 */
    @NotBlank(message = "报价行点位编号不能为空")
    private String siteNo;
    /** 购买档期长度（周），决定落哪一档阶梯折扣 */
    @NotNull(message = "购买周数不能为空")
    @Min(value = 1, message = "购买周数至少为 1")
    private Integer weeks;
}
