package com.evops.compliance.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 修改类目：改名（建档客户存的是快照，旧客户不回写）；受限条目可同步更新依据。 */
@Data
public class CategoryModifyRequest {
    @NotBlank(message = "类目名称不能为空")
    @Size(max = 64, message = "类目名称最长 64 字")
    private String categoryName;
    /** 留空表示不改依据；受限条目清空依据会被服务层拒绝 */
    @Size(max = 500, message = "法规依据最长 500 字")
    private String legalBasis;
    private String remark;
}
