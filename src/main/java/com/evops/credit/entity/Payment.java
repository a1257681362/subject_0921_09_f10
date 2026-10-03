package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 回款凭证：一笔回款一行。receivedAmount 实收；allocatedAmount 已核销到单（&lt;= 实收）。
 * RECEIVED 已登记待核销 / PARTIAL 部分核销 / SETTLED 已核销完。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_payment")
public class Payment extends BaseEntity {
    /** 回款凭证号 RCV+序列 */
    private String paymentNo;
    private Long customerId;
    private String customerName;
    private BigDecimal receivedAmount;
    private BigDecimal allocatedAmount;
    private LocalDate receivedDate;
    /** 外部银行流水/收据号 */
    private String voucherNo;
    private String payerAccount;
    /** RECEIVED / PARTIAL / SETTLED */
    private String status;
    private String operatorName;
    private String remark;
}
