package com.evops.rectify.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超期自动抄送定时任务：固定间隔扫一遍已派单整改中且过了期限的单子，逐级往上抄送。
 * 抄送幂等由 t_rectify_cc (rectify_id, cc_level) 唯一约束兜底，重复扫描不重发。
 */
@Component
public class RectifyEscalationScheduler {

    private static final Logger log = LoggerFactory.getLogger(RectifyEscalationScheduler.class);

    private final RectifyService rectifyService;

    @Value("${rectify.escalation-scan-delay-ms:600000}")
    private long scanDelayMs;

    public RectifyEscalationScheduler(RectifyService rectifyService) {
        this.rectifyService = rectifyService;
    }

    /** 启动后 30 秒先扫一轮，之后按配置间隔（默认 10 分钟）扫。 */
    @Scheduled(fixedDelayString = "${rectify.escalation-scan-delay-ms:600000}", initialDelay = 30_000L)
    @Transactional(rollbackFor = Exception.class)
    public void scan() {
        try {
            int count = rectifyService.escalateDue();
            if (count > 0) {
                log.warn("超期整改单逐级抄送：本次新抄送 {} 条", count);
            }
        } catch (Exception e) {
            // 定时任务不能让异常把调度线程打停
            log.error("超期抄送扫描失败", e);
        }
    }

    public long getScanDelayMs() {
        return scanDelayMs;
    }
}
