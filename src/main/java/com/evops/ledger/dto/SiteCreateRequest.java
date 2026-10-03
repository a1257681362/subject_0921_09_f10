package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class SiteCreateRequest {
    /** 点位编号，重复存不进去 */
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    /** 尺寸 */
    @NotBlank(message = "尺寸不能为空")
    private String sizeSpec;
    /** 朝向 */
    @NotBlank(message = "朝向不能为空")
    private String orientation;
    /** 商圈 */
    @NotBlank(message = "商圈不能为空")
    private String businessDistrict;
    /**
     * 作业性质：NORMAL 普通点位（默认）/ HIGH_ALTITUDE 高空点位。
     * 高空点位的安装派单只派给持在有效期内高空作业证的安装队。
     */
    private String workType;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
