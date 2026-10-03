package com.evops.compliance.dto;

import lombok.Data;

/** 冻结处置（解冻/强制落架）：原因随记录留底。 */
@Data
public class DisposeRequest {
    private String reason;
}
