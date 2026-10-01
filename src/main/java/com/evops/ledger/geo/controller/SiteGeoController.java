package com.evops.ledger.geo.controller;

import com.evops.common.ApiResponse;
import com.evops.ledger.geo.dto.GeoDetailView;
import com.evops.ledger.geo.dto.GeoLocateRequest;
import com.evops.ledger.geo.dto.GeoVoidRequest;
import com.evops.ledger.geo.dto.NearbyView;
import com.evops.ledger.geo.entity.SiteGeo;
import com.evops.ledger.geo.service.SiteGeoService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 经纬度建档与按半径圈选：
 * 建档（首次定位 / 更正另起版本）、作废、档案详情（版本留痕）、圈内在营点位、整页 CSV 导出。
 */
@RestController
@RequestMapping("/api/sites-geo")
public class SiteGeoController {

    private final SiteGeoService geoService;

    public SiteGeoController(SiteGeoService geoService) {
        this.geoService = geoService;
    }

    /** 建档 / 更正经纬度：没档首定位 v1；已有有效坐标则更正另起一版，不抹历史。 */
    @PostMapping
    public ApiResponse<SiteGeo> locate(@Valid @RequestBody GeoLocateRequest request) {
        return ApiResponse.ok(geoService.locate(request));
    }

    /** 坐标作废：点位撤销或坐标录错；作废留痕、不再参与圈选，作废后可重新建档。 */
    @PostMapping("/{siteNo}/void")
    public ApiResponse<SiteGeo> voidGeo(@PathVariable String siteNo, @RequestBody(required = false) GeoVoidRequest request) {
        return ApiResponse.ok(geoService.voidGeo(siteNo, request != null ? request : new GeoVoidRequest()));
    }

    /** 坐标档案详情：当前坐标与逐版本留痕。 */
    @GetMapping("/{siteNo}")
    public ApiResponse<GeoDetailView> detail(@PathVariable String siteNo) {
        return ApiResponse.ok(geoService.detail(siteNo));
    }

    /** 按半径捞在营点位：?longitude=&latitude=&radiusKm=，结果按距离升序，重叠带点位标出。 */
    @GetMapping("/nearby")
    public ApiResponse<NearbyView> nearby(@RequestParam BigDecimal longitude,
                                          @RequestParam BigDecimal latitude,
                                          @RequestParam BigDecimal radiusKm) {
        return ApiResponse.ok(geoService.nearby(longitude, latitude, radiusKm));
    }

    /** 整页导出：把这一圈的全部命中（非单页截断）导成 CSV，跑盘带出去用。 */
    @GetMapping("/nearby/export.csv")
    public ResponseEntity<byte[]> exportNearby(@RequestParam BigDecimal longitude,
                                               @RequestParam BigDecimal latitude,
                                               @RequestParam BigDecimal radiusKm)
            throws java.io.UnsupportedEncodingException {
        NearbyView view = geoService.nearby(longitude, latitude, radiusKm);
        String csv = buildCsv(view);
        // UTF-8 BOM，Excel 直接双击打开中文不乱码
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String fileName = "点位圈选_" + longitude + "," + latitude + "_r" + radiusKm + "km_" + stamp + ".csv";
        String encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(bytes);
    }

    private String buildCsv(NearbyView view) {
        StringBuilder sb = new StringBuilder();
        sb.append("#圆心经度,#圆心纬度,#半径(公里),#命中点位,#圈内主商圈数\r\n");
        sb.append(csv(view.getLongitude())).append(',')
          .append(csv(view.getLatitude())).append(',')
          .append(csv(view.getRadiusKm())).append(',')
          .append(view.getTotal()).append(',')
          .append(view.getDistrictCount()).append("\r\n");
        sb.append("距离(公里),距离(米),点位编号,尺寸,朝向,经度,纬度,归属主商圈,点位档案商圈,商圈重叠带,同圈其他主商圈,坐标版本,经手人\r\n");
        List<NearbyView.Item> items = view.getItems();
        for (NearbyView.Item it : items) {
            sb.append(csv(it.getDistanceKm())).append(',')
              .append(it.getDistanceMeters()).append(',')
              .append(csv(it.getSiteNo())).append(',')
              .append(csv(it.getSizeSpec())).append(',')
              .append(csv(it.getOrientation())).append(',')
              .append(csv(it.getLongitude())).append(',')
              .append(csv(it.getLatitude())).append(',')
              .append(csv(it.getBusinessDistrict())).append(',')
              .append(csv(it.getSiteBusinessDistrict())).append(',')
              .append(it.isOverlap() ? "是" : "否").append(',')
              .append(csv(String.join(" / ", it.getOverlapDistricts()))).append(',')
              .append("v").append(it.getVersionNo()).append(',')
              .append(csv(it.getOperatorName())).append("\r\n");
        }
        return sb.toString();
    }

    /** RFC 4180：逗号/引号/换行包进双引号，内部引号双写。 */
    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
