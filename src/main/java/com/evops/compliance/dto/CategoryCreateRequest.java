package com.evops.compliance.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** 合规岗新增行业类目：编码全局唯一；新增条目默认为普通在档，受限需另行“设为受限”并填依据。 */
@Data
public class CategoryCreateRequest {
    @NotBlank(message = "类目编码不能为空")
    @Pattern(regexp = "[A-Z0-9_]{1,32}", message = "类目编码只能用大写字母、数字、下划线，最长 32 位")
    private String categoryCode;
    @NotBlank(message = "类目名称不能为空")
    @Size(max = 64, message = "类目名称最长 64 字")
    private String categoryName;
    private String remark;
}
