package com.evops.ratecard.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 点位刊例价配置：版本内每个点位一条挂牌周单价（未税）。 */
@Data
public class SitePriceSpec {
    /** 点位编号 */
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    /** 挂牌周单价（未税，折前） */
    @NotNull(message = "挂牌单价不能为空")
    @DecimalMin(value = "0.01", message = "挂牌单价必须大于 0")
    private BigDecimal listPrice;
}
