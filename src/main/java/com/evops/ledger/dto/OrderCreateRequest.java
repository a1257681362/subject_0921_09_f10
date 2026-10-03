package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

@Data
public class OrderCreateRequest {
    /** 点位编号 */
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    /** 画面名称 */
    @NotBlank(message = "画面名称不能为空")
    private String creativeName;
    /**
     * 引用的已提交报价单号（QT...）。填写即为授信上刊单：客户与含税占用额从报价单快照取，
     * 提交时校验并占用客户信用额度，余额不足直接拒收；留空为普通上刊单，不占额度。
     */
    private String quoteNo;
    /** 上刊发生日，决定所在周与归属月；留空取今天 */
    private LocalDate occurDate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
