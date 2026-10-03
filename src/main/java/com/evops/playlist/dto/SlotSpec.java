package com.evops.playlist.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;

/**
 * 播放时段编排规格：date 为时段开始的自然日，startTime/endTime 为 HH:mm。
 * endTime 不晚于 startTime 即视为跨零点（也可用 crossMidnight 显式声明）；
 * 跨零点时段由服务层切成「当日到 24:00」「次日从 00:00 起」两段，再按自然日比对冲突。
 */
@Data
public class SlotSpec {
    private LocalDate date;
    @NotBlank(message = "时段开始时间不能为空（HH:mm）")
    private String startTime;
    @NotBlank(message = "时段结束时间不能为空（HH:mm）")
    private String endTime;
    /** 显式声明跨零点；留空时按 endTime <= startTime 自动判定 */
    private Boolean crossMidnight;
    @NotEmpty(message = "时段内至少要挑一条素材")
    @Valid
    private List<SlotItemSpec> items;
}
