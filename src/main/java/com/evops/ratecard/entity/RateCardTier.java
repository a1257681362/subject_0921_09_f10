package com.evops.ratecard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 阶梯档位：按购买档期长度（周）落档。
 * 区间 [minWeeks, maxWeeks] 闭区间，maxWeeks 为 null 表示“minWeeks 周及以上”；
 * discountRate 是档位比例（刊例价 × 比例 = 折后未税周单价），1 = 不打折、0.9 = 九折。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rate_card_tier")
public class RateCardTier extends BaseEntity {
    /** 所属刊例版本 */
    private Long versionId;
    /** 档位序号（从 1 起，按最短档期排） */
    private Integer tierNo;
    /** 本档最短周数（含） */
    private Integer minWeeks;
    /** 本档最长周数（含），null = 及以上 */
    private Integer maxWeeks;
    /** 档位比例（折扣率） */
    private BigDecimal discountRate;
}
