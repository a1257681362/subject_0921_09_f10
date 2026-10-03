package com.evops.credit.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/** 客户建档：给客户名与初始信用额度；建档即 v1 直接生效。 */
@Data
public class CustomerCreateRequest {
    /** 客户名称，须与报价单上的客户名完全一致；全局唯一 */
    @NotBlank(message = "客户名称不能为空")
    private String customerName;
    /** 归属销售账号；销售建档留空默认归当前登录销售，财务可指定 */
    private String ownerSales;
    private String contactName;
    /** 初始信用额度（含税口径，到分） */
    @NotNull(message = "信用额度不能为空")
    @PositiveOrZero(message = "信用额度不能为负")
    private BigDecimal creditLimit;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
