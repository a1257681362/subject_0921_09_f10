package com.evops.compliance.dto;

import lombok.Data;

import java.util.List;

/** 批量冻结在途单：类目集留空=当前生效版全量类目。 */
@Data
public class FreezeRequest {
    /** 要冻结的行业类目集；留空取当前生效名单全量类目 */
    private List<String> categories;
    /** 冻结原因（随批次留底） */
    private String reason;
}
