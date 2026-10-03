package com.evops.agent.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 代理建档：绑定登录账号作为账单隔离边界。 */
@Data
public class AgentCreateRequest {
    /** 代理名称，全局唯一 */
    @NotBlank(message = "代理名称不能为空")
    private String agentName;
    /** 绑定的登录账号（代理角色），全局唯一；代理只能查该账号名下账单 */
    @NotBlank(message = "代理绑定登录账号不能为空")
    private String loginUsername;
    private String contactName;
    private String contactPhone;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
