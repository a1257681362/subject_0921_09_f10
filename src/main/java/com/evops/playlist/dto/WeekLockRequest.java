package com.evops.playlist.dto;

import lombok.Data;

import java.time.LocalDate;

/** 周锁版动作请求（人工锁版）。 */
@Data
public class WeekLockRequest {
    /** 要锁版的周锚定日（周内任意一天），留空取最新草稿所在周 */
    private LocalDate weekStart;
    private String operatorName;
}
