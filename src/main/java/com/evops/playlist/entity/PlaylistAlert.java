package com.evops.playlist.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 排片告警：切换失败不许静默，空档/素材缺失/下周无排片/锁版改单待审批同样落库。
 * dedupKey 唯一：同一屏同一自然日同一时段同一类告警只报一次，定时任务重复扫不重发。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_playlist_alert")
public class PlaylistAlert extends BaseEntity {
    private Long playlistId;
    private String playlistNo;
    private String siteNo;
    /** SWITCH_FAILED / GAP / MISSING_ASSET / WEEK_EMPTY / PENDING_CHANGE */
    private String alertType;
    private LocalDateTime triggerTime;
    private LocalDate switchDate;
    private Integer slotSeq;
    private Long versionId;
    private String detail;
    /** OPEN / RESOLVED */
    private String status;
    private String resolvedBy;
    private LocalDateTime resolvedTime;
    private String resolveComment;
    /** 幂等键，见唯一约束 */
    private String dedupKey;
}
