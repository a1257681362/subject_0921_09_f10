package com.evops.playlist.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 播放条目：素材从素材库里挑（按素材编号），可带该条播放时长（秒）。 */
@Data
public class SlotItemSpec {
    /** 素材编号 AS…（素材库中在用素材） */
    @NotBlank(message = "播放条目必须指定素材编号")
    private String assetNo;
    /** 播放时长（秒），可空 */
    private Integer durationSec;
}
