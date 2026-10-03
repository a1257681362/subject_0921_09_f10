package com.evops.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 批量冻结记录行：一张被冻单一行，客户与类目快照随行留底；
 * 处置状态 FROZEN 冻结中 / RELEASED 已解冻 / FORCE_CLOSED 强制落架。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_freeze_record")
public class ComplianceFreezeRecord extends BaseEntity {
    private Long batchId;
    private String batchNo;
    private Long orderId;
    private String orderNo;
    /** 授信单的客户快照；普通单为 null */
    private Long customerId;
    private String customerNo;
    private String customerName;
    /** 命中受限名单的行业类目（单上快照） */
    private String industryCategory;
    /** FROZEN 冻结中 / RELEASED 已解冻 / FORCE_CLOSED 强制落架 */
    private String status;
    /** 处置经手人 / 时刻 / 原因 */
    private String disposeOperator;
    private LocalDateTime disposeTime;
    private String disposeReason;
}
