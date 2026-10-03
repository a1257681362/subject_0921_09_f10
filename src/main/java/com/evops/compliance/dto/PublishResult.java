package com.evops.compliance.dto;

import com.evops.compliance.entity.CategoryListVersion;
import lombok.Data;

import java.util.List;

/** 发布结果：新版本 + 相对旧生效版的类目增减（前端据此提示一键批量冻结）。 */
@Data
public class PublishResult {
    private CategoryListVersion version;
    /** 本次新增受限类目（旧生效版没有） */
    private List<String> addedCategories;
    /** 本次移出受限类目 */
    private List<String> removedCategories;

    public PublishResult(CategoryListVersion version, List<String> addedCategories, List<String> removedCategories) {
        this.version = version;
        this.addedCategories = addedCategories;
        this.removedCategories = removedCategories;
    }
}
