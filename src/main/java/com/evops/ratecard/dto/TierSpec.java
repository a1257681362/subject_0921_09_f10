package com.evops.ratecard.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 阶梯档位配置：按购买档期长度（周）落档，区间闭区间，maxWeeks 留空表示“及以上”。 */
@Data
public class TierSpec {
    /** 本档最短周数（含），从 1 起 */
    @NotNull(message = "档位最短周数不能为空")
    @Min(value = 1, message = "档位最短周数至少为 1")
    private Integer minWeeks;
    /** 本档最长周数（含），null = minWeeks 周及以上（只能出现在最后一档） */
    private Integer maxWeeks;
    /** 档位比例（刊例价 × 比例 = 折后单价），(0,1]，1 表示不打折 */
    @NotNull(message = "档位比例不能为空")
    @DecimalMin(value = "0.0001", message = "档位比例必须大于 0")
    @DecimalMax(value = "1.0000", message = "档位比例不能大于 1（不允许加价）")
    private BigDecimal discountRate;
}
