package com.evops.install.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待匹配派单补扫：有安装队后续补登了高空证、或之前档期被占现在空出来了，
 * 定时把 PENDING 派单再按资质+档期匹配一轮。匹配本身的队行锁 + 排期校验保证幂等不重派。
 */
@Component
public class InstallPendingScheduler {

    private static final Logger log = LoggerFactory.getLogger(InstallPendingScheduler.class);

    private final InstallDispatchService dispatchService;

    public InstallPendingScheduler(InstallDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /** 启动后 40 秒先扫一轮，之后按配置间隔（默认 10 分钟）扫。 */
    @Scheduled(fixedDelayString = "${install.pending-scan-delay-ms:600000}", initialDelay = 40_000L)
    @Transactional(rollbackFor = Exception.class)
    public void scan() {
        try {
            int count = dispatchService.autoMatchPending();
            if (count > 0) {
                log.warn("待匹配安装派单补扫：本次新派成 {} 张", count);
            }
        } catch (Exception e) {
            // 定时任务不能让异常把调度线程打停
            log.error("安装派单待匹配补扫失败", e);
        }
    }
}
