package com.evops.playlist.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 播放列表（一块屏一条排片单主档）。
 * status 为汇总状态：EDITING 编排中 / LOCKED 已锁版（存在周锁版）。
 * live_* 是播放器当前实际在播指针，由到点定时任务按锁版版本推进。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_playlist")
public class Playlist extends BaseEntity {
    /** 播放列表单号 PL+序列，全局唯一 */
    private String playlistNo;
    private Long siteId;
    private String siteNo;
    /** EDITING / LOCKED */
    private String status;
    /** 当前最新版本（草稿或已锁版） */
    private Long currentVersionId;
    private Integer currentVersionNo;
    /** 播放器在播版本指针 */
    private Long liveVersionId;
    /** 在播自然日（跨零点已切到自然日） */
    private LocalDate liveSwitchDate;
    /** 在播时段序号（版本内自然日内序号） */
    private Integer liveSlotSeq;
    private LocalDateTime liveSwitchedTime;
    private String operatorName;
    private String remark;
}
