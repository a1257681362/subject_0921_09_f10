package com.evops.ratecard.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;

/** 报价提交请求：按提交当天的生效刊例计价，单价快照随行留底。 */
@Data
public class QuoteSubmitRequest {
    /** 客户名称 */
    @NotBlank(message = "客户名称不能为空")
    private String customerName;
    /** 提交日期，留空取今天；决定按哪一版刊例计价（不许按未来日期报价） */
    private LocalDate submitDate;
    /** 报价行：点位 + 周数 */
    @NotEmpty(message = "报价至少要有一行")
    @Valid
    private List<QuoteLineRequest> lines;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
