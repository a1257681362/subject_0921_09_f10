package com.evops.playlist.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 周锁版：同屏同一周一条。锁版后该周改动只能走审批（锁版改单申请 → PENDING → 审批）。
 * lockSource：MANUAL 人工锁版 / AUTO 周五下班前自动锁版。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_playlist_week_lock")
public class PlaylistWeekLock extends BaseEntity {
    private Long playlistId;
    private LocalDate weekStart;
    private LocalDate weekEnd;
    /** 该周生效的锁版版本 */
    private Long versionId;
    /** MANUAL / AUTO */
    private String lockSource;
    private String lockedBy;
    private LocalDateTime lockTime;
}
