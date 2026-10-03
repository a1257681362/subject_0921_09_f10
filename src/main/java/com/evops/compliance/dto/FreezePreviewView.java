package com.evops.compliance.dto;

import com.evops.compliance.entity.IndustryCategory;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 冻结/解冻预览结果：圈定的在途单明细 + 单数与含税占用合计，合规岗确认后再一键执行。 */
@Data
public class FreezePreviewView {
    private IndustryCategory category;
    /** 圈定的单据数（预览口径） */
    private Integer totalCount;
    private BigDecimal totalAmount;
    private List<FrozenOrderItem> orders;
    /** 一键执行时实际处理的单数（条件更新命中数，预览接口不返回） */
    private Integer affectedCount;
}
