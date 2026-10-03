package com.evops.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 批量冻结批次：一次"按类目集冻结在途单"一批，命中类目、客户数、单据数、经手人全留底。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_freeze_batch")
public class ComplianceFreezeBatch extends BaseEntity {
    /** 批次号 FR+序列，全局唯一 */
    private String batchNo;
    /** 冻结时生效的名单版本（留痕依据） */
    private Long listVersionId;
    private Integer listVersionNo;
    /** 本次冻结类目集（逗号分隔） */
    private String categories;
    /** 命中客户数 */
    private Integer customerCount;
    /** 实际冻结单据数 */
    private Integer orderCount;
    /** 冻结原因 */
    private String reason;
    /** 经手人（合规岗） */
    private String operatorName;
}
