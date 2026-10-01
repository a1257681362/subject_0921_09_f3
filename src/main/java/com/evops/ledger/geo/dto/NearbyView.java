package com.evops.ledger.geo.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 半径圈选结果：圆心、公里数、命中明细（已按距离升序）。 */
@Data
public class NearbyView {
    private BigDecimal longitude;
    private BigDecimal latitude;
    private BigDecimal radiusKm;
    /** 命中点位总数 */
    private int total;
    /** 圈内涉及的主商圈个数；大于 1 说明这一圈压在商圈重叠带 */
    private int districtCount;
    private List<Item> items;

    /** 一个命中点位：归属按建档时录入的主商圈走，距离相对圆心。 */
    @Data
    public static class Item {
        private Long geoId;
        private Long siteId;
        private String siteNo;
        private String sizeSpec;
        private String orientation;
        /** 坐标（十进制度，原样返回 7 位小数） */
        private BigDecimal longitude;
        private BigDecimal latitude;
        /** 归属主商圈：建档时录入，不随点位档案改动漂移 */
        private String businessDistrict;
        /** 点位档案当前商圈（仅供对照，归属不看它） */
        private String siteBusinessDistrict;
        /** 距圆心（米） */
        private int distanceMeters;
        /** 距圆心（公里，保留 3 位小数） */
        private BigDecimal distanceKm;
        /** 坐标当前版本号 */
        private Integer versionNo;
        /** 是否落在商圈重叠带：圈内还有别的主商圈的点位 */
        private boolean overlap;
        /** 重叠带里同时圈进来的其他主商圈 */
        private List<String> overlapDistricts;
        private String operatorName;
    }
}
