package com.evops.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 结算行：一张实际流水（当期落架的授信原始上刊单，金额取报价含税总额快照）一行。
 * 结算时点把协议版本号、分账比例全部复制进来，之后协议调比例、换版都与历史账单无关。
 * 行内对平：commissionAmount + platformAmount = revenueAmount（尾差由平台行吸收）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_agent_settlement_line")
public class AgentSettlementLine extends BaseEntity {
    private Long settlementId;
    private Long agentId;
    /** 流水来源：授信上刊单 */
    private Long orderId;
    private String orderNo;
    private Long siteId;
    private String siteNo;
    private String creativeNameSnapshot;
    /** 流水归属结算期（上刊单落架期），yyyy-MM */
    private String bizPeriod;
    private Long agreementId;
    /** 结算时点协议版本坐标快照 */
    private Long agreementVersionId;
    private Integer agreementVersionNo;
    /** 结算时点分账比例快照 */
    private BigDecimal commissionRate;
    /** 流水金额（报价含税总额快照） */
    private BigDecimal revenueAmount;
    /** 代理分账 = HALF_UP(流水 × 比例) */
    private BigDecimal commissionAmount;
    /** 平台分账原始 = HALF_UP(流水 × (1-比例)) */
    private BigDecimal platformAmountRaw;
    /** 平台实际分账（吸收本行尾差后） */
    private BigDecimal platformAmount;
    /** 本行尾差 = 流水 - 代理 - 平台原始；非零即标出 */
    private BigDecimal lineRoundingDiff;
}
