package com.evops.rectify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 异常整改单：巡查员现场上报即生成，系统派给维护商，处理完回传，复勘通过才销项。
 * 状态机见 {@link com.evops.rectify.RectifyEnums}；每次流转 version_no +1 并写事件快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rectify_order")
public class RectifyOrder extends BaseEntity {
    /** 整改单号 RC+序列，全局唯一 */
    private String rectifyNo;
    private Long siteId;
    private String siteNo;
    private String businessDistrict;
    /** 异常类型代码（牌面翘边 PANEL_LIFT 等） */
    private String abnormalType;
    /** 异常描述 */
    private String abnormalDesc;
    /** REPORTED / DISPATCHED / REPAIRED / CLOSED */
    private String status;
    /** 异常上报版本：每流转一次加一 */
    private Integer versionNo;
    /** 上报巡查员 */
    private String reporterName;
    private LocalDateTime reportTime;
    /** 承派维护商 */
    private String vendorName;
    private String vendorContact;
    /** 派单经手人（调度） */
    private String dispatchOperator;
    private LocalDateTime dispatchTime;
    /** 整改期限：派单时刻 + 配置期限小时；复勘驳回不延期 */
    private LocalDateTime deadline;
    /** 维护商回传的处理结果 */
    private String repairResult;
    private String repairOperator;
    private LocalDateTime repairTime;
    /** 复勘人 / 意见 / 时刻 */
    private String reinspectOperator;
    private String reinspectComment;
    private LocalDateTime reinspectTime;
    /** 已抄送的最高级别（0 未抄送） */
    private Integer ccLevel;
    private LocalDateTime closedTime;
    /** 最近动作经手人 */
    private String operatorName;
    private String remark;
}
