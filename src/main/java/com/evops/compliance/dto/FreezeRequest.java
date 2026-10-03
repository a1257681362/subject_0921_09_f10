package com.evops.compliance.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 按类目圈定在途单（冻结预览/一键执行共用）。 */
@Data
public class FreezeRequest {
    @NotBlank(message = "类目编码不能为空")
    private String categoryCode;
    /** 执行备注，落冻结事件留底 */
    private String remark;
}
