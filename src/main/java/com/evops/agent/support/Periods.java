package com.evops.agent.support;

import com.evops.common.BusinessException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/** 结算期（自然月 yyyy-MM）工具：落库固化、下期推算、先后比较。 */
public final class Periods {
    private Periods() {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    public static String of(LocalDate date) {
        return YearMonth.from(date).format(FMT);
    }

    public static String current() {
        return of(LocalDate.now());
    }

    /** 下一结算期：当期加一月。 */
    public static String next(String period) {
        return YearMonth.parse(period, FMT).plusMonths(1).format(FMT);
    }

    public static boolean isValid(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            return false;
        }
        try {
            YearMonth.parse(period, FMT);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String requireValid(String period) {
        if (!isValid(period)) {
            throw new BusinessException("结算期格式必须为 yyyy-MM：" + period);
        }
        return period;
    }

    /** 期间先后比较：a 在 b 之前返回负、相同 0、之后正。 */
    public static int compare(String a, String b) {
        return YearMonth.parse(a, FMT).compareTo(YearMonth.parse(b, FMT));
    }
}
