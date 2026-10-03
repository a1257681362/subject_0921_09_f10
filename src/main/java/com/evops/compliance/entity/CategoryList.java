package com.evops.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 受限行业类目名单主档：全公司一份（schema 预置），当前生效版本指针随发布更新。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_category_list")
public class CategoryList extends BaseEntity {
    /** 名单编号 RCL+序列 */
    private String listNo;
    private String listName;
    /** ACTIVE 在用 */
    private String status;
    private Long currentVersionId;
    private Integer currentVersionNo;
    private String operatorName;
    private String remark;
}
