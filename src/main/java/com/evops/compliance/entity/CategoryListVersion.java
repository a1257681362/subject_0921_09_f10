package com.evops.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 类目名单版本：草稿可反复整表配置条目；发布即时生效，旧生效版自动 SUPERSEDED；
 * 生效版不可作废（不留无名单空窗）。版本记经手人/发布时刻/调整说明，版本链即操作记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_category_list_version")
public class CategoryListVersion extends BaseEntity {
    /** 所属名单主档 */
    private Long listId;
    /** 版本号，名单内单调递增，首版 v1 */
    private Integer versionNo;
    /** 类目名单状态：DRAFT 编制中 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代 */
    private String status;
    /** 调整说明（发布时填，操作记录的一部分） */
    private String changeSummary;
    /** 版本内条目数 */
    private Integer itemCount;
    /** 发布时刻（生效起点） */
    private LocalDateTime publishTime;
    /** 经手人（建稿人；发布时更新为发布人） */
    private String operatorName;
    private String remark;
}
