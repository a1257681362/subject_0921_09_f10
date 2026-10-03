package com.evops.ledger.support;

import com.evops.common.BusinessException;

import java.math.BigDecimal;

/**
 * 坐标口径：十进制度落库，小数位不丢——纬度 [-90,90]、经度 [-180,180]、最多 7 位小数，
 * 超位直接拒收，不静默截断；距离按 Haversine 球面公式算公里数。
 */
public final class GeoMath {

    private GeoMath() {}

    /** 坐标小数位上限（与 DECIMAL(10,7)/DECIMAL(11,7) 落库精度一致） */
    public static final int COORD_SCALE = 7;
    /** 半径小数位上限（米级精度） */
    public static final int RADIUS_SCALE = 3;
    /** 单次检索半径上限（公里），本地跑盘场景足够 */
    public static final BigDecimal MAX_RADIUS_KM = new BigDecimal("100");

    private static final BigDecimal LAT_MIN = new BigDecimal("-90");
    private static final BigDecimal LAT_MAX = new BigDecimal("90");
    private static final BigDecimal LNG_MIN = new BigDecimal("-180");
    private static final BigDecimal LNG_MAX = new BigDecimal("180");

    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** 校验纬度：必填、范围内、小数位不超 7 位。 */
    public static BigDecimal requireLat(BigDecimal lat) {
        if (lat == null) {
            throw new BusinessException("纬度不能为空");
        }
        if (lat.compareTo(LAT_MIN) < 0 || lat.compareTo(LAT_MAX) > 0) {
            throw new BusinessException("纬度超出范围 [-90, 90]：" + lat.toPlainString());
        }
        return requireScale(lat, COORD_SCALE, "纬度");
    }

    /** 校验经度：必填、范围内、小数位不超 7 位。 */
    public static BigDecimal requireLng(BigDecimal lng) {
        if (lng == null) {
            throw new BusinessException("经度不能为空");
        }
        if (lng.compareTo(LNG_MIN) < 0 || lng.compareTo(LNG_MAX) > 0) {
            throw new BusinessException("经度超出范围 [-180, 180]：" + lng.toPlainString());
        }
        return requireScale(lng, COORD_SCALE, "经度");
    }

    /** 校验检索/圈界半径（公里）：必填、大于 0、不超过上限、小数位不超 3 位。 */
    public static BigDecimal requireRadiusKm(BigDecimal radiusKm) {
        if (radiusKm == null) {
            throw new BusinessException("半径公里数不能为空");
        }
        if (radiusKm.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("半径公里数必须大于 0：" + radiusKm.toPlainString());
        }
        if (radiusKm.compareTo(MAX_RADIUS_KM) > 0) {
            throw new BusinessException("半径公里数超过上限 " + MAX_RADIUS_KM.toPlainString() + " 公里：" + radiusKm.toPlainString());
        }
        return requireScale(radiusKm, RADIUS_SCALE, "半径公里数");
    }

    /** 小数位超限直接拒收——坐标精度是录入方的承诺，系统不悄悄抹掉。 */
    private static BigDecimal requireScale(BigDecimal value, int maxScale, String label) {
        if (value.stripTrailingZeros().scale() > maxScale) {
            throw new BusinessException(label + "最多支持 " + maxScale + " 位小数，请核对后再录：" + value.toPlainString());
        }
        return value;
    }

    /** Haversine 球面距离（公里）。仅用于计算，落库始终是十进制度 DECIMAL。 */
    public static double distanceKm(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double la1 = Math.toRadians(lat1.doubleValue());
        double la2 = Math.toRadians(lat2.doubleValue());
        double dLat = la2 - la1;
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(la1) * Math.cos(la2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
