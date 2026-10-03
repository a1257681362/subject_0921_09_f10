package com.evops.agent.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;

/** 点位挂代理协议：一个代理在一个点位挂一份，比例与有效期（起始结算期）定清楚。 */
@Data
public class AgreementCreateRequest {
    @NotBlank(message = "代理编号不能为空")
    private String agentNo;
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    /** 代理分账比例（0~1，四位小数），如 0.1500 = 15% */
    private BigDecimal commissionRate;
    /** 起始生效结算期 yyyy-MM；留空取当前结算期，可指定未来期，不许填过去期 */
    private String effectiveFromPeriod;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
