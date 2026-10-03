package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 经纬度建档请求：十进制度坐标，首建记 v1，重录另起新版本。 */
@Data
public class GeoArchiveRequest {
    /** 纬度（十进制度，[-90,90]，最多 7 位小数） */
    @NotNull(message = "纬度不能为空")
    private BigDecimal lat;
    /** 经度（十进制度，[-180,180]，最多 7 位小数） */
    @NotNull(message = "经度不能为空")
    private BigDecimal lng;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
