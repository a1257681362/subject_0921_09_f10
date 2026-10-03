package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 客户档案：客户名全局唯一，与报价单 t_quote.customer_name 精确对应。
 * 额度只认这一户的账；occupied_amount 为当前在额占用（挂起未撤刊 + 挂起期间未核销）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_customer")
public class Customer extends BaseEntity {
    /** 客户编号 CU+序列 */
    private String customerNo;
    /** 客户名称，全局唯一（报价单按它认领客户） */
    private String customerName;
    /** 归属销售账号（销售只能看/操作自己名下客户） */
    private String ownerSales;
    /** 信用额度（当前生效版本后值） */
    private BigDecimal creditLimit;
    /** 当前在额占用合计 */
    private BigDecimal occupiedAmount;
    /** ACTIVE 在档 / FROZEN 冻结 */
    private String creditStatus;
    private Long currentVersionId;
    private Integer currentVersionNo;
    private String contactName;
    private String operatorName;
    private String remark;
}
