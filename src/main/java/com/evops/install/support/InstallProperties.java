package com.evops.install.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安装派单配置：照片落盘目录、待匹配补扫间隔、默认作业时段长度。
 */
@Data
@Component
@ConfigurationProperties(prefix = "install")
public class InstallProperties {

    /** 到场/完工照片落盘根目录（库里只存相对路径） */
    private String storageRoot = "./data/install-photos";

    /** 待匹配补扫间隔（毫秒，默认 10 分钟；启动 40 秒后先扫一轮） */
    private long pendingScanDelayMs = 600_000L;

    /**
     * 自动派单无显式档期时，默认作业时段长度（小时）：
     * 计划开始取上刊档期周的周一 09:00，结束 = 开始 + 该时长。
     */
    private int defaultJobHours = 4;

    /** 自动派单默认计划开始的钟点（24 小时制本地时间） */
    private int defaultStartHour = 9;
}
