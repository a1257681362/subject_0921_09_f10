package com.evops.ledger.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 按周（ISO，周一到周日）与归属月工具。 */
public final class WeekRanges {
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private WeekRanges() {}

    /** 任意日期所在周的周一。 */
    public static LocalDate mondayOf(LocalDate date) {
        return date.with(java.time.DayOfWeek.MONDAY);
    }

    /** 任意日期所在周的周日。 */
    public static LocalDate sundayOf(LocalDate date) {
        return mondayOf(date).plusDays(6);
    }

    /** 归属期间 yyyy-MM：按发生日取，落库即固化。 */
    public static String periodOf(LocalDate occurDate) {
        return occurDate.format(MONTH_FMT);
    }
}
