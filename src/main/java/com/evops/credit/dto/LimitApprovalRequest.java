package com.evops.credit.dto;

import lombok.Data;

/** 额度变更审批入参（财务）。 */
@Data
public class LimitApprovalRequest {
    /** 驳回/审批备注 */
    private String comment;
    /** 审批人，留空取当前登录账号（须财务/系统角色） */
    private String operatorName;
}
