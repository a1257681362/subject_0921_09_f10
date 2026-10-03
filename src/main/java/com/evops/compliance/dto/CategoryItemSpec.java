package com.evops.compliance.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 名单条目配置：行业类目 + 受限依据。 */
@Data
public class CategoryItemSpec {
    /** 行业类目名（与客户档案/上刊单上的行业类目精确匹配） */
    @NotBlank(message = "行业类目不能为空")
    private String categoryName;
    /** 受限依据（法规条文/监管通报文号），拦截提示带它 */
    @NotBlank(message = "受限依据不能为空")
    private String basis;
}
