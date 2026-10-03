package com.evops.compliance.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 在途单冻结/解冻预览行：一张挂起授信单一行。 */
@Data
public class FrozenOrderItem {
    private String orderNo;
    private Long customerId;
    private String customerName;
    private String industryCategoryCode;
    private String industryCategoryName;
    private Long siteId;
    private String creativeName;
    private String bizPeriod;
    private BigDecimal occupiedAmount;
}
