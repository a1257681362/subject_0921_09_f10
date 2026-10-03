package com.evops.compliance.dto;

import lombok.Data;

/** 解除受限 / 停用类目的备注。 */
@Data
public class CategoryRemarkRequest {
    private String remark;
}
