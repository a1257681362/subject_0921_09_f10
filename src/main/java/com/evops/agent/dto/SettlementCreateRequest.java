package com.evops.agent.dto;

import lombok.Data;

/** 触发结算：按结算期对（指定代理或全部代理）落结算快照。 */
@Data
public class SettlementCreateRequest {
    /** 结算归属期 yyyy-MM；留空取当前结算期，不许结未来期 */
    private String period;
    /** 指定只结某代理；留空结全部在册代理 */
    private String agentNo;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
