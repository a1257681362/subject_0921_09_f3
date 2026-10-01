package com.evops.ledger.geo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.entity.Site;
import com.evops.ledger.geo.GeoEnums;
import com.evops.ledger.geo.dto.GeoDetailView;
import com.evops.ledger.geo.dto.GeoLocateRequest;
import com.evops.ledger.geo.dto.GeoVoidRequest;
import com.evops.ledger.geo.dto.NearbyView;
import com.evops.ledger.geo.entity.SiteGeo;
import com.evops.ledger.geo.entity.SiteGeoVersion;
import com.evops.ledger.geo.mapper.SiteGeoMapper;
import com.evops.ledger.geo.mapper.SiteGeoVersionMapper;
import com.evops.ledger.geo.support.GeoDistance;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.support.Operators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 点位经纬度建档（留版本、标状态）与按半径捞在营点位。 */
@Service
public class SiteGeoService {

    /** 圈选半径上限（公里）：防止一把圈全国 */
    private static final BigDecimal MAX_RADIUS_KM = new BigDecimal("100");
    /** 坐标小数位：十进制度 7 位约到厘米，入库与回显都不丢位 */
    private static final int COORD_SCALE = 7;

    private final SiteGeoMapper geoMapper;
    private final SiteGeoVersionMapper versionMapper;
    private final SiteMapper siteMapper;

    public SiteGeoService(SiteGeoMapper geoMapper,
                          SiteGeoVersionMapper versionMapper,
                          SiteMapper siteMapper) {
        this.geoMapper = geoMapper;
        this.versionMapper = versionMapper;
        this.siteMapper = siteMapper;
    }

    /**
     * 建档 / 更正坐标：
     * 没档 → 首次定位 v1；已有有效坐标 → 更正，坐标与商圈不抹历史，另起一版；
     * 已作废 → 重新定位，档案翻回在营并续一版。归属以录入的主商圈为准。
     */
    @Transactional(rollbackFor = Exception.class)
    public SiteGeo locate(GeoLocateRequest request) {
        Site site = requireListedSite(request.getSiteNo());
        BigDecimal longitude = normalizeCoord(request.getLongitude(), "经度");
        BigDecimal latitude = normalizeCoord(request.getLatitude(), "纬度");
        String source = normalizeSource(request.getSource());
        String operator = Operators.resolve(request.getOperatorName());
        LocalDate occurDate = request.getOccurDate() != null ? request.getOccurDate() : LocalDate.now();

        SiteGeo geo = geoMapper.selectOne(new LambdaQueryWrapper<SiteGeo>()
                .eq(SiteGeo::getSiteId, site.getId()));

        if (geo == null) {
            geo = new SiteGeo();
            geo.setSiteId(site.getId());
            geo.setLongitude(longitude);
            geo.setLatitude(latitude);
            geo.setBusinessDistrict(resolveDistrict(request.getBusinessDistrict(), site.getBusinessDistrict()));
            geo.setSource(source);
            geo.setGeoStatus(GeoEnums.GEO_LOCATED);
            geo.setLatestVersionNo(1);
            geo.setOperatorName(operator);
            geo.setRemark(request.getRemark());
            geoMapper.insert(geo);
            appendVersion(geo, 1, GeoEnums.CHANGE_LOCATE, longitude, latitude,
                    geo.getBusinessDistrict(), source, operator, occurDate, request.getReason());
            return geo;
        }

        int nextVersion = geo.getLatestVersionNo() + 1;
        if (GeoEnums.GEO_VOID.equals(geo.getGeoStatus())) {
            // 作废后重新建档：归属重新按本次录入（留空取点位档案当前商圈）定
            geo.setBusinessDistrict(resolveDistrict(request.getBusinessDistrict(), site.getBusinessDistrict()));
            geo.setLongitude(longitude);
            geo.setLatitude(latitude);
            geo.setSource(source);
            geo.setGeoStatus(GeoEnums.GEO_LOCATED);
            geo.setLatestVersionNo(nextVersion);
            geo.setOperatorName(operator);
            geo.setVoidReason(null);
            geo.setVoidTime(null);
            if (request.getRemark() != null) {
                geo.setRemark(request.getRemark());
            }
            geoMapper.updateById(geo);
            appendVersion(geo, nextVersion, GeoEnums.CHANGE_LOCATE, longitude, latitude,
                    geo.getBusinessDistrict(), source, operator, occurDate, request.getReason());
            return geo;
        }

        // 坐标更正：不抹历史，另起一版；主商圈留空表示维持原归属，归属仍按建档时那版走
        String district = request.getBusinessDistrict() != null && !request.getBusinessDistrict().trim().isEmpty()
                ? request.getBusinessDistrict().trim() : geo.getBusinessDistrict();
        String changeType;
        String reason;
        if (longitude.compareTo(geo.getLongitude()) == 0
                && latitude.compareTo(geo.getLatitude()) == 0
                && district.equals(geo.getBusinessDistrict())) {
            throw new BusinessException("坐标与归属和当前有效版本一致，无需另起更正版本");
        }
        changeType = GeoEnums.CHANGE_CORRECT;
        reason = request.getReason();
        geo.setLongitude(longitude);
        geo.setLatitude(latitude);
        geo.setBusinessDistrict(district);
        geo.setSource(source);
        geo.setLatestVersionNo(nextVersion);
        geo.setOperatorName(operator);
        if (request.getRemark() != null) {
            geo.setRemark(request.getRemark());
        }
        geoMapper.updateById(geo);
        appendVersion(geo, nextVersion, changeType, longitude, latitude,
                district, source, operator, occurDate, reason);
        return geo;
    }

