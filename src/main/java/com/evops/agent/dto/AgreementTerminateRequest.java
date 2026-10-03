package com.evops.agent.dto;

import lombok.Data;

/** 协议终止：从下一结算期起不再分账，当期仍按协议结算，历史账单不动。 */
@Data
public class AgreementTerminateRequest {
    /** 终止起始结算期 yyyy-MM；留空取下一结算期 */
    private String effectiveFromPeriod;
    /** 终止原因，留底 */
    private String reason;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
}
