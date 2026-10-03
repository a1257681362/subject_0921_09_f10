package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 安装派单：上刊单确认后自动生成一张，按资质与档期匹配安装队。
 * 状态机见 {@link com.evops.install.InstallEnums}；每流转一次 versionNo +1 并写事件快照。
 *
 * 排期口径：同一队 [planStart, planEnd) 半开区间不许重叠（首尾相接不算撞）；
 * 只对未取消的派单比较。匹配时对候选队加行级悲观锁串行化，避免并发把两张单同时排给一个队。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_order")
public class InstallOrder extends BaseEntity {
    /** 派单号 ID+序列，全局唯一 */
    private String dispatchNo;
    /** 来源上刊单 */
    private Long orderId;
    private String orderNo;
    private Long siteId;
    private String siteNo;
    private String businessDistrict;
    private String creativeName;
    /** NORMAL / HIGH_ALTITUDE（从上刊点位快照） */
    private String workType;
    /** PENDING / ASSIGNED / ARRIVED / COMPLETED / CANCELLED */
    private String status;
    /** 资质匹配版本：每流转一次 +1 */
    private Integer versionNo;

    private Long teamId;
    private String teamNo;
    /** 派单时队名快照 */
    private String teamNameSnapshot;
    /** 计划作业时段（半开区间，结束为排他界） */
    private LocalDateTime planStart;
    private LocalDateTime planEnd;
    /** 到场截止时刻（= planStart，师傅应在计划开始时刻前/当时到场；晚于它算不准时） */
    private LocalDateTime arrivalDeadline;
    /** 1 自动匹配 / 0 人工补匹配（PENDING 时为 null） */
    private Integer autoMatched;

    private LocalDateTime arriveTime;
    private String arriveOperator;
    private LocalDateTime finishTime;
    private String finishOperator;
    private String finishResult;
    /** 完工是否准时：1 准时 / 0 延误（完工时刻晚于计划结束即延误）；未完为 null */
    private Integer onTime;

    private String cancelReason;
    private LocalDateTime cancelledTime;
    /** 改期次数（每次通知到队 +1） */
    private Integer rescheduleCount;
    /** 最近动作经手人 */
    private String operatorName;
    private String remark;
}
