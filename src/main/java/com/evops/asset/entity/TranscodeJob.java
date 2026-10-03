package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 转档任务：多规格转档状态流水，失败原因随任务落库，支持重跑。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_transcode_job")
public class TranscodeJob extends BaseEntity {
    /** 任务号，全局唯一，TJ+序列 */
    private String jobNo;
    private Long assetId;
    private Long versionId;
    /** PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;
    /** 本次跑的规格清单（规格名，逗号分隔，便于翻查） */
    private String specs;
    /** 成功产出的规格数 */
    private Integer successCount;
    /** 失败原因：美工看得见，不干等 */
    private String failReason;
    private LocalDateTime finishedTime;
    private String operatorName;
}
