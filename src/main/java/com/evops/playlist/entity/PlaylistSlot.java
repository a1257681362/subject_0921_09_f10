package com.evops.playlist.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 播放时段（版本内）：一行一个自然日内时段。
 * 跨零点时段编排进来后先切成两段（当日到 24:00 + 次日从 00:00 起），crossMidnight=1 标记原始时段跨零点。
 * 同屏同一自然日内时段不许重叠，保存时服务层校验。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_playlist_slot")
public class PlaylistSlot extends BaseEntity {
    private Long playlistId;
    private Long versionId;
    /** 归属自然日 */
    private LocalDate slotDate;
    private LocalTime startTime;
    private LocalTime endTime;
    /** 原时段跨零点、本行是到当日 24:00 的前半 */
    private Integer endReachMidnight;
    /** 原时段跨零点、本行是次日从 00:00 起的后半 */
    private Integer startFromMidnight;
    /** 原始时段是否跨零点 */
    private Integer crossMidnight;
    /** 版本内（自然日维度）的时段序号，复制/顺延时保持顺序 */
    private Integer seq;
}
