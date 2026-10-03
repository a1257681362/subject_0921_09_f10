package com.evops.rectify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 整改单逐事件留底：只追加，不改不删；每条带事件后单据快照 JSON，支撑版本翻查。 */
@Data
@TableName("t_rectify_event")
public class RectifyEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long rectifyId;
    private String rectifyNo;
    /** REPORT / DISPATCH / REPAIR / REINSPECT_PASS / REINSPECT_REJECT / ESCALATE / PHOTO_DELETE */
    private String eventType;
    private Integer versionNo;
    private String operatorName;
    private String detail;
    /** 事件后单据快照 JSON */
    private String snapshot;
    private LocalDateTime eventTime;
}
