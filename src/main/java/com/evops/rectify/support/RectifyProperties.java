package com.evops.rectify.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 异常整改配置：整改期限与超期逐级抄送阶梯。
 *
 * 派单时按 {@code deadlineHours} 给整改期限；定时扫描超期未回传（仍在 DISPATCHED）的单，
 * 超过 {@code escalation} 中某级 afterHours 就把该单抄送给该级 target，逐级往上，
 * 已抄送级别落库不重发。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rectify")
public class RectifyProperties {

    /** 留证照片落盘根目录（库里只存相对路径） */
    private String storageRoot = "./data/rectify-photos";

    /** 整改期限（小时）：派单时刻 + 它 = 期限；复勘驳回重处理重新计时 */
    private int deadlineHours = 48;

    /** 超期抄送扫描间隔（毫秒） */
    private long escalationScanDelayMs = 600_000L;

    /** 逐级抄送阶梯：afterHours = 超过整改期限后的小时数（0 表示期限一过即抄送给本级），按升序 */
    private List<EscalationLevel> escalation = new ArrayList<>(Arrays.asList(
            new EscalationLevel(0, "维护主管"),
            new EscalationLevel(48, "运营经理"),
            new EscalationLevel(120, "分管总监")));

    @Data
    public static class EscalationLevel {
        /** 派单后超过多少小时（未回传）触发本级抄送 */
        private int afterHours;
        /** 抄送给谁（岗位/人） */
        private String target;

        public EscalationLevel() {
        }

        public EscalationLevel(int afterHours, String target) {
            this.afterHours = afterHours;
            this.target = target;
        }
    }
}
