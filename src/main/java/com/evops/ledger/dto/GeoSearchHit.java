package com.evops.ledger.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 半径检索结果行：圈里在营点位，按距离升序；重叠带标记只作提示，归属仍按主商圈。 */
@Data
public class GeoSearchHit {
    private Long siteId;
    private String siteNo;
    private String sizeSpec;
    private String orientation;
    /** 归属：录入时定的主商圈 */
    private String businessDistrict;
    /** 距检索圆心的公里数（展示保留 3 位小数） */
    private BigDecimal distanceKm;
    /** 建档坐标（十进制度，原样返回不丢小数位） */
    private BigDecimal lat;
    private BigDecimal lng;
    /** 经纬度建档状态 / 当前版本号 */
    private String geoStatus;
    private Integer geoVersionNo;
    /** 是否落在商圈重叠带（坐标同时落进 ≥2 个商圈圈界） */
    private boolean overlap;
    /** 坐标实际落进的商圈圈界名单 */
    private List<String> coveredDistricts;
}
