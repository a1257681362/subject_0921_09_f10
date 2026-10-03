package com.evops.compliance.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 设为受限：法规依据必填——建档/上刊命中时它原样进拦截提示。 */
@Data
public class CategoryRestrictRequest {
    @NotBlank(message = "受限法规依据不能为空（拦截提示要带依据）")
    @Size(max = 500, message = "法规依据最长 500 字")
    private String legalBasis;
    private String remark;
}
