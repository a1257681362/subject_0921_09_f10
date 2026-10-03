package com.evops.credit.dto;

import com.evops.credit.entity.Payment;
import com.evops.credit.entity.PaymentWriteoff;
import lombok.Data;

import java.util.List;

/** 回款凭证详情：凭证头 + 逐单核销明细。 */
@Data
public class PaymentDetailView {
    private Payment payment;
    private List<PaymentWriteoff> writeoffs;
}
