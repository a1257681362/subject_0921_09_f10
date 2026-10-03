package com.evops.credit.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 历史客户行业类目补录（F10）：仅当前为空可补，仅合规岗/系统。 */
@Data
public class IndustryCategoryFillRequest {
    @NotBlank(message = "行业类目不能为空")
    private String industryCategory;
}
