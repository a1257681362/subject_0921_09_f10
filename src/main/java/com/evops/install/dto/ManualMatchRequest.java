package com.evops.install.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 人工补匹配（把待匹配派单派给指定安装队）/ 也可用于给已派单改派。
 * 资质（高空证有效期）与档期（队同一时段唯一）仍强制校验，不合格直接拒。
 */
@Data
public class ManualMatchRequest {
    /** 指定安装队编号 */
    @NotNull(message = "安装队编号不能为空")
    private String teamNo;
    /** 计划开始时刻，留空沿用派单当前档期（自动生成时已给默认档期） */
    private LocalDateTime planStart;
    /** 计划结束时刻，留空沿用当前档期 */
    private LocalDateTime planEnd;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
