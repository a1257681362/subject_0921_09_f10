package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 派单事件留底（只追加）：自动生成/匹配/改期/到场/完工/取消/通知都写一条，
 * 每条带事件后派单快照 JSON。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_event")
public class InstallEvent extends BaseEntity {
    private Long dispatchId;
    private String dispatchNo;
    /** AUTO_CREATE / MATCH / RESCHEDULE / ARRIVE / COMPLETE / CANCEL / NOTIFY */
    private String eventType;
    private Integer versionNo;
    private String operatorName;
    private String detail;
    /** 事件后派单快照 JSON */
    private String snapshot;
    private java.time.LocalDateTime eventTime;
}
