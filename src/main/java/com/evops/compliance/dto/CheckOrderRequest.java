package com.evops.compliance.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 上刊页预检：按报价单号追到客户行业，看是否命中受限。 */
@Data
public class CheckOrderRequest {
    @NotBlank(message = "报价单号不能为空")
    private String quoteNo;
}
