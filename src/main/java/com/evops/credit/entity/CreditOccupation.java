package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 授信占用明细：一张授信上刊单一行。
 * ACTIVE 占用中；RELEASED 落架撤刊（未核销额转应收）；REVERSED 对冲更正（释放但不转应收）。
 * amount 为报价含税总额快照；settledAmount 累计已核销；剩余占用 = amount - settledAmount。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_credit_occupation")
public class CreditOccupation extends BaseEntity {
    private Long customerId;
    private String customerName;
    private Long orderId;
    private String orderNo;
    private String quoteNo;
    /** 占用/应收金额（报价含税总额快照，到分） */
    private BigDecimal amount;
    /** 累计已核销金额 */
    private BigDecimal settledAmount;
    /** ACTIVE / RELEASED / REVERSED */
    private String status;
    /** UNSETTLED / PARTIAL / SETTLED */
    private String settleStatus;
    /** 上刊归属期间 yyyy-MM，落库固化 */
    private String bizPeriod;
    private String siteNo;
    private String creativeName;
    private LocalDateTime releasedTime;
}
