package com.evops.playlist.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 整段复制：把来源周（优先取该周锁版版本，没锁版取最新草稿）的全部时段与播放单
 * 原样复制到目标周，生成目标周的新草稿版本。复制不影响来源周已锁版本。
 */
@Data
public class SegmentCopyRequest {
    /** 来源周锚定日（周内任意一天） */
    private LocalDate fromWeekStart;
    /** 目标周锚定日（周内任意一天），留空取来源周的下一周 */
    private LocalDate toWeekStart;
    private String operatorName;
    private String remark;
}
