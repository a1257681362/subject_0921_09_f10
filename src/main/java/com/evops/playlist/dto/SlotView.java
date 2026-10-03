package com.evops.playlist.dto;

import com.evops.playlist.entity.PlaylistItem;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 一个自然日内的播放时段（跨零点已被切到自然日）及其播放单。 */
@Data
public class SlotView {
    private Integer seq;
    private LocalDate slotDate;
    private LocalTime startTime;
    private LocalTime endTime;
    /** 原始时段是否跨零点 */
    private Boolean crossMidnight;
    /** 本行是跨零点前半（到当日 24:00） */
    private Boolean endReachMidnight;
    /** 本行是跨零点后半（次日从 00:00 起） */
    private Boolean startFromMidnight;
    private List<PlaylistItem> items;
}
