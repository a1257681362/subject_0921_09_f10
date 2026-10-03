package com.evops.playlist.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 排片配置：到点切换扫描间隔、周锁版扫描间隔与周五下班时刻。
 * 周五下班时刻一过，自动锁版任务把下周还没锁版的排片全部锁掉/告警。
 */
@Data
@Component
@ConfigurationProperties(prefix = "playlist")
public class PlaylistProperties {

    /** 到点切换扫描间隔（毫秒，默认 30 秒；启动 20 秒后先扫一轮） */
    private long switchScanDelayMs = 30_000L;

    /** 周锁版扫描间隔（毫秒，默认 10 分钟；启动 40 秒后先扫一轮） */
    private long lockScanDelayMs = 600_000L;

    /** 周五下班时刻（HH:mm）：下周排片应在此刻前全部锁版 */
    private String fridayDeadline = "18:00";
}
