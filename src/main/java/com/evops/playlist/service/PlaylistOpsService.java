package com.evops.playlist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.asset.entity.Asset;
import com.evops.asset.mapper.AssetMapper;
import com.evops.common.BusinessException;
import com.evops.ledger.support.WeekRanges;
import com.evops.playlist.PlaylistEnums;
import com.evops.playlist.entity.Playlist;
import com.evops.playlist.entity.PlaylistItem;
import com.evops.playlist.entity.PlaylistSlot;
import com.evops.playlist.entity.PlaylistVersion;
import com.evops.playlist.mapper.PlaylistItemMapper;
import com.evops.playlist.mapper.PlaylistSlotMapper;
import com.evops.playlist.support.PlaylistProperties;
import com.evops.playlist.support.PlaylistSegments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 排片运维主线：
 * - 到点自动切换：定时扫描每块屏，按锁版版本与当前自然日/时刻推进 live 指针；
 *   切换失败（素材缺失等）不落错指针、落 SWITCH_FAILED/MISSING_ASSET 告警，绝不静默；
 *   锁版周内该时点没有有效时段（段间空档/当天无段）落 GAP 告警；告警按自然日时段幂等。
 * - 周五下班前自动锁版：到点扫一遍，把下周还没锁版且编好的草稿全部锁版；
 *   下周无排片落 WEEK_EMPTY、锁版改单还在待审批落 PENDING_CHANGE、锁版落不下落 LOCK_FAILED，全部不静默。
 */
