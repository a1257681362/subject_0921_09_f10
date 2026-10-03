package com.evops.install.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 改期：新档期必填，改期原因必填，改期要通知到队。 */
@Data
public class RescheduleRequest {
    @NotNull(message = "新计划开始时刻不能为空")
    private LocalDateTime newStart;
    @NotNull(message = "新计划结束时刻不能为空")
    private LocalDateTime newEnd;
    /** 改期原因（通知到队） */
    private String reason;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
}
