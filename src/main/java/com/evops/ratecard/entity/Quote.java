package com.evops.ratecard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 报价单：按提交当天的生效刊例计价。
 * 刊例年份/版本/税率全部随单固化，刊例后续调价不回写旧单——只认快照行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_quote")
public class Quote extends BaseEntity {
    /** 报价单号，QT+序列，全局唯一 */
    private String quoteNo;
    /** 客户名称 */
    private String customerName;
    /** 提交日期（决定按哪一版刊例计价，落库固化） */
    private LocalDate submitDate;
    /** 计价时使用的刊例年份 */
    private Integer rateYear;
    /** 计价刊例 ID/编号快照 */
    private Long rateCardId;
    private String rateCardNo;
    /** 计价版本 ID/版本号快照 */
    private Long versionId;
    private Integer versionNo;
    /** 税率快照 */
    private BigDecimal taxRate;
    /** 未税合计 */
    private BigDecimal totalExTax;
    /** 税额合计 */
    private BigDecimal totalTax;
    /** 含税合计（默认展示口径） */
    private BigDecimal totalInclTax;
    /** 状态：SUBMITTED 已提交 */
    private String status;
    /** 经手人 */
    private String operatorName;
    private String remark;
}
