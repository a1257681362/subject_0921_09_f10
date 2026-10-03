package com.evops.ratecard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 年度刊例：一个自然年一份主档，当前生效版本挂在 currentVersionId 上。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rate_card")
public class RateCard extends BaseEntity {
    /** 刊例编号，全局唯一 */
    private String cardNo;
    /** 刊例年份（自然年），同年只能有一份刊例 */
    private Integer rateYear;
    /** 刊例名称，例如 2026 年度户外 LED 刊例 */
    private String cardName;
    /** 状态：DRAFT 编制中 / EFFECTIVE 生效中 / CLOSED 已封存 */
    private String status;
    /** 当前生效（最新发布）版本 ID，从未发布为 null */
    private Long currentVersionId;
    /** 当前生效版本号 */
    private Integer currentVersionNo;
    /** 建档经手人 */
    private String operatorName;
    private String remark;
}
