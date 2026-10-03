package com.evops.playlist.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;

/**
 * 整表保存某周编排：替换式提交该周全部时段，每次保存都另起一个草稿版本（编排改动留版本）。
 * weekStart 传周内任意一天即可；所有时段（含跨零点切出的次日段）必须落在该自然周内。
 */
@Data
public class PlaylistSaveRequest {
    /** 编排周锚定日，留空取播放列表首个版本的周/今天所在周 */
    private LocalDate weekStart;
    @NotEmpty(message = "至少编排一个时段；要清空该周请走专门的清空动作")
    @Valid
    private List<SlotSpec> slots;
    private String operatorName;
    private String remark;
}
