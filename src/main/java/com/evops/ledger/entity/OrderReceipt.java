package com.evops.ledger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 外部回执：挂回原单据（指向对冲单时回溯根单）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order_receipt")
public class OrderReceipt extends BaseEntity {
    /** 回执编号，唯一 */
    private String receiptNo;
    /** 回执直接指向的单据 */
    private Long orderId;
    private String orderNo;
    /** 回溯到的根单 */
    private Long rootOrderId;
    private String rootOrderNo;
    /** 回执来源，如 监测平台 / 业主签收回传 */
    private String source;
    private String content;
    /** 回执上的发生日 */
    private LocalDate occurDate;
    private String operatorName;
    private LocalDateTime receivedTime;
}
