package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/** 版本回退：把挂起中单据恢复到某个历史版本快照，回退动作本身也留一条事件。 */
@Data
public class RollbackRequest {
    @NotNull(message = "目标版本号不能为空")
    private Integer targetVersion;
    /** 回退发生日，留空取今天 */
    private LocalDate occurDate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String reason;
}
