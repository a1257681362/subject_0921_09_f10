package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代理协议主档：点位挂代理协议——一个代理在一个点位一份协议。
 * 比例与有效期不在主档改，全在只追加的 {@link AgentAgreementVersion} 里。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_agent_agreement")
public class AgentAgreement extends BaseEntity {
    /** 协议号，全局唯一 */
    private String agreementNo;
    private Long agentId;
    private String agentNo;
    /** 代理名称快照 */
    private String agentNameSnapshot;
    private Long siteId;
    private String siteNo;
    /** ACTIVE 生效中 / TERMINATED 已终止 */
    private String status;
    private Long currentVersionId;
    private Integer currentVersionNo;
    /** 待下期生效的调整版本 id（无则空） */
    private Long pendingVersionId;
    /** 当前生效版起始结算期 yyyy-MM */
    private String effectiveFromPeriod;
    /** 终止起始结算期（TERMINATE 版起始期），未终止为空 */
    private String terminateFromPeriod;
    private String operatorName;
    private String remark;
}
