package com.evops.playlist.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 编排事件留底（只追加）：建档/保存/复制/顺延/锁版/申请/审批/到点切换都写一条，带版本号。 */
@Data
@TableName("t_playlist_event")
public class PlaylistEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long playlistId;
    private String playlistNo;
    private Integer versionNo;
    /** CREATE/SAVE/COPY/SHIFT/LOCK/AUTO_LOCK/CHANGE_REQUEST/APPROVE/REJECT/SWITCH */
    private String eventType;
    private String operatorName;
    private String detail;
    /** 事件时版本/指针快照 JSON */
    private String snapshot;
    private LocalDateTime eventTime;
}
