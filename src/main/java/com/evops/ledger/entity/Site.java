package com.evops.ledger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 点位档案：编号、尺寸、朝向、商圈。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_site")
public class Site extends BaseEntity {
    /** 点位编号，全局唯一，重复存不进去 */
    private String siteNo;
    /** 尺寸规格，例如 18m×6m */
    private String sizeSpec;
    /** 朝向，例如 朝北 / 东南 */
    private String orientation;
    /** 商圈（录入时定的主商圈，点位归属按它走） */
    private String businessDistrict;
    /** 状态：LISTED 挂牌 */
    private String status;
    /** 作业性质：NORMAL 普通点位 / HIGH_ALTITUDE 高空点位（F8 安装派单资质门槛依据），默认普通 */
    private String workType;
    /** 纬度（十进制度，DECIMAL 落库不丢小数位），未建档为 null */
    private BigDecimal lat;
    /** 经度（十进制度），未建档为 null */
    private BigDecimal lng;
    /** 经纬度建档状态：UNFILED 未建档 / FILED 已建档 */
    private String geoStatus;
    /** 当前生效的经纬度建档版本号，未建档为 null */
    private Integer geoVersionNo;
    /** 挂牌经手人 */
    private String operatorName;
    private String remark;
}
