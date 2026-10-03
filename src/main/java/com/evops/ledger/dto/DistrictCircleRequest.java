package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 商圈圈界请求：中心坐标+半径（公里），把拓展部圈的商圈落成可算的圆。 */
@Data
public class DistrictCircleRequest {
    /** 商圈名（与点位档案的主商圈同名即对应），唯一 */
    @NotBlank(message = "商圈名不能为空")
    private String districtName;
    /** 圆心纬度（十进制度） */
    @NotNull(message = "圆心纬度不能为空")
    private BigDecimal centerLat;
    /** 圆心经度（十进制度） */
    @NotNull(message = "圆心经度不能为空")
    private BigDecimal centerLng;
    /** 半径（公里，大于 0，最多 3 位小数） */
    @NotNull(message = "半径公里数不能为空")
    private BigDecimal radiusKm;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
