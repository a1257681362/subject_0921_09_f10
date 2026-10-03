package com.evops.playlist.dto;

import com.evops.playlist.entity.PlaylistVersion;
import com.evops.playlist.entity.PlaylistWeekLock;
import lombok.Data;

import java.util.List;

/** 某周的编排：生效/草稿版本头 + 周锁版信息 + 按自然日切分后的时段。 */
@Data
public class WeekScheduleView {
    private java.time.LocalDate weekStart;
    private java.time.LocalDate weekEnd;
    /** 该周当前采用的版本（已锁版取锁版版本，否则取最新草稿） */
    private PlaylistVersion version;
    /** 周锁版记录，未锁为 null */
    private PlaylistWeekLock lock;
    /** 该周是否已锁版 */
    private boolean locked;
    private List<SlotView> slots;
}
