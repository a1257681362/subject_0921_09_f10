package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 回款按单核销明细（只追加）：一笔回款可分次核销多张单，一张单也可被多笔回款分次核销。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_payment_writeoff")
public class PaymentWriteoff extends BaseEntity {
    private Long paymentId;
    private String paymentNo;
    private Long customerId;
    private Long occupationId;
    private Long orderId;
    private String orderNo;
    /** 本次核销金额（到分） */
    private BigDecimal amount;
    private LocalDate writeoffDate;
    private String operatorName;
    private String remark;
}
