package com.evops.playlist.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 播放列表版本：版本链只追加，编排每次保存/复制/顺延/锁版/锁后改单/审批都另起一版。
 * 每版覆盖一个 ISO 自然周（周一至周日）；跨零点时段切到自然日后，周一凌晨溢出段仍归周日所在周版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_playlist_version")
public class PlaylistVersion extends BaseEntity {
    private Long playlistId;
    private Integer versionNo;
    /** 覆盖周（ISO 周一） */
    private LocalDate weekStart;
    /** 覆盖周（周日） */
    private LocalDate weekEnd;
    /** CREATE / EDIT / COPY / SHIFT / LOCK / AUTO_LOCK / CHANGE_REQUEST / APPROVE */
    private String changeType;
    /** DRAFT / LOCKED / PENDING / REJECTED / SUPERSEDED */
    private String status;
    private Integer slotCount;
    private Integer itemCount;
    /** 版本全量快照 JSON（头 + 时段 + 条目） */
    private String snapshot;

    private String lockedBy;
    private LocalDateTime lockTime;

    private String applicantName;
    private String applyReason;
    private LocalDateTime applyTime;
    private String approverName;
    private String approveComment;
    private LocalDateTime approveTime;

    /** 复制/顺延/改单来源版本 */
    private Long sourceVersionId;
    private String operatorName;
    private String remark;
}
