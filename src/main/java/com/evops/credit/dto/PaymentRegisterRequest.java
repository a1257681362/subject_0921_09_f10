package com.evops.credit.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 回款登记：一笔回款一张凭证；登记后再按单核销，支持部分回款分次核。 */
@Data
public class PaymentRegisterRequest {
    /** 客户编号 */
    @NotBlank(message = "客户编号不能为空")
    private String customerNo;
    /** 实收金额（含税口径，到分，必须为正） */
    @NotNull(message = "回款金额不能为空")
    @Positive(message = "回款金额必须大于 0")
    private BigDecimal receivedAmount;
    /** 到账日期，留空取今天 */
    private LocalDate receivedDate;
    /** 外部银行流水/收据号（选填） */
    private String voucherNo;
    private String payerAccount;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
