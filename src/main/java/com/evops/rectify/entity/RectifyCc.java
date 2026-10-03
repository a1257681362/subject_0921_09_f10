package com.evops.rectify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 超期抄送记录（只追加）：每超一级抄送一条。
 * (rectifyId, ccLevel) 唯一约束兜底，定时任务重复扫也不会重发。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_rectify_cc")
public class RectifyCc extends BaseEntity {
    private Long rectifyId;
    private String rectifyNo;
    /** 抄送级别，从 1 起，逐级往上 */
    private Integer ccLevel;
    /** 抄送给谁（岗位/人，按配置取） */
    private String ccTarget;
    /** 触发时已超期限的整小时数 */
    private Integer overdueHours;
    private String reason;
    private LocalDateTime triggerTime;
}
