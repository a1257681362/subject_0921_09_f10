package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 信用额度版本（只追加）：额度一动一个版本，前值后值都留着。
 * INITIAL 建档直接 EFFECTIVE（前值 0）；ADJUST/FREEZE/UNFREEZE 先 PENDING，财务审批后生效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_credit_limit_version")
public class CreditLimitVersion extends BaseEntity {
    private Long customerId;
    private Integer versionNo;
    /** INITIAL / ADJUST / FREEZE / UNFREEZE */
    private String changeType;
    private BigDecimal beforeLimit;
    private BigDecimal afterLimit;
    private String beforeStatus;
    private String afterStatus;
    /** PENDING / EFFECTIVE / REJECTED / SUPERSEDED */
    private String status;
    private String applicantName;
    private String applyReason;
    private LocalDateTime applyTime;
    private String approverName;
    private String approveComment;
    private LocalDateTime approveTime;
}
