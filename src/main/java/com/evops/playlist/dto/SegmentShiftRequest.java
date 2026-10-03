package com.evops.playlist.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 整段顺延：把某周全部时段整体平移 offsetMinutes 分钟（可为负，表示提前），
 * 跨零点、跨自然日都按切分后的自然日时段重算，生成新草稿版本。
 */
@Data
public class SegmentShiftRequest {
    /** 编排周锚定日，留空取最新草稿所在周 */
    private LocalDate weekStart;
    /** 顺延分钟数（正数延后、负数提前），如 180 顺延 3 小时，-60 提前 1 小时 */
    private Long offsetMinutes;
    private String operatorName;
    private String remark;
}
