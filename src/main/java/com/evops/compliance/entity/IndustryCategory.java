package com.evops.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 行业类目名单条目：一个编码一行，合规岗维护。
 * restricted=1 即受限：客户建档、授信上刊提交两个口命中当场拒，提示带 legalBasis 依据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_industry_category")
public class IndustryCategory extends BaseEntity {
    /** 类目编码，全局唯一（如 MEDICAL/EDUCATION/TOBACCO） */
    private String categoryCode;
    /** 类目名称（建档时把编码+名称快照固化到客户） */
    private String categoryName;
    /** ACTIVE 在档 / DISABLED 停用 */
    private String status;
    /** 是否受限：1 受限 / 0 普通 */
    private Integer restricted;
    /** 受限法规依据：命中拦截时原样进提示，让被拦的人知道凭什么 */
    private String legalBasis;
    /** 当前生效名单版本号 */
    private Integer currentVersionNo;
    private LocalDateTime restrictedTime;
    private LocalDateTime releasedTime;
    private String operatorName;
    private String remark;
}
