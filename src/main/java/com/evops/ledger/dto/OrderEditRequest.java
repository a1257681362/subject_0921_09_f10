package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 挂起中单据的留痕更正（仅画面名、备注等非档期字段）；档期开错请走对冲单。 */
@Data
public class OrderEditRequest {
    @NotBlank(message = "画面名称不能为空")
    private String creativeName;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