    /** 坐标作废：点位撤销或坐标录错；作废不删记录、留一版，作废后该点位不再被圈选捞到。 */
    @Transactional(rollbackFor = Exception.class)
    public SiteGeo voidGeo(String siteNo, GeoVoidRequest request) {
        Site site = requireListedSite(siteNo);
        SiteGeo geo = geoMapper.selectOne(new LambdaQueryWrapper<SiteGeo>()
                .eq(SiteGeo::getSiteId, site.getId()));
        if (geo == null) {
            throw new BusinessException("点位 " + siteNo + " 还没有坐标档案，无档可作废");
        }
        if (GeoEnums.GEO_VOID.equals(geo.getGeoStatus())) {
            throw new BusinessException("点位 " + siteNo + " 的坐标档案已是作废状态，不能重复作废");
        }
        String operator = Operators.resolve(request.getOperatorName());
        LocalDate occurDate = request.getOccurDate() != null ? request.getOccurDate() : LocalDate.now();
        int nextVersion = geo.getLatestVersionNo() + 1;
        geo.setGeoStatus(GeoEnums.GEO_VOID);
        geo.setVoidReason(request.getReason());
        geo.setVoidTime(LocalDateTime.now());
        geo.setLatestVersionNo(nextVersion);
        geo.setOperatorName(operator);
        geoMapper.updateById(geo);
        appendVersion(geo, nextVersion, GeoEnums.CHANGE_VOID, geo.getLongitude(), geo.getLatitude(),
                geo.getBusinessDistrict(), geo.getSource(), operator, occurDate, request.getReason());
        return geo;
    }

