package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 改期通知（每次改期通知到队留一条，只追加）：记录新旧档期、原因、经手人。
 * (dispatchId, seq) 唯一：同一派单第几次改期可翻。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_notice")
public class InstallNotice extends BaseEntity {
    private Long dispatchId;
    private String dispatchNo;
    private Long teamId;
    private String teamNo;
    /** 该派单第几次改期通知（从 1 起） */
    private Integer seq;
    private LocalDateTime oldStart;
    private LocalDateTime oldEnd;
    private LocalDateTime newStart;
    private LocalDateTime newEnd;
    private String reason;
    private String operatorName;
    private LocalDateTime noticeTime;
}
