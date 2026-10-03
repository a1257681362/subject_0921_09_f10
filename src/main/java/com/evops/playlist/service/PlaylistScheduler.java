package com.evops.playlist.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 排片定时任务：
 * - 到点自动切换：按配置间隔（默认 30 秒）扫描，到点把各屏切到应播时段；失败落告警，不静默；
 * - 周五下班前自动锁版：按配置间隔（默认 10 分钟）扫描，周五下班时刻一过锁下周排片。
 * 异常只记录不抛出，不能让调度线程被打停。
 */
@Component
public class PlaylistScheduler {

    private static final Logger log = LoggerFactory.getLogger(PlaylistScheduler.class);

    private final PlaylistOpsService opsService;

    public PlaylistScheduler(PlaylistOpsService opsService) {
        this.opsService = opsService;
    }

    /** 启动 20 秒后先扫一轮，之后每 30 秒（可配）到点切换。 */
    @Scheduled(fixedDelayString = "${playlist.switch-scan-delay-ms:30000}", initialDelay = 20_000L)
    public void switchTick() {
        try {
            int switched = opsService.switchDue();
            if (switched > 0) {
                log.warn("到点自动切换：本次上播 {} 块屏", switched);
            }
        } catch (Exception e) {
            log.error("排片到点切换扫描失败", e);
        }
    }

    /** 启动 40 秒后先扫一轮，之后每 10 分钟（可配）检查周五自动锁版。 */
    @Scheduled(fixedDelayString = "${playlist.lock-scan-delay-ms:600000}", initialDelay = 40_000L)
    public void autoLockTick() {
        try {
            int locked = opsService.autoLockNextWeek();
            if (locked > 0) {
                log.warn("周五下班前自动锁版：本次锁定 {} 块屏的下周排片", locked);
            }
        } catch (Exception e) {
            log.error("排片周锁版扫描失败", e);
        }
    }
}
