package com.evops.ledger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 按周档期占用（同一点位同一周唯一一行）。
 * ACTIVE 表示该周有挂起画面；落架/对冲后置 RELEASED，该周可重新上刊（更新本行，不留多行）。
 * 历史经过以 t_order_event 为准。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_slot_booking")
public class SlotBooking extends BaseEntity {
    private Long siteId;
    private LocalDate weekStart;
    private LocalDate weekEnd;
    /** ACTIVE / RELEASED */
    private String status;
    private Long orderId;
    private String orderNo;
}
