package com.evops.playlist.support;

import com.evops.common.BusinessException;
import com.evops.playlist.dto.SlotItemSpec;
import com.evops.playlist.dto.SlotSpec;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 播放时段切分与冲突比较：跨零点时段一律先按自然日切成多段，再做同屏同日比较。
 *
 * 段以半开区间 [start, end) 表示，到当日 24:00 的段 end 存 00:00 + endReachMidnight 标记，
 * 比较时按 86400 秒处理；首尾相接（10:00 结束 / 10:00 开始）不算撞。
 */
public final class PlaylistSegments {

    /** 一天的秒数，跨零点前半段的“终点” */
    public static final int SECONDS_PER_DAY = 24 * 3600;
    static final LocalTime MIDNIGHT = LocalTime.MIDNIGHT;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("H:mm");

    private PlaylistSegments() {}

    /** 内部表示：一个自然日内的半开时段 + 该时段播放单。 */
    public static class Segment {
        public LocalDate date;
        public LocalTime start;
        public LocalTime end;
        public boolean endReachMidnight;
        public boolean startFromMidnight;
        public boolean crossMidnight;
        public List<SlotItemSpec> items;

        public int startSec() { return start.toSecondOfDay(); }
        public int endSec() { return endReachMidnight ? SECONDS_PER_DAY : end.toSecondOfDay(); }

        public String timeText() {
            String tail = (endReachMidnight ? "24:00" : HM.format(end))
                    + (crossMidnight ? "（跨零点）" : "");
            return HM.format(start) + "~" + tail;
        }
    }

    /** 把一条编排规格按自然日切开（跨零点切成两段）；anchorDate 为规格未带 date 时的归属日。 */
    public static List<Segment> parse(SlotSpec spec, LocalDate anchorDate) {
        LocalDate date = spec.getDate() != null ? spec.getDate() : anchorDate;
        if (date == null) {
            throw new BusinessException("时段必须带日期（或在带周锚定日的请求里提交）");
        }
        LocalTime start = parseTime(spec.getStartTime(), "时段开始时间");
        LocalTime end = parseTime(spec.getEndTime(), "时段结束时间");
        if (start.equals(MIDNIGHT) && end.equals(MIDNIGHT) && !Boolean.TRUE.equals(spec.getCrossMidnight())) {
            throw new BusinessException(date + " 的时段 00:00~00:00 不合法：0 时长时段不能保存");
        }
        boolean cross = spec.getCrossMidnight() != null ? spec.getCrossMidnight() : !end.isAfter(start);

        List<Segment> out = new ArrayList<>();
        if (!cross) {
            if (!end.isAfter(start)) {
                throw new BusinessException(date + " 的时段 " + HM.format(start) + "~" + HM.format(end)
                        + " 不合法：结束时间不晚于开始时间即视为跨零点，请确认或显式传 crossMidnight=true");
            }
            out.add(segment(date, start, end, false, false, false, spec.getItems()));
        } else {
            // 当日到 24:00
            out.add(segment(date, start, MIDNIGHT, true, false, true, spec.getItems()));
            // 次日从 00:00 起；结束恰为 00:00 表示整段正好到当日 24:00，没有非空次日段
            if (!end.equals(MIDNIGHT)) {
                out.add(segment(date.plusDays(1), MIDNIGHT, end, false, true, true, spec.getItems()));
            }
        }
        return out;
    }

    /**
     * 把一个绝对时间区间 [from, to) 按自然日切成段（整段顺延用：平移后可能落在别的自然日/重新跨零点）。
     */
    public static List<Segment> splitAbsolute(LocalDateTime from, LocalDateTime to,
                                              boolean originCross, List<SlotItemSpec> items) {
        if (!to.isAfter(from)) {
            throw new BusinessException("顺延后出现 0 时长或倒序时段，拒绝保存");
        }
        List<Segment> out = new ArrayList<>();
        LocalDateTime cursor = from;
        boolean spansDays = !to.toLocalDate().equals(from.toLocalDate());
        while (cursor.isBefore(to)) {
            LocalDate date = cursor.toLocalDate();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
            LocalTime st = cursor.toLocalTime();
            if (to.isAfter(dayEnd)) {
                out.add(segment(date, st, MIDNIGHT, true, st.equals(MIDNIGHT),
                        originCross || spansDays, items));
                cursor = dayEnd;
            } else {
                boolean reach = to.equals(dayEnd);
                LocalTime en = reach ? MIDNIGHT : to.toLocalTime();
                out.add(segment(date, st, en, reach, st.equals(MIDNIGHT),
                        originCross || spansDays, items));
                break;
            }
        }
        return out;
    }

    /**
     * 同屏同一自然日冲突校验：半开区间重叠即撞；首尾相接不算。
     * 撞了直接报业务错（保存时拦截）。
     */
    public static void checkNoOverlap(List<Segment> segments) {
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparing((Segment s) -> s.date).thenComparingInt(Segment::startSec));
        for (int i = 1; i < sorted.size(); i++) {
            Segment prev = sorted.get(i - 1);
            Segment cur = sorted.get(i);
            if (cur.date.equals(prev.date) && cur.startSec() < prev.endSec()) {
                throw new BusinessException("同屏 " + cur.date + " 时段撞车："
                        + prev.timeText() + " 与 " + cur.timeText()
                        + " 重叠；同一屏同一时段只许一条有效排片");
            }
        }
    }

    public static LocalTime parseTime(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(field + "不能为空（格式 HH:mm）");
        }
        try {
            return LocalTime.parse(value.trim().replace('：', ':'), DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            try {
                return LocalTime.parse(value.trim().replace('：', ':'),
                        DateTimeFormatter.ofPattern("H:mm"));
            } catch (Exception ex) {
                throw new BusinessException(field + "格式不对: " + value + "（应为 HH:mm，如 08:30）");
            }
        }
    }

    private static Segment segment(LocalDate date, LocalTime start, LocalTime end,
                                   boolean endReach, boolean startFrom, boolean cross,
                                   List<SlotItemSpec> items) {
        Segment s = new Segment();
        s.date = date;
        s.start = start;
        s.end = end;
        s.endReachMidnight = endReach;
        s.startFromMidnight = startFrom;
        s.crossMidnight = cross;
        s.items = items;
        return s;
    }
}
