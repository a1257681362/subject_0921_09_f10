package com.evops.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 名单条目（随版本冻结）：行业类目 + 受限依据（法规条文/通报文号），拦截提示带它。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_category_list_item")
public class CategoryListItem extends BaseEntity {
    /** 所属名单版本 */
    private Long versionId;
    /** 行业类目名（与客户档案/上刊单上的行业类目精确匹配） */
    private String categoryName;
    /** 受限依据（法规条文/监管通报文号），拦截提示必须带 */
    private String basis;
}
