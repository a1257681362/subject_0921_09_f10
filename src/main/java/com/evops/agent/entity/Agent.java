package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代理档案：一个代理一行。loginUsername 绑定登录账号，是“代理只能查自己账单”的隔离边界。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_agent")
public class Agent extends BaseEntity {
    /** 代理编号，全局唯一 */
    private String agentNo;
    /** 代理名称，全局唯一 */
    private String agentName;
    /** 绑定登录账号（代理角色），账单按它隔离 */
    private String loginUsername;
    private String contactName;
    private String contactPhone;
    /** ACTIVE 在册 / DISABLED 停用 */
    private String status;
    /** 建档经手人 */
    private String operatorName;
    private String remark;
}
