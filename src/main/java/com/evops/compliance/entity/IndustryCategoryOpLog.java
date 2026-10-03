package com.evops.compliance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 类目名单逐条操作流水（只追加，不改不删）：
 * 谁、什么时候、把哪个条目从什么改成什么，前后值 JSON 与依据都在。
 */
@Data
@TableName("t_industry_category_oplog")
public class IndustryCategoryOpLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String categoryCode;
    private String categoryName;
    /** ADD_CATEGORY / RESTRICT / RELEASE / MODIFY / DISABLE */
    private String action;
    /** 动作前条目 JSON（新增为空） */
    private String beforeJson;
    /** 动作后条目 JSON */
    private String afterJson;
    private String legalBasis;
    private String operatorName;
    private String remark;
    private LocalDateTime opTime;
}
