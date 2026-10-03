package com.evops.ratecard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 年度刊例版本：草稿配档位与点位价，发布后冻结；调价另起新版，旧版置 SUPERSEDED。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rate_card_version")
public class RateCardVersion extends BaseEntity {
    /** 所属年度刊例 */
    private Long cardId;
    /** 版本号，刊例内单调递增，首版 v1 */
    private Integer versionNo;
    /** 版本状态：DRAFT 草稿 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代 / REVOKED 已作废 */
    private String status;
    /** 税率（小数，0.06 = 6%），挂牌单价为未税价，含税价按它算 */
    private BigDecimal taxRate;
    /** 发布时刻（生效起点） */
    private LocalDateTime publishTime;
    /** 作废时刻 */
    private LocalDateTime revokeTime;
    /** 作废原因 */
    private String revokeReason;
    /** 经手人 */
    private String operatorName;
    private String remark;
}
