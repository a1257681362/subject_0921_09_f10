package com.evops.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.dto.GeoArchiveRequest;
import com.evops.ledger.dto.GeoSearchHit;
import com.evops.ledger.entity.DistrictCircle;
import com.evops.ledger.entity.Site;
import com.evops.ledger.entity.SiteGeoVersion;
import com.evops.ledger.mapper.DistrictCircleMapper;
import com.evops.ledger.mapper.SiteGeoVersionMapper;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.support.GeoMath;
import com.evops.ledger.support.Operators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 经纬度建档与半径捞点。
 * 口径：坐标十进制度 DECIMAL 落库不丢小数位；每录一次记一版，旧版不抹；
 * 检索只捞在营（LISTED）且已建档的点位，按距离升序；
 * 落在 ≥2 个商圈圈界里的标重叠带，归属仍按录入时定的主商圈走。
 */
@Service
public class SiteGeoService {

    private final SiteMapper siteMapper;
    private final SiteGeoVersionMapper geoVersionMapper;
    private final DistrictCircleMapper districtCircleMapper;

    public SiteGeoService(SiteMapper siteMapper,
                          SiteGeoVersionMapper geoVersionMapper,
                          DistrictCircleMapper districtCircleMapper) {
        this.siteMapper = siteMapper;
        this.geoVersionMapper = geoVersionMapper;
        this.districtCircleMapper = districtCircleMapper;
    }

    /**
     * 经纬度建档：首建记 v1（INITIAL），纠偏重录另起新版本（CORRECT），旧版置 SUPERSEDED 不抹历史。
     * 点位档案上的坐标始终指向当前生效版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public SiteGeoVersion archive(String siteNo, GeoArchiveRequest request) {
        BigDecimal lat = GeoMath.requireLat(request.getLat());
        BigDecimal lng = GeoMath.requireLng(request.getLng());
        Site site = requireSite(siteNo);

        Integer nextVersion = geoVersionMapper.selectCount(
                new LambdaQueryWrapper<SiteGeoVersion>().eq(SiteGeoVersion::getSiteId, site.getId())).intValue() + 1;

        // 旧版全部置“已被取代”，历史留底不抹
        SiteGeoVersion supersede = new SiteGeoVersion();
        supersede.setStatus(LedgerEnums.GEO_VERSION_SUPERSEDED);
        geoVersionMapper.update(supersede, new LambdaUpdateWrapper<SiteGeoVersion>()
                .eq(SiteGeoVersion::getSiteId, site.getId())
                .eq(SiteGeoVersion::getStatus, LedgerEnums.GEO_VERSION_EFFECTIVE));

        SiteGeoVersion version = new SiteGeoVersion();
        version.setSiteId(site.getId());
        version.setVersionNo(nextVersion);
        version.setLat(lat);
        version.setLng(lng);
        version.setSource(nextVersion == 1 ? LedgerEnums.GEO_SOURCE_INITIAL : LedgerEnums.GEO_SOURCE_CORRECT);
        version.setStatus(LedgerEnums.GEO_VERSION_EFFECTIVE);
        version.setOperatorName(Operators.resolve(request.getOperatorName()));
        version.setRemark(request.getRemark());
        geoVersionMapper.insert(version);

        Site current = new Site();
        current.setId(site.getId());
        current.setLat(lat);
        current.setLng(lng);
        current.setGeoStatus(LedgerEnums.GEO_FILED);
        current.setGeoVersionNo(nextVersion);
        siteMapper.updateById(current);
        return version;
    }

    /** 建档版本翻查：最新版在前，历史版可回翻。 */
    public List<SiteGeoVersion> versions(String siteNo) {
        Site site = requireSite(siteNo);
        return geoVersionMapper.selectList(new LambdaQueryWrapper<SiteGeoVersion>()
                .eq(SiteGeoVersion::getSiteId, site.getId())
                .orderByDesc(SiteGeoVersion::getVersionNo));
    }

