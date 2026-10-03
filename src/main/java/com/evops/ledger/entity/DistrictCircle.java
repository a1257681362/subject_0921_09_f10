package com.evops.ledger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 商圈圈界：拓展部圈的商圈落成中心坐标+半径（公里），重叠带判定以它为准。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_biz_district_circle")
public class DistrictCircle extends BaseEntity {
    /** 商圈名，全局唯一；与点位档案的主商圈同名即对应 */
    private String districtName;
    /** 圆心纬度（十进制度） */
    private BigDecimal centerLat;
    /** 圆心经度（十进制度） */
    private BigDecimal centerLng;
    /** 半径（公里） */
    private BigDecimal radiusKm;
    /** 圈定经手人 */
    private String operatorName;
    private String remark;
}
