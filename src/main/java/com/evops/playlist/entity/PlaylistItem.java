package com.evops.playlist.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 播放条目：时段内播放单的一行，素材从素材库里挑（assetId 引用 t_asset），
 * 同时存素材编号/名称快照，之后素材改名不影响旧版排片留档。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_playlist_item")
public class PlaylistItem extends BaseEntity {
    private Long playlistId;
    private Long versionId;
    private Long slotId;
    /** 时段内顺序 */
    private Integer seq;
    private Long assetId;
    private String assetNo;
    private String assetNameSnapshot;
    /** 该条播放时长（秒），可空 */
    private Integer durationSec;
}
