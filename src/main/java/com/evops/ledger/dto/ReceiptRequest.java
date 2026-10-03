package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

/** 外部回执挂回单据：给对冲单号也能回溯到根单。 */
@Data
public class ReceiptRequest {
    @NotBlank(message = "回执编号不能为空")
    private String receiptNo;
    /** 回执指向的单号（上刊单或对冲单均可） */
    @NotBlank(message = "关联单号不能为空")
    private String orderNo;
    /** 回执来源，如 监测平台 / 业主签收回传 */
    private String source;
    private String content;
    /** 回执发生日，留空取今天 */
    private LocalDate occurDate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
}
