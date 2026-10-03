package com.evops.ledger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 上刊单（含对冲单）。画面挂在点位上，状态 HUNG 挂起 / TAKEN_DOWN 落架 / OFFSET 已对冲。
 * bizPeriod 按 occurDate 落库时固化，跨月单不随查询日漂移。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_posting_order")
public class PostingOrder extends BaseEntity {
    /** 单号，全局唯一 */
    private String orderNo;
    /** 根单 id：对冲单指回原单，所有关联单据共享一个根 */
    private Long rootOrderId;
    /** 本单是哪张单的对冲单 */
    private Long offsetOfOrderId;
    /** POSTING / OFFSET */
    private String orderKind;
    private Long siteId;
    /** 画面名称 */
    private String creativeName;
    /** 档期：周一 */
    private LocalDate weekStart;
    /** 档期：周日 */
    private LocalDate weekEnd;
    /** 业务发生日（上刊日落架日），归属月按它算 */
    private LocalDate occurDate;
    /** 归属期间 yyyy-MM，落库固化 */
    private String bizPeriod;
    /** HUNG / TAKEN_DOWN / OFFSET */
    private String status;
    /** 单据版本，每次留痕改动 +1 */
    private Integer versionNo;
    /** 引用的报价单号：有值=授信上刊单，客户与含税占用额从报价单快照取；空=普通单不占额度 */
    private String quoteNo;
    /** 授信客户 id（授信单） */
    private Long customerId;
    /** 授信占用金额（报价含税总额快照） */
    private java.math.BigDecimal occupiedAmount;
    /** 是否占用授信：1 是 / 0 否 */
    private Integer creditUsed;
    /** F8 安装派单 id（上刊确认自动生成后回写）；普通历史单为 null */
    private Long installDispatchId;
    /** 安装派单状态镜像（PENDING/ASSIGNED/ARRIVED/COMPLETED/CANCELLED），上刊单跟着派单走 */
    private String installStatus;
    /** 安装完工回传时刻 */
    private LocalDateTime installFinishTime;
    private String offsetReason;
    /** 开单经手人 */
    private String operatorName;
    /** 关闭（落架/对冲）时间 */
    private LocalDateTime closedTime;
    private String remark;
}
