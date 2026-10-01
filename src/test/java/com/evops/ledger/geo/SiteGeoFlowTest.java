package com.evops.ledger.geo;

import com.evops.common.BusinessException;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.Site;
import com.evops.ledger.geo.dto.GeoDetailView;
import com.evops.ledger.geo.dto.GeoLocateRequest;
import com.evops.ledger.geo.dto.GeoVoidRequest;
import com.evops.ledger.geo.dto.NearbyView;
import com.evops.ledger.geo.entity.SiteGeo;
import com.evops.ledger.geo.entity.SiteGeoVersion;
import com.evops.ledger.geo.service.SiteGeoService;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.service.SchemaVersionService;
import com.evops.ledger.service.SiteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F3 经纬度建档 + 按半径圈选口径端到端测试（真实 H2 + 真实事务）。 */
@SpringBootTest
class SiteGeoFlowTest {

    @Autowired private SiteGeoService geoService;
    @Autowired private SiteService siteService;
    @Autowired private SiteMapper siteMapper;
    @Autowired private SchemaVersionService schemaVersionService;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    /** 挂牌一个点位。 */
    private Site register(String district) {
        SiteCreateRequest req = new SiteCreateRequest();
        req.setSiteNo(uniqueNo("G"));
        req.setSizeSpec("18m×6m");
        req.setOrientation("朝北");
        req.setBusinessDistrict(district);
        req.setOperatorName("tester");
        return siteService.register(req);
    }

    private GeoLocateRequest locateReq(Site site, String lon, String lat, String district) {
        GeoLocateRequest req = new GeoLocateRequest();
        req.setSiteNo(site.getSiteNo());
        req.setLongitude(new BigDecimal(lon));
        req.setLatitude(new BigDecimal(lat));
        req.setBusinessDistrict(district);
        req.setOperatorName("tester");
        req.setOccurDate(LocalDate.of(2026, 9, 28));
        return req;
    }

    // ---- 建档：十进制度、小数位不丢、归属按录入时主商圈 ----

    @Test
    void 建档坐标十进制度七位小数原样落库不丢位() {
        Site site = register("王府井");
        geoService.locate(locateReq(site, "116.4108330", "39.9087220", null));

        GeoDetailView detail = geoService.detail(site.getSiteNo());
        SiteGeo geo = detail.getGeo();
        assertEquals(0, new BigDecimal("116.4108330").compareTo(geo.getLongitude()));
        assertEquals(0, new BigDecimal("39.9087220").compareTo(geo.getLatitude()));
        // 小数位真在库里（7 位），不是被截成 6 位
        assertEquals(7, geo.getLongitude().scale());
        assertEquals(7, geo.getLatitude().scale());
        assertEquals("LOCATED", geo.getGeoStatus());
        assertEquals(1, geo.getLatestVersionNo());
    }