    /** 点位坐标档案详情（含逐版本留痕）。 */
    public GeoDetailView detail(String siteNo) {
        Site site = requireListedSite(siteNo);
        SiteGeo geo = geoMapper.selectOne(new LambdaQueryWrapper<SiteGeo>()
                .eq(SiteGeo::getSiteId, site.getId()));
        if (geo == null) {
            throw new BusinessException("点位 " + siteNo + " 还没有经纬度档案");
        }
        List<SiteGeoVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<SiteGeoVersion>()
                .eq(SiteGeoVersion::getGeoId, geo.getId())
                .orderByAsc(SiteGeoVersion::getVersionNo));
        GeoDetailView view = new GeoDetailView();
        view.setSite(site);
        view.setGeo(geo);
        view.setVersions(versions);
        return view;
    }

    /**
     * 按半径捞点位：给坐标、给公里数，圈内“在营点位”全列出。
     * 在营 = 点位挂牌在营（LISTED）且坐标已定位（LOCATED，未作废）。
     * 结果按距离升序；一圈圈进多个主商圈时，命中点位标在商圈重叠带。
     */
    public NearbyView nearby(BigDecimal rawLon, BigDecimal rawLat, BigDecimal rawRadiusKm) {
        if (rawLon == null || rawLat == null || rawRadiusKm == null) {
            throw new IllegalArgumentException("圆心经纬度和半径（公里）都必填");
        }
        BigDecimal longitude = normalizeCoord(rawLon, "经度");
        BigDecimal latitude = normalizeCoord(rawLat, "纬度");
        BigDecimal radiusKm = normalizeRadius(rawRadiusKm);
        int radiusMeters = radiusKm.multiply(new BigDecimal("1000")).setScale(0, RoundingMode.HALF_UP).intValueExact();

        // 在营点位档案
        List<Site> listedSites = siteMapper.selectList(new LambdaQueryWrapper<Site>()
                .eq(Site::getStatus, com.evops.ledger.LedgerEnums.SITE_LISTED));
        if (listedSites.isEmpty()) {
            return emptyView(longitude, latitude, radiusKm);
        }
        Map<Long, Site> siteMap = new HashMap<>();
        for (Site s : listedSites) {
            siteMap.put(s.getId(), s);
        }

        // 在营坐标档案（已定位未作废）
        List<SiteGeo> geos = geoMapper.selectList(new LambdaQueryWrapper<SiteGeo>()
                .eq(SiteGeo::getGeoStatus, GeoEnums.GEO_LOCATED));

        List<NearbyView.Item> items = new ArrayList<>();
        for (SiteGeo geo : geos) {
            Site site = siteMap.get(geo.getSiteId());
            if (site == null) {
                continue; // 点位已不在营（档案撤销等），坐标不参与圈选
            }
            int meters = GeoDistance.meters(longitude, latitude, geo.getLongitude(), geo.getLatitude());
            if (meters > radiusMeters) {
                continue;
            }
            NearbyView.Item item = new NearbyView.Item();
            item.setGeoId(geo.getId());
            item.setSiteId(site.getId());
            item.setSiteNo(site.getSiteNo());
            item.setSizeSpec(site.getSizeSpec());
            item.setOrientation(site.getOrientation());
            item.setLongitude(geo.getLongitude());
            item.setLatitude(geo.getLatitude());
            item.setBusinessDistrict(geo.getBusinessDistrict());
            item.setSiteBusinessDistrict(site.getBusinessDistrict());
            item.setDistanceMeters(meters);
            item.setDistanceKm(new BigDecimal(meters).movePointLeft(3).setScale(3, RoundingMode.HALF_UP));
            item.setVersionNo(geo.getLatestVersionNo());
            item.setOperatorName(geo.getOperatorName());
            items.add(item);
        }
        items.sort((a, b) -> Integer.compare(a.getDistanceMeters(), b.getDistanceMeters()));

        // 重叠带：这一圈里圈进了不止一个主商圈
        Set<String> districts = items.stream()
                .map(NearbyView.Item::getBusinessDistrict)
                .collect(Collectors.toCollection(HashSet::new));
        boolean overlapZone = districts.size() > 1;
        for (NearbyView.Item item : items) {
            item.setOverlap(overlapZone);
            if (overlapZone) {
                List<String> others = new ArrayList<>(districts);
                others.remove(item.getBusinessDistrict());
                Collections.sort(others);
                item.setOverlapDistricts(others);
            } else {
                item.setOverlapDistricts(Collections.emptyList());
            }
        }

        NearbyView view = new NearbyView();
        view.setLongitude(longitude);
        view.setLatitude(latitude);
        view.setRadiusKm(radiusKm);
        view.setTotal(items.size());
        view.setDistrictCount(districts.size());
        view.setItems(items);
        return view;
    }

    private NearbyView emptyView(BigDecimal longitude, BigDecimal latitude, BigDecimal radiusKm) {
        NearbyView view = new NearbyView();
        view.setLongitude(longitude);
        view.setLatitude(latitude);
        view.setRadiusKm(radiusKm);
        view.setTotal(0);
        view.setDistrictCount(0);
        view.setItems(Collections.emptyList());
        return view;
    }

    private Site requireListedSite(String siteNo) {
        if (siteNo == null || siteNo.trim().isEmpty()) {
            throw new BusinessException("点位编号不能为空");
        }
        Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>()
                .eq(Site::getSiteNo, siteNo.trim()));
        if (site == null) {
            throw new BusinessException("点位不存在: " + siteNo.trim());
        }
        if (!com.evops.ledger.LedgerEnums.SITE_LISTED.equals(site.getStatus())) {
            throw new BusinessException("点位 " + site.getSiteNo() + " 不在营，不能登记坐标");
        }
        return site;
    }

    private void appendVersion(SiteGeo geo, int versionNo, String changeType,
                               BigDecimal longitude, BigDecimal latitude, String district,
                               String source, String operator, LocalDate occurDate, String reason) {
        SiteGeoVersion version = new SiteGeoVersion();
        version.setGeoId(geo.getId());
        version.setSiteId(geo.getSiteId());
        version.setVersionNo(versionNo);
        version.setChangeType(changeType);
        version.setLongitude(longitude);
        version.setLatitude(latitude);
        version.setBusinessDistrict(district);
        version.setSource(source);
        version.setOperatorName(operator);
        version.setOccurDate(occurDate);
        version.setReason(reason);
        versionMapper.insert(version);
    }

    /** 坐标按 7 位小数收口：超 7 位直接拒（不悄悄截位丢精度），不足 7 位补齐，入库不丢小数位。 */
    private BigDecimal normalizeCoord(BigDecimal value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        boolean longitude = "经度".equals(label);
        BigDecimal bound = longitude ? new BigDecimal("180") : new BigDecimal("90");
        if (value.abs().compareTo(bound) > 0) {
            throw new IllegalArgumentException(label + "超出十进制度范围（" + (longitude ? "-180~180" : "-90~90") + "）");
        }
        if (value.scale() > COORD_SCALE) {
            throw new IllegalArgumentException(label + " 最多保留 " + COORD_SCALE + " 位小数，多出的小数位无法原样入库");
        }
        return value.setScale(COORD_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal normalizeRadius(BigDecimal radius) {
        if (radius.signum() <= 0) {
            throw new IllegalArgumentException("圈选半径必须大于 0 公里");
        }
        if (radius.compareTo(MAX_RADIUS_KM) > 0) {
            throw new IllegalArgumentException("圈选半径最大 " + MAX_RADIUS_KM + " 公里");
        }
        return radius.setScale(3, RoundingMode.HALF_UP);
    }

    private String normalizeSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            return GeoEnums.SOURCE_GPS;
        }
        String s = source.trim().toUpperCase();
        if (!GeoEnums.SOURCE_GPS.equals(s) && !GeoEnums.SOURCE_MANUAL.equals(s)) {
            throw new IllegalArgumentException("坐标来源只支持 GPS / MANUAL");
        }
        return s;
    }

    private String resolveDistrict(String explicit, String fallback) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }
        return fallback;
    }
}
