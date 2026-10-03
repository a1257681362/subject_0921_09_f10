package com.evops.asset.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 转档任务异步入口：独立 Bean 避免同类自调用导致 @Async 失效。 */
@Component
public class TranscodeAsyncRunner {

    private final TranscodeService transcodeService;

    public TranscodeAsyncRunner(TranscodeService transcodeService) {
        this.transcodeService = transcodeService;
    }

    @Async("transcodeExecutor")
    public void run(Long jobId) {
        transcodeService.processJob(jobId);
    }
}
