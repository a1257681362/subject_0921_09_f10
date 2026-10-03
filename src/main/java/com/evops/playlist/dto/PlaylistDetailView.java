package com.evops.playlist.dto;

import com.evops.playlist.entity.Playlist;
import com.evops.playlist.entity.PlaylistAlert;
import com.evops.playlist.entity.PlaylistEvent;
import com.evops.playlist.entity.PlaylistVersion;
import com.evops.playlist.entity.PlaylistWeekLock;
import lombok.Data;

import java.util.List;

/** 播放列表全量档案：主档 + 版本链 + 周锁版 + 事件留底 + 告警。 */
@Data
public class PlaylistDetailView {
    private Playlist playlist;
    private List<PlaylistVersion> versions;
    private List<PlaylistWeekLock> locks;
    private List<PlaylistEvent> events;
    private List<PlaylistAlert> alerts;
}
