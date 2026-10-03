package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算单：代理 × 结算期一张，确认即冻结（SETTLED），已结算期不重算。
 *
 * 对平口径：totalCommission + totalPlatform = totalRevenue，分毫不差；
 * 各行独立四舍五入到分产生的尾差 roundingDiff（差一分也落库标出）由平台方兜底吸收，
 * totalPlatform = totalPlatformRaw + roundingDiff；balanced=1 即对平。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_agent_settlement")
public class AgentSettlement extends BaseEntity {
    /** 结算单号，全局唯一 */
    private String settlementNo;
    private Long agentId;
    private String agentNo;
    private String agentNameSnapshot;
    /** 结算归属期 yyyy-MM（流水按落架期固化） */
    private String bizPeriod;
    /** SETTLED 已结算（确认即冻结） */
    private String status;
    /** 结算流水笔数 */
    private Integer orderCount;
    /** 实际流水合计（报价含税总额快照） */
    private BigDecimal totalRevenue;
    /** 代理分账合计（四舍五入到分后求和） */
    private BigDecimal totalCommission;
    /** 平台分账原始合计（各行独立四舍五入后求和） */
    private BigDecimal totalPlatformRaw;
    /** 平台分账实际合计（吸收全部尾差后，与总额对平） */
    private BigDecimal totalPlatform;
    /** 尾差合计 = 流水 - 代理 - 平台原始；非零即标出，由平台兜底 */
    private BigDecimal roundingDiff;
    /** 尾差兜底方 */
    private String tailParty;
    /** 是否对平：1 对平 / 0 未对平 */
    private Integer balanced;
    private LocalDateTime settleTime;
    /** 结算单全量快照 JSON（头 + 行 + 协议版本坐标） */
    private String snapshot;
    private String operatorName;
    private String remark;
}
