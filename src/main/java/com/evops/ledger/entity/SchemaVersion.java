package com.evops.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 底账结构版本登记。 */
@Data
@TableName("t_evops_schema_version")
public class SchemaVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String versionNo;
    private String description;
    private LocalDateTime createdAt;
}
