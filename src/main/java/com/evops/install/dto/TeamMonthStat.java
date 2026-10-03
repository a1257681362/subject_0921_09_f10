package com.evops.install.dto;

import lombok.Data;

/**
 * 月末按队结算口径一行。
 * 结算认这个数：完工量 = 当月完工回执数；准时率 = 准时完工数 / 完工量（无完工为 0）。
 */
@Data
public class TeamMonthStat {
    private String teamNo;
    private String teamName;
    /** 归属期间 yyyy-MM（按完工时刻所在月固化） */
    private String period;
    /** 当月完工量（张） */
    private long completedCount;
    /** 当月准时完工数（张） */
    private long onTimeCount;
    /** 当月延误完工数（张） */
    private long lateCount;
    /** 当月已派未完工（含待匹配）量——提示口径，不进结算 */
    private long assignedOpenCount;
    /** 准时率（0~1，completedCount=0 时为 0） */
    private double onTimeRate;
}
