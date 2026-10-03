package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 代理协议版本（只追加）：比例与有效期定清楚，一版一行，历史永久可翻。
 * 有效期按结算期（自然月 yyyy-MM）：effectiveFromPeriod 为本版起始生效期。
 * 某结算期用哪版 = effective_from_period &lt;= 该期 的最大版本号。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_agent_agreement_version")
public class AgentAgreementVersion extends BaseEntity {
    private Long agreementId;
    private Integer versionNo;
    /** INITIAL / ADJUST / TERMINATE */
    private String changeType;
    /** 代理分账比例（0~1，DECIMAL(7,4)）；TERMINATE 版沿用上一版比例留底 */
    private BigDecimal commissionRate;
    /** 起始生效结算期 yyyy-MM */
    private String effectiveFromPeriod;
    /** PENDING 待生效 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代 */
    private String status;
    private String reason;
    private String operatorName;
    /** 版本实际转生效的时刻（建档版即建档时刻；调整版在结算期切换时置位） */
    private LocalDateTime effectiveTime;
}
