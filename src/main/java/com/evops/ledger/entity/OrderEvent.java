package com.evops.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 逐单事件留底：只追加，不改不删。谁经手、哪天发生、当时单据快照都在这里。 */
@Data
@TableName("t_order_event")
public class OrderEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String orderNo;
    private Long rootOrderId;
    /** CREATE / EDIT / TAKE_DOWN / OFFSET / ROLLBACK */
    private String eventType;
    private Integer versionNo;
    /** 发生日（业务日） */
    private LocalDate occurDate;
    /** 经手人 */
    private String operatorName;
    /** 关联单号（如对冲单/原单） */
    private String relatedOrderNo;
    private String detail;
    /** 事件后单据快照 JSON，版本回退取它 */
    private String snapshot;
    /** 系统记录时刻 */
    private LocalDateTime eventTime;
}