    /** 半径捞点：给个坐标、给个公里数，圈里在营点位按距离升序列出，重叠带标出来。 */
    public List<GeoSearchHit> search(BigDecimal lat, BigDecimal lng, BigDecimal radiusKm) {
        BigDecimal centerLat = GeoMath.requireLat(lat);
        BigDecimal centerLng = GeoMath.requireLng(lng);
        BigDecimal radius = GeoMath.requireRadiusKm(radiusKm);

        List<Site> listed = siteMapper.selectList(new LambdaQueryWrapper<Site>()
                .eq(Site::getStatus, LedgerEnums.SITE_LISTED)
                .eq(Site::getGeoStatus, LedgerEnums.GEO_FILED));
        List<DistrictCircle> circles = districtCircleMapper.selectList(null);

        List<GeoSearchHit> hits = new ArrayList<>();
        for (Site site : listed) {
            double distance = GeoMath.distanceKm(centerLat, centerLng, site.getLat(), site.getLng());
            if (distance > radius.doubleValue()) {
                continue;
            }
            GeoSearchHit hit = new GeoSearchHit();
            hit.setSiteId(site.getId());
            hit.setSiteNo(site.getSiteNo());
            hit.setSizeSpec(site.getSizeSpec());
            hit.setOrientation(site.getOrientation());
            hit.setBusinessDistrict(site.getBusinessDistrict());
            hit.setDistanceKm(BigDecimal.valueOf(distance).setScale(3, RoundingMode.HALF_UP));
            hit.setLat(site.getLat());
            hit.setLng(site.getLng());
            hit.setGeoStatus(site.getGeoStatus());
            hit.setGeoVersionNo(site.getGeoVersionNo());
            List<String> covered = circles.stream()
                    .filter(c -> GeoMath.distanceKm(site.getLat(), site.getLng(), c.getCenterLat(), c.getCenterLng())
                            <= c.getRadiusKm().doubleValue())
                    .map(DistrictCircle::getDistrictName)
                    .sorted()
                    .collect(Collectors.toList());
            hit.setCoveredDistricts(covered);
            hit.setOverlap(covered.size() >= 2);
            hits.add(hit);
        }
        // 按距离升序，距离并列按点位编号稳住顺序
        hits.sort(Comparator.comparing(GeoSearchHit::getDistanceKm).thenComparing(GeoSearchHit::getSiteNo));
        return hits;
    }

    /** 整页导出：与检索同口径的完整结果集，CSV 文本（调用方负责写响应头）。 */
    public String exportCsv(BigDecimal lat, BigDecimal lng, BigDecimal radiusKm) {
        List<GeoSearchHit> hits = search(lat, lng, radiusKm);
        // BOM 头：跑盘同事用 Excel 打开 CSV 时中文不乱码
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("点位编号,尺寸,朝向,主商圈(归属),距离(km),纬度,经度,商圈重叠带,覆盖商圈,建档状态,建档版本\n");
        for (GeoSearchHit hit : hits) {
            csv.append(escape(hit.getSiteNo())).append(',')
                    .append(escape(hit.getSizeSpec())).append(',')
                    .append(escape(hit.getOrientation())).append(',')
                    .append(escape(hit.getBusinessDistrict())).append(',')
                    .append(hit.getDistanceKm().toPlainString()).append(',')
                    .append(hit.getLat().toPlainString()).append(',')
                    .append(hit.getLng().toPlainString()).append(',')
                    .append(hit.isOverlap() ? "是" : "否").append(',')
                    .append(escape(String.join("、", hit.getCoveredDistricts()))).append(',')
                    .append(LedgerEnums.GEO_FILED.equals(hit.getGeoStatus()) ? "已建档" : "未建档").append(',')
                    .append(hit.getGeoVersionNo() == null ? "" : "v" + hit.getGeoVersionNo())
                    .append('\n');
        }
        return csv.toString();
    }

    /** CSV 单元格转义：含逗号/引号/换行时整体加引号，内部引号翻倍。 */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private Site requireSite(String siteNo) {
        Site site = siteMapper.selectOne(
                new LambdaQueryWrapper<Site>().eq(Site::getSiteNo, siteNo == null ? "" : siteNo.trim()));
        if (site == null) {
            throw new BusinessException("点位不存在: " + siteNo);
        }
        return site;
    }
}
