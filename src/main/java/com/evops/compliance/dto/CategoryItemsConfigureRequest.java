package com.evops.compliance.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.List;

/** 草稿版本整表配置条目（发布后冻结，只能 DRAFT 配）。 */
@Data
public class CategoryItemsConfigureRequest {
    /** 整表条目：空列表=空名单（全部放行） */
    @NotNull(message = "条目列表不能为 null（空名单请传空数组）")
    @Valid
    private List<CategoryItemSpec> items;
}
