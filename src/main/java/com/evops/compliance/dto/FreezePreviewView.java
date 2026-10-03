package com.evops.compliance.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 批量冻结命中预览：按类目集命中的在途单与涉及客户数。 */
@Data
public class FreezePreviewView {
    /** 参与匹配的类目 → 受限依据 */
    private Map<String, String> categoryBasis;
    private Integer orderCount;
    private Integer customerCount;
    private List<HitOrder> orders;

    @Data
    public static class HitOrder {
        private String orderNo;
        private String industryCategory;
        private String customerName;
        private String creativeName;
        private LocalDate weekStart;
        private LocalDate weekEnd;
        private String operatorName;
    }
}
