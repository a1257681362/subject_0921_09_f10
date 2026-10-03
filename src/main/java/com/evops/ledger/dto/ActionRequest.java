package com.evops.ledger.dto;

import lombok.Data;

import java.time.LocalDate;

/** 落架 / 对冲动作入参。 */
@Data
public class ActionRequest {
    /** 动作发生日（落架日/对冲日），留空取今天 */
    private LocalDate occurDate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    /** 对冲原因 / 落架备注 */
    private String reason;
}
