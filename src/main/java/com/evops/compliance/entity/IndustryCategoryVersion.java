package com.evops.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 类目名单版本（只追加）：每次维护动作自动落一版，即时生效——
 * 当版 EFFECTIVE，上一版置 SUPERSEDED；snapshot 为当版全量条目 JSON，历史永久可翻。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_industry_category_version")
public class IndustryCategoryVersion extends BaseEntity {
    /** 版本号，全局递增 */
    private Integer versionNo;
    /** INITIAL / ADD_CATEGORY / RESTRICT / RELEASE / MODIFY / DISABLE */
    private String changeType;
    /** 本次动作针对的类目编码（INITIAL 为空） */
    private String targetCode;
    /** EFFECTIVE 生效中 / SUPERSEDED 已被新版取代 */
    private String status;
    /** 当版全量条目数 */
    private Integer itemCount;
    /** 当版受限条目数 */
    private Integer restrictedCount;
    /** 当版全量条目快照 JSON */
    private String snapshot;
    /** 动作原因/说明 */
    private String reason;
    private String operatorName;
    private LocalDateTime effectiveTime;
}
