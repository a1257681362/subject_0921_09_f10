package com.evops.ratecard.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;

/**
 * 草稿版本配置（替换式）：整表提交档位比例与点位刊例价。
 * 只允许在 DRAFT 版本上配置；发布后的版本冻结，调价请另起新版。
 */
@Data
public class VersionConfigureRequest {
    /** 税率（小数，0.06=6%），留空保持版本原税率 */
    private BigDecimal taxRate;
    /** 阶梯档位（按最短周数升序，区间连续不重叠，首档从 1 周起，末档可开口） */
    @NotEmpty(message = "至少要配一档阶梯")
    @Valid
    private List<TierSpec> tiers;
    /** 点位刊例价（点位不能重复，必须是已挂牌点位） */
    @NotEmpty(message = "至少要给一个点位挂刊例价")
    @Valid
    private List<SitePriceSpec> sitePrices;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
