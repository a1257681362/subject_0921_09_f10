package com.evops.agent.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 协议中途调比例：一律从“下一结算期”生效，历史期不重算。
 * effectiveFromPeriod 留空取下一结算期；最早只能是下期，不能当期/追溯。
 */
@Data
public class AgreementAdjustRequest {
    /** 调整后代理分账比例（0~1） */
    @NotNull(message = "调整后比例不能为空")
    private BigDecimal commissionRate;
    /** 起始生效结算期 yyyy-MM；留空取下一结算期 */
    private String effectiveFromPeriod;
    /** 调整原因，留底 */
    private String reason;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
}
