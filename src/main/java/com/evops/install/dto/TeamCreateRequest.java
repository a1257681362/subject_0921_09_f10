package com.evops.install.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 安装队建档。 */
@Data
public class TeamCreateRequest {
    /** 队名，全局唯一 */
    @NotBlank(message = "安装队名称不能为空")
    private String teamName;
    /** 队长/负责人 */
    @NotBlank(message = "队长不能为空")
    private String leaderName;
    /** 联系方式 */
    private String contact;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
