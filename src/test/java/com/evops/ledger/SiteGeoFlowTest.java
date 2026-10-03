package com.evops.ledger;

import com.evops.common.BusinessException;
import com.evops.ledger.dto.DistrictCircleRequest;
import com.evops.ledger.dto.GeoArchiveRequest;
import com.evops.ledger.dto.GeoSearchHit;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.DistrictCircle;
import com.evops.ledger.entity.Site;
import com.evops.ledger.entity.SiteGeoVersion;
import com.evops.ledger.service.DistrictCircleService;
import com.evops.ledger.service.SchemaVersionService;
import com.evops.ledger.service.SiteGeoService;
import com.evops.ledger.service.SiteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F3 经纬度建档与半径捞点口径端到端测试（真实 H2 + 真实事务）。 */
@SpringBootTest
class SiteGeoFlowTest {

    @Autowired private SiteService siteService;
    @Autowired private SiteGeoService siteGeoService;
    @Autowired private DistrictCircleService districtCircleService;
    @Autowired private SchemaVersionService schemaVersionService;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    private Site registerSite(String no, String district) {
        SiteCreateRequest req = new SiteCreateRequest();
        req.setSiteNo(no);
        req.setSizeSpec("18m×6m");
        req.setOrientation("朝北");
        req.setBusinessDistrict(district);
        req.setOperatorName("tester");
        return siteService.register(req);
    }

    private GeoArchiveRequest geo(String lat, String lng) {
        GeoArchiveRequest req = new GeoArchiveRequest();
        req.setLat(new BigDecimal(lat));
        req.setLng(new BigDecimal(lng));
        req.setOperatorName("tester");
        return req;
    }

    private DistrictCircleRequest circle(String name, String lat, String lng, String radiusKm) {
        DistrictCircleRequest req = new DistrictCircleRequest();
        req.setDistrictName(name);
        req.setCenterLat(new BigDecimal(lat));
        req.setCenterLng(new BigDecimal(lng));
        req.setRadiusKm(new BigDecimal(radiusKm));
        req.setOperatorName("tester");
        return req;
    }

    @Test
    void 坐标十进制度落库小数位不丢() {
        String no = uniqueNo("G");
        registerSite(no, "王府井");
        siteGeoService.archive(no, geo("39.9041999", "116.4073963"));

        Site site = siteService.getByNo(no);
        // 7 位小数原样回来，不少一位、不被浮点吃掉
        assertEquals("39.9041999", site.getLat().toPlainString());
        assertEquals("116.4073963", site.getLng().toPlainString());
        assertEquals("FILED", site.getGeoStatus());
        assertEquals(1, site.getGeoVersionNo());
    }