@Service
public class PlaylistOpsService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistOpsService.class);

    private final PlaylistService playlistService;
    private final PlaylistSlotMapper slotMapper;
    private final PlaylistItemMapper itemMapper;
    private final AssetMapper assetMapper;
    private final PlaylistProperties properties;

    public PlaylistOpsService(PlaylistService playlistService,
                              PlaylistSlotMapper slotMapper,
                              PlaylistItemMapper itemMapper,
                              AssetMapper assetMapper,
                              PlaylistProperties properties) {
        this.playlistService = playlistService;
        this.slotMapper = slotMapper;
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.properties = properties;
    }

    // ---- 到点自动切换 ----

    @Transactional(rollbackFor = Exception.class)
    public int switchDue() {
        return switchDue(LocalDateTime.now());
    }

    /**
     * 可指定时刻的切换扫描（测试/补扫）。返回本次实际切换的屏数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int switchDue(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate monday = WeekRanges.mondayOf(today);
        int seconds = now.toLocalTime().toSecondOfDay();
        int switched = 0;

        for (Playlist playlist : playlistService.allPlaylists()) {
            try {
                if (switchOne(playlist, today, monday, seconds, now)) {
                    switched++;
                }
            } catch (Exception e) {
                // 单屏异常不影响其他屏；异常本身落 SWITCH_FAILED 告警，不静默
                log.error("屏 {} 到点切换异常", playlist.getPlaylistNo(), e);
                playlistService.raiseAlert(playlist, PlaylistEnums.ALERT_SWITCH_FAILED, today, null,
                        playlist.getLiveVersionId(), "到点切换处理异常：" + e.getMessage());
            }
        }
        return switched;
    }

    private boolean switchOne(Playlist playlist, LocalDate today, LocalDate monday,
                              int seconds, LocalDateTime now) {
        PlaylistVersion version = playlistService.lockedVersion(playlist.getId(), monday);
        // 周一凌晨可能仍在播上一周版本周日跨零点的溢出段
        PlaylistVersion prevVersion = today.equals(monday)
                ? playlistService.lockedVersion(playlist.getId(), monday.minusWeeks(1)) : null;

        List<PlaylistSlot> slots = version == null ? java.util.Collections.emptyList()
                : slotsOfDay(version.getId(), today);
        List<PlaylistSlot> prevSlots = prevVersion == null ? java.util.Collections.emptyList()
                : slotsOfDay(prevVersion.getId(), today);

        PlaylistSlot due = findDue(slots, seconds);
        PlaylistVersion dueVersion = version;
        if (due == null) {
            // 本周一凌晨没排上，再看上周日跨零点溢出段是否还覆盖当前时刻
            PlaylistSlot fromPrev = findDue(prevSlots, seconds);
            if (fromPrev != null) {
                due = fromPrev;
                dueVersion = prevVersion;
            }
        }

        if (due == null) {
            // 本周未锁版：还没承诺播出（上周溢出段若覆盖此刻，上面已命中），不告警
            if (version == null) {
                return false;
            }
            int earliest = earliestStart(slots);
            int prevTailEnd = lastEndSec(prevSlots);
            boolean beforeFirstThisWeek = !slots.isEmpty() && seconds < earliest;
            // 凌晨还没到当天首段、且没有上周溢出段承诺覆盖：等开播，不算空档
            if (beforeFirstThisWeek && prevSlots.isEmpty()) {
                return false;
            }
            // 上周日跨零点溢出段已结束、本周首段还没开始：跨周边界上的空档也要告警
            if (beforeFirstThisWeek && prevTailEnd >= 0 && seconds >= prevTailEnd) {
                PlaylistSlot tail = prevSlots.get(prevSlots.size() - 1);
                playlistService.raiseAlert(playlist, PlaylistEnums.ALERT_GAP, today, tail.getSeq(),
                        prevVersion.getId(), today + " " + LocalTime.ofSecondOfDay(seconds)
                                + " 处在跨周边界空档：上周日跨零点时段（#" + tail.getSeq()
                                + "）已结束，本周首个时段 " + LocalTime.ofSecondOfDay(earliest) + " 还没开始");
                return false;
            }
            raiseGapIfAbsent(playlist, today, version, slots, seconds);
            return false;
        }

        // 已在播同一版本同一自然日同一段：幂等跳过
        if (dueVersion.getId().equals(playlist.getLiveVersionId())
                && today.equals(playlist.getLiveSwitchDate())
                && due.getSeq().equals(playlist.getLiveSlotSeq())) {
            return false;
        }

        // 上播前核验播放单素材仍在库：缺失即切换失败，不推进指针，落告警
        List<PlaylistItem> items = itemMapper.selectList(new LambdaQueryWrapper<PlaylistItem>()
                .eq(PlaylistItem::getSlotId, due.getId())
                .orderByAsc(PlaylistItem::getSeq));
        for (PlaylistItem item : items) {
            Asset asset = assetMapper.selectById(item.getAssetId());
            if (asset == null) {
                playlistService.raiseAlert(playlist, PlaylistEnums.ALERT_MISSING_ASSET, today, due.getSeq(),
                        dueVersion.getId(), "到点切换失败：时段 #" + due.getSeq() + "（" + due.getStartTime()
                                + " 起）的素材 " + item.getAssetNo() + " 在素材库里找不到，播放单未上播");
                playlistService.raiseAlert(playlist, PlaylistEnums.ALERT_SWITCH_FAILED, today, due.getSeq(),
                        dueVersion.getId(), "时段 #" + due.getSeq() + " 因素材缺失切换失败");
                return false;
            }
        }

        playlist.setLiveVersionId(dueVersion.getId());
        playlist.setLiveSwitchDate(today);
        playlist.setLiveSlotSeq(due.getSeq());
        playlist.setLiveSwitchedTime(now);
        playlistService.updateLivePointer(playlist);
        playlistService.recordSwitchEvent(playlist, dueVersion, today, due.getSeq(),
                "到点自动切换：" + today + " " + due.getStartTime()
                        + (due.getEndReachMidnight() != null && due.getEndReachMidnight() == 1 ? "~24:00" : "~" + due.getEndTime())
                        + " 时段上播（v" + dueVersion.getVersionNo() + "，" + items.size() + " 条播放单）");
        log.info("屏 {} 到点切换：{} v{} 时段#{}", playlist.getPlaylistNo(), today,
                dueVersion.getVersionNo(), due.getSeq());
        return true;
    }

    private List<PlaylistSlot> slotsOfDay(Long versionId, LocalDate date) {
        return slotMapper.selectList(new LambdaQueryWrapper<PlaylistSlot>()
                .eq(PlaylistSlot::getVersionId, versionId)
                .eq(PlaylistSlot::getSlotDate, date)
                .orderByAsc(PlaylistSlot::getStartTime));
    }

    /** 半开 [start, end)：时刻恰好落在段内返回该段。 */
    private PlaylistSlot findDue(List<PlaylistSlot> slots, int seconds) {
        for (PlaylistSlot slot : slots) {
            int start = slot.getStartTime().toSecondOfDay();
            int end = slot.getEndReachMidnight() != null && slot.getEndReachMidnight() == 1
                    ? PlaylistSegments.SECONDS_PER_DAY : slot.getEndTime().toSecondOfDay();
            if (start <= seconds && seconds < end) {
                return slot;
            }
        }
        return null;
    }

    private int earliestStart(List<PlaylistSlot> slots) {
        int earliest = Integer.MAX_VALUE;
        for (PlaylistSlot slot : slots) {
            earliest = Math.min(earliest, slot.getStartTime().toSecondOfDay());
        }
        return earliest;
    }

    /** 一组自然日段里最晚的结束秒数（到 24:00 按 86400）；无段返回 -1。 */
    private int lastEndSec(List<PlaylistSlot> slots) {
        int last = -1;
        for (PlaylistSlot slot : slots) {
            int end = slot.getEndReachMidnight() != null && slot.getEndReachMidnight() == 1
                    ? PlaylistSegments.SECONDS_PER_DAY : slot.getEndTime().toSecondOfDay();
            last = Math.max(last, end);
        }
        return last;
    }

    /** 锁版周内空档告警：段间间隙按相邻段定位槽号；当天无段按 0 号槽，一天一条。 */
    private void raiseGapIfAbsent(Playlist playlist, LocalDate today, PlaylistVersion version,
                                  List<PlaylistSlot> slots, int seconds) {
        Integer slotSeq = 0;
        String detail;
        if (slots.isEmpty()) {
            detail = today + " 该屏锁版周内当天没有任何排片，当前时点无有效播放单（空档）";
        } else {
            // 找到当前时刻之前最近结束的段，空档挂在它后面
            PlaylistSlot prev = slots.get(0);
            for (PlaylistSlot slot : slots) {
                int end = slot.getEndReachMidnight() != null && slot.getEndReachMidnight() == 1
                        ? PlaylistSegments.SECONDS_PER_DAY : slot.getEndTime().toSecondOfDay();
                if (end <= seconds) {
                    slotSeq = slot.getSeq();
                    prev = slot;
                }
            }
            detail = today + " " + LocalTime.ofSecondOfDay(seconds)
                    + " 该屏处在播放空档：时段 #" + prev.getSeq() + " 已结束、下一时段还没开始（锁版周内不许无有效排片）";
        }
        playlistService.raiseAlert(playlist, PlaylistEnums.ALERT_GAP, today, slotSeq,
                version.getId(), detail);
    }

    // ---- 周五下班前自动锁版 ----

    @Transactional(rollbackFor = Exception.class)
    public int autoLockNextWeek() {
        return autoLockNextWeek(LocalDateTime.now());
    }

    /**
     * 周五下班时刻一过，把每块屏下周还没锁版的排片全部锁版。
     * 非周五/未到下班时刻直接返回 0（交给下一轮扫描）。返回本次新锁版屏数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int autoLockNextWeek(LocalDateTime now) {
        if (!DayOfWeek.FRIDAY.equals(now.getDayOfWeek())
                || now.toLocalTime().isBefore(deadline())) {
            return 0;
        }
        LocalDate nextMonday = WeekRanges.mondayOf(now.toLocalDate()).plusWeeks(1);
        int locked = 0;
        for (Playlist playlist : playlistService.allPlaylists()) {
            try {
                if (autoLockOne(playlist, nextMonday)) {
                    locked++;
                }
            } catch (Exception e) {
                log.error("屏 {} 下周自动锁版异常", playlist.getPlaylistNo(), e);
                playlistService.raiseAlert(playlist, PlaylistEnums.ALERT_LOCK_FAILED, nextMonday, 0, null,
                        "周五自动锁版下周排片失败：" + e.getMessage());
            }
        }
        if (locked > 0) {
            log.warn("周五下班前自动锁版完成：本次新锁 {} 块屏的下周排片（{} 周）", locked, nextMonday);
        }
        return locked;
    }

    private boolean autoLockOne(Playlist playlist, LocalDate nextMonday) {
        if (playlistService.findLock(playlist.getId(), nextMonday) != null) {
            // 下周已锁、但锁版改单仍待审批：旧版会照常播，不影响安全；提醒尽快出审批结论，别让改动悄悄错过下周
            if (playlistService.countPendingChanges(playlist.getId(), nextMonday) > 0) {
                playlistService.raiseAlert(playlist, PlaylistEnums.ALERT_PENDING_CHANGE, nextMonday, 0, null,
                        "下周（" + nextMonday + " 周）已锁版，但有锁版改单仍待审批，请尽快审批以免改动错过下周");
            }
            return false;
        }
        PlaylistVersion latest = playlistService.latestWeekVersion(playlist.getId(), nextMonday);
        if (latest == null || playlistService.countSlots(latest.getId()) == 0) {
            playlistService.raiseAlert(playlist, PlaylistEnums.ALERT_WEEK_EMPTY, nextMonday, 0, null,
                    "下周（" + nextMonday + " 周）还没有任何排片，周五下班前无法锁版，请立即补排");
            return false;
        }
        try {
            playlistService.lockWeek(playlist, nextMonday, PlaylistEnums.LOCK_AUTO, "SYSTEM");
            return true;
        } catch (BusinessException e) {
            playlistService.raiseAlert(playlist, PlaylistEnums.ALERT_LOCK_FAILED, nextMonday, 0,
                    latest.getId(), "下周自动锁版被拒：" + e.getMessage());
            return false;
        }
    }

    private LocalTime deadline() {
        return PlaylistSegments.parseTime(properties.getFridayDeadline(), "周五下班时刻");
    }
}
