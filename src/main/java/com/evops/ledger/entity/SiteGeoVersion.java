package com.evops.ledger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 经纬度建档版本：每录一次坐标记一版，旧版置 SUPERSEDED 不抹历史。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_site_geo_version")
public class SiteGeoVersion extends BaseEntity {
    /** 所属点位 */
    private Long siteId;
    /** 版本号，点位内单调递增，首建 v1 */
    private Integer versionNo;
    /** 纬度（十进制度，DECIMAL 落库不丢小数位） */
    private BigDecimal lat;
    /** 经度（十进制度） */
    private BigDecimal lng;
    /** 版本来源：INITIAL 首次建档 / CORRECT 纠偏重录 */
    private String source;
    /** 版本状态：EFFECTIVE 生效中 / SUPERSEDED 已被新版取代 */
    private String status;
    /** 建档经手人 */
    private String operatorName;
    private String remark;
}