    @Test
    void 建档版本留痕_重录另起新版旧版不抹() {
        String no = uniqueNo("G");
        registerSite(no, "王府井");
        SiteGeoVersion v1 = siteGeoService.archive(no, geo("39.9041999", "116.4073963"));
        assertEquals(1, v1.getVersionNo());
        assertEquals("INITIAL", v1.getSource());
        assertEquals("EFFECTIVE", v1.getStatus());

        SiteGeoVersion v2 = siteGeoService.archive(no, geo("39.9042000", "116.4074000"));
        assertEquals(2, v2.getVersionNo());
        assertEquals("CORRECT", v2.getSource());

        List<SiteGeoVersion> versions = siteGeoService.versions(no);
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).getVersionNo()); // 最新在前
        assertEquals("EFFECTIVE", versions.get(0).getStatus());
        assertEquals("SUPERSEDED", versions.get(1).getStatus()); // 旧版留底不抹
        assertEquals("39.9041999", versions.get(1).getLat().toPlainString());

        // 点位档案上的坐标始终指向当前生效版本
        Site site = siteService.getByNo(no);
        assertEquals("39.9042000", site.getLat().toPlainString());
        assertEquals(2, site.getGeoVersionNo());
    }

    @Test
    void 坐标超界与小数位超限直接拒收() {
        String no = uniqueNo("G");
        registerSite(no, "王府井");
        BusinessException latEx = assertThrows(BusinessException.class,
                () -> siteGeoService.archive(no, geo("91", "116.4")));
        assertTrue(latEx.getMessage().contains("纬度超出范围"));
        BusinessException lngEx = assertThrows(BusinessException.class,
                () -> siteGeoService.archive(no, geo("39.9", "-181")));
        assertTrue(lngEx.getMessage().contains("经度超出范围"));
        // 第 8 位小数：拒收，不静默截断
        BusinessException scaleEx = assertThrows(BusinessException.class,
                () -> siteGeoService.archive(no, geo("39.12345678", "116.4")));
        assertTrue(scaleEx.getMessage().contains("最多支持 7 位小数"));
        // 拒收后仍是未建档
        assertEquals("UNFILED", siteService.getByNo(no).getGeoStatus());
    }

    @Test
    void 半径捞点_只在营已建档_按距离升序_圈外不出现() {
        // 各用例各占一片独立经纬度区域，共享库下互不串数据
        String a = uniqueNo("G");
        String b = uniqueNo("G");
        String c = uniqueNo("G");
        String d = uniqueNo("G");
        registerSite(a, "人民广场");
        registerSite(b, "人民广场");
        registerSite(c, "南京东路");
        registerSite(d, "人民广场");
        siteGeoService.archive(a, geo("31.2304000", "121.4737000")); // 圆心原地，0km
        siteGeoService.archive(b, geo("31.2404000", "121.4737000")); // 北约 1.1km
        siteGeoService.archive(c, geo("31.2504000", "121.4737000")); // 北约 2.2km，出圈
        // d 不建档坐标

        List<GeoSearchHit> hits = siteGeoService.search(
                new BigDecimal("31.2304000"), new BigDecimal("121.4737000"), new BigDecimal("2"));
        List<String> nos = hits.stream().map(GeoSearchHit::getSiteNo).collect(Collectors.toList());
        assertEquals(2, hits.size());
        assertEquals(a, nos.get(0)); // 距离最近排第一
        assertEquals(b, nos.get(1));
        assertFalse(nos.contains(c)); // 圈外点位不出现
        assertFalse(nos.contains(d)); // 未建档坐标不进结果
        assertEquals(0, hits.get(0).getDistanceKm().compareTo(new BigDecimal("0.000")));
        assertTrue(hits.get(1).getDistanceKm().compareTo(new BigDecimal("1.0")) > 0);
        assertTrue(hits.get(1).getDistanceKm().compareTo(new BigDecimal("1.2")) < 0);
    }

    @Test
    void 商圈重叠带标出来_归属仍按录入主商圈() {
        String a = uniqueNo("G");
        String b = uniqueNo("G");
        registerSite(a, "江汉路");
        registerSite(b, "江汉路"); // 录入时定的主商圈是江汉路
        siteGeoService.archive(a, geo("30.5700000", "114.3000000"));
        siteGeoService.archive(b, geo("30.5800000", "114.3000000"));
        districtCircleService.create(circle(uniqueNo("江汉路"), "30.5700000", "114.3000000", "2"));
        districtCircleService.create(circle(uniqueNo("楚河汉街"), "30.5900000", "114.3000000", "2"));

        List<GeoSearchHit> hits = siteGeoService.search(
                new BigDecimal("30.5700000"), new BigDecimal("114.3000000"), new BigDecimal("2"));
        GeoSearchHit hitA = hits.stream().filter(h -> h.getSiteNo().equals(a)).findFirst().get();
        GeoSearchHit hitB = hits.stream().filter(h -> h.getSiteNo().equals(b)).findFirst().get();

        assertFalse(hitA.isOverlap()); // 只落进一个圈
        assertEquals(1, hitA.getCoveredDistricts().size());

        assertTrue(hitB.isOverlap()); // 同时落进两个圈界：重叠带
        assertEquals(2, hitB.getCoveredDistricts().size());
        assertEquals("江汉路", hitB.getBusinessDistrict()); // 归属仍按录入时定的主商圈
    }

    @Test
    void 商圈圈界同名不能重复圈_重圈改写当前态() {
        String name = uniqueNo("商圈");
        districtCircleService.create(circle(name, "39.9042000", "116.4074000", "2"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> districtCircleService.create(circle(name, "39.9", "116.4", "1")));
        assertTrue(ex.getMessage().contains("已有圈界"));

        DistrictCircle redrawn = districtCircleService.redraw(name, circle(name, "39.9100000", "116.4100000", "3"));
        assertEquals("39.9100000", redrawn.getCenterLat().toPlainString());
        assertEquals(0, redrawn.getRadiusKm().compareTo(new BigDecimal("3")));
        assertEquals(1, districtCircleService.list().stream()
                .filter(d -> d.getDistrictName().equals(name)).count());
    }

    @Test
    void 整页导出与检索同口径_含表头与全部行() {
        String a = uniqueNo("G");
        String b = uniqueNo("G");
        registerSite(a, "华强北");
        registerSite(b, "车公庙");
        siteGeoService.archive(a, geo("22.5431000", "114.0579000"));
        siteGeoService.archive(b, geo("22.5531000", "114.0579000"));

        String csv = siteGeoService.exportCsv(
                new BigDecimal("22.5431000"), new BigDecimal("114.0579000"), new BigDecimal("2"));
        assertEquals('\uFEFF', csv.charAt(0)); // BOM：Excel 打开不乱码
        String[] lines = csv.split("\n");
        assertEquals(3, lines.length); // 表头 + 两个点位，整页全量
        assertTrue(lines[0].contains("主商圈(归属)"));
        assertTrue(lines[0].contains("商圈重叠带"));
        assertTrue(lines[1].contains(a) && lines[1].contains("22.5431000"));
        assertTrue(lines[2].contains(b) && lines[2].contains("车公庙"));
    }

    @Test
    void 底账版本登记包含f3起步版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f3-site-geo-1"));
    }
}
