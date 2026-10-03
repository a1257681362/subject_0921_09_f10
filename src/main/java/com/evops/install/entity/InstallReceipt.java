package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 完工回执（完工回传留底）：一单一回执（dispatch_id 唯一），
 * 回执内容/完工时刻/是否准时随单固化，作为月末按队结算凭证。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_receipt")
public class InstallReceipt extends BaseEntity {
    private Long dispatchId;
    private String dispatchNo;
    private Long teamId;
    private String receiptContent;
    private LocalDateTime finishTime;
    /** 1 准时 / 0 延误 */
    private Integer onTime;
    private String operatorName;
}
