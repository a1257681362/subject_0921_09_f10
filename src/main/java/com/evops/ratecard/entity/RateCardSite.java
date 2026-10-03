package com.evops.ratecard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 点位刊例行：版本内每个点位一条挂牌周单价（未税）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rate_card_site")
public class RateCardSite extends BaseEntity {
    /** 所属刊例版本 */
    private Long versionId;
    /** 点位 ID（t_site.id） */
    private Long siteId;
    /** 挂牌周单价（未税），折前 */
    private BigDecimal listPrice;
}
