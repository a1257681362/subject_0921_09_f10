package com.evops.ratecard.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 报价行：提交当时的单价快照。
 * 点位挂牌价、落档档位、档位比例、折后单价、未税/税额/含税金额全部随行留底，
 * 之后刊例调价、点位改名都与本行无关，财务对账只认这一行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_quote_line")
public class QuoteLine extends BaseEntity {
    /** 所属报价单 */
    private Long quoteId;
    /** 行号 */
    private Integer lineNo;
    /** 点位 ID */
    private Long siteId;
    /** 点位编号快照 */
    private String siteNo;
    /** 尺寸快照 */
    private String sizeSpec;
    /** 朝向快照 */
    private String orientation;
    /** 商圈快照 */
    private String businessDistrict;
    /** 购买档期长度（周） */
    private Integer weeks;
    /** 落档档位序号 */
    private Integer tierNo;
    /** 档位比例快照 */
    private BigDecimal discountRate;
    /** 挂牌周单价快照（未税，折前） */
    private BigDecimal listPrice;
    /** 折后周单价快照（未税） */
    private BigDecimal unitPrice;
    /** 未税金额（折后单价 × 周数） */
    private BigDecimal amountExTax;
    /** 税率快照 */
    private BigDecimal taxRate;
    /** 税额 */
    private BigDecimal taxAmount;
    /** 含税金额（默认展示口径） */
    private BigDecimal amountInclTax;
}