    @Test
    void 建档时主商圈固化_随后点位档案改商圈归属不漂移() {
        Site site = register("王府井");
        geoService.locate(locateReq(site, "116.4100000", "39.9000000", "王府井主圈"));

        // 点位档案后来改了商圈（模拟档案维护），坐标归属仍按建档时录入的走
        site.setBusinessDistrict("东单延伸带");
        siteMapper.updateById(site);

        NearbyView view = geoService.nearby(new BigDecimal("116.4100000"), new BigDecimal("39.9000000"), new BigDecimal("1"));
        NearbyView.Item hit = view.getItems().stream()
                .filter(i -> i.getSiteId().equals(site.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("王府井主圈", hit.getBusinessDistrict());
        assertEquals("东单延伸带", hit.getSiteBusinessDistrict());
    }

    @Test
    void 建档主商圈留空则取点位档案当前商圈() {
        Site site = register("西单");
        SiteGeo geo = geoService.locate(locateReq(site, "116.3730000", "39.9080000", null));
        assertEquals("西单", geo.getBusinessDistrict());
    }

    @Test
    void 不存在的点位不能建档坐标() {
        GeoLocateRequest req = new GeoLocateRequest();
        req.setSiteNo("NO-SUCH-SITE-999");
        req.setLongitude(new BigDecimal("116.4000000"));
        req.setLatitude(new BigDecimal("39.9000000"));
        req.setOperatorName("tester");
        BusinessException ex = assertThrows(BusinessException.class, () -> geoService.locate(req));
        assertTrue(ex.getMessage().contains("点位不存在"));
    }

    // ---- 版本与状态 ----

    @Test
    void 坐标更正不抹历史另起版本_作废留版且可重新建档() {
        Site site = register("国贸");
        geoService.locate(locateReq(site, "116.4580000", "39.9090000", null));

        // 完全一致的“更正”拒绝，不白留版本
        BusinessException dup = assertThrows(BusinessException.class,
                () -> geoService.locate(locateReq(site, "116.4580000", "39.9090000", null)));
        assertTrue(dup.getMessage().contains("一致"));

        GeoLocateRequest fix = locateReq(site, "116.4581200", "39.9091500", "国贸核心区");
        fix.setOccurDate(LocalDate.of(2026, 9, 29));
        fix.setReason("跑盘复测坐标偏移");
        SiteGeo fixed = geoService.locate(fix);
        assertEquals(2, fixed.getLatestVersionNo());
        assertEquals("国贸核心区", fixed.getBusinessDistrict());

        GeoDetailView detail = geoService.detail(site.getSiteNo());
        List<SiteGeoVersion> versions = detail.getVersions();
        assertEquals(2, versions.size());
        assertEquals("LOCATE", versions.get(0).getChangeType());
        assertEquals("CORRECT", versions.get(1).getChangeType());
        assertEquals(0, new BigDecimal("116.4580000").compareTo(versions.get(0).getLongitude()));
        assertEquals("tester", versions.get(1).getOperatorName());

        // 作废：状态翻面、留 VOID 版本，不能重复作废
        GeoVoidRequest voidReq = new GeoVoidRequest();
        voidReq.setReason("点位撤销");
        voidReq.setOperatorName("tester");
        voidReq.setOccurDate(LocalDate.of(2026, 9, 30));
        SiteGeo voided = geoService.voidGeo(site.getSiteNo(), voidReq);
        assertEquals("VOID", voided.getGeoStatus());
        assertEquals(3, voided.getLatestVersionNo());
        assertNotNull(voided.getVoidTime());
        assertThrows(BusinessException.class, () -> geoService.voidGeo(site.getSiteNo(), voidReq));

        // 作废后重新建档：翻回在营，续一版
        SiteGeo relocated = geoService.locate(locateReq(site, "116.4582000", "39.9092000", "国贸核心区"));
        assertEquals("LOCATED", relocated.getGeoStatus());
        assertEquals(4, relocated.getLatestVersionNo());
        assertNull(relocated.getVoidReason());
        assertEquals(4, geoService.detail(site.getSiteNo()).getVersions().size());
    }

    // ---- 按半径圈选 ----

    @Test
    void 按半径捞点位_圈内在营全列出_按距离升序_圈外不捞() {
        // 独立坐标区（杭州一带），避开本类其他用例的点位；纬度每 0.001°约 111 米
        BigDecimal cx = new BigDecimal("120.1500000");
        BigDecimal cy = new BigDecimal("30.2500000");
        Site near = register("武林");
        Site mid = register("武林");
        Site far = register("武林");
        geoService.locate(locateReq(near, "120.1500000", "30.2527000", null)); // ~300m 北
        geoService.locate(locateReq(mid, "120.1500000", "30.2680000", null));  // ~2.0km 北
        geoService.locate(locateReq(far, "120.1500000", "30.3850000", null));  // ~15km 北

        NearbyView view = geoService.nearby(cx, cy, new BigDecimal("3"));
        List<String> nos = view.getItems().stream().map(NearbyView.Item::getSiteNo).collect(Collectors.toList());
        assertEquals(2, view.getTotal());
        assertTrue(nos.contains(near.getSiteNo()) && nos.contains(mid.getSiteNo()));
        assertFalse(nos.contains(far.getSiteNo()));
        // 距离升序
        assertEquals(near.getSiteNo(), view.getItems().get(0).getSiteNo());
        assertEquals(mid.getSiteNo(), view.getItems().get(1).getSiteNo());
        int d1 = view.getItems().get(0).getDistanceMeters();
        int d2 = view.getItems().get(1).getDistanceMeters();
        assertTrue(d1 > 290 && d1 < 310, "近点约 300m，实际 " + d1);
        assertTrue(d2 > 1980 && d2 < 2030, "中点约 2000m，实际 " + d2);
        assertTrue(d1 < d2);
    }

    @Test
    void 圈选只捞在营点位_未建档与已作废不出现() {
        // 独立坐标区（广州一带）
        BigDecimal cx = new BigDecimal("113.2500000");
        BigDecimal cy = new BigDecimal("23.1500000");
        Site withGeo = register("天河");
        Site noGeo = register("天河");
        Site voidedSite = register("天河");
        geoService.locate(locateReq(withGeo, "113.2500000", "23.1500000", null));
        geoService.locate(locateReq(voidedSite, "113.2501000", "23.1501000", null));
        GeoVoidRequest voidReq = new GeoVoidRequest();
        voidReq.setReason("录错作废");
        voidReq.setOperatorName("tester");
        geoService.voidGeo(voidedSite.getSiteNo(), voidReq);

        NearbyView view = geoService.nearby(cx, cy, new BigDecimal("2"));
        List<String> nos = view.getItems().stream().map(NearbyView.Item::getSiteNo).collect(Collectors.toList());
        assertTrue(nos.contains(withGeo.getSiteNo()));
        assertFalse(nos.contains(noGeo.getSiteNo()));
        assertFalse(nos.contains(voidedSite.getSiteNo()));
    }

    @Test
    void 一圈圈进多个主商圈_命中点位标在商圈重叠带() {
        // 独立坐标区（西安一带）
        Site a = register("档案商圈A");
        Site b = register("档案商圈B");
        Site c = register("档案商圈C");
        geoService.locate(locateReq(a, "108.9505000", "34.2700000", "小寨"));
        geoService.locate(locateReq(b, "108.9495000", "34.2700000", "大雁塔"));
        geoService.locate(locateReq(c, "109.2000000", "34.5000000", "浐灞"));

        NearbyView overlapView = geoService.nearby(new BigDecimal("108.9500000"), new BigDecimal("34.2700000"),
                new BigDecimal("1"));
        assertEquals(2, overlapView.getDistrictCount());
        assertEquals(2, overlapView.getTotal());
        for (NearbyView.Item item : overlapView.getItems()) {
            assertTrue(item.isOverlap());
            assertEquals(1, item.getOverlapDistricts().size());
            assertTrue(item.getOverlapDistricts().contains(
                "小寨".equals(item.getBusinessDistrict()) ? "大雁塔" : "小寨"));
        }

        // 只圈到单个主商圈时不标重叠
        NearbyView single = geoService.nearby(new BigDecimal("109.2000000"), new BigDecimal("34.5000000"),
                new BigDecimal("1"));
        assertEquals(1, single.getDistrictCount());
        assertEquals(1, single.getTotal());
        assertFalse(single.getItems().get(0).isOverlap());
        assertTrue(single.getItems().get(0).getOverlapDistricts().isEmpty());
    }

    // ---- 入参口径 ----

    @Test
    void 坐标超七位小数拒绝_不悄悄截位() {
        Site site = register("丽都");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> geoService.locate(locateReq(site, "116.40000001", "39.9000000", null)));
        assertTrue(ex.getMessage().contains("7 位小数"));
    }

    @Test
    void 经纬度越界与半径非法拒绝() {
        Site site = register("丽都");
        assertThrows(IllegalArgumentException.class,
                () -> geoService.locate(locateReq(site, "116.4000000", "91.0000000", null)));
        assertThrows(IllegalArgumentException.class,
                () -> geoService.locate(locateReq(site, "181.0000000", "39.9000000", null)));
        assertThrows(IllegalArgumentException.class,
                () -> geoService.nearby(new BigDecimal("116.4"), new BigDecimal("39.9"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> geoService.nearby(new BigDecimal("116.4"), new BigDecimal("39.9"), new BigDecimal("101")));
    }

    @Test
    void 底账版本登记包含f3经纬度建档版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f3-site-geo-1"));
    }
}
