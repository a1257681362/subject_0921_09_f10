package com.evops.compliance.dto;

import lombok.Data;

/** 发布名单版本：即时生效，旧生效版自动 SUPERSEDED。 */
@Data
public class PublishRequest {
    /** 调整说明（操作记录的一部分，随版本留底） */
    private String changeSummary;
}
