package com.evops.playlist.dto;

import lombok.Data;

/** 锁版改单的审批动作（通过/驳回），可带审批意见。 */
@Data
public class ChangeApprovalRequest {
    private String comment;
    /** 审批人，留空取当前登录账号（须财务/系统角色，且不能审批自己的申请） */
    private String operatorName;
}
