package com.evops.credit.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 回款按单核销：指定回款凭证（可留空由系统挑该客户最早一笔够用的未核销完回款）与上刊单号、本次核销额。
 * 一张单可分多次核销（部分回款分次核），一笔回款也可核销多张单。
 */
@Data
public class WriteoffRequest {
    /** 回款凭证号；留空自动挑该客户最早一笔余额足够的回款 */
    private String paymentNo;
    /** 上刊单号（授信单） */
    @NotBlank(message = "核销上刊单号不能为空")
    private String orderNo;
    /** 本次核销金额，必须为正且不超过单据未核销额、回款未核销余额 */
    @NotNull(message = "核销金额不能为空")
    @Positive(message = "核销金额必须大于 0")
    private BigDecimal amount;
    /** 核销日期，留空取今天 */
    private LocalDate writeoffDate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
