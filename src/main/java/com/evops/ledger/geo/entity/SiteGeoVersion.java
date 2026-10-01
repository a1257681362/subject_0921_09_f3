package com.evops.ledger.geo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 经纬度建档版本：只追加；首次定位 / 坐标更正 / 坐标作废各留一版。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_site_geo_version")
public class SiteGeoVersion extends BaseEntity {
    /** 坐标档案 id */
    private Long geoId;
    /** 点位档案 id */
    private Long siteId;
    /** 版本号，从 1 起，单调递增 */
    private Integer versionNo;
    /** 动作：LOCATE 首次定位 / CORRECT 坐标更正 / VOID 坐标作废 */
    private String changeType;
    /** 该版经度 */
    private BigDecimal longitude;
    /** 该版纬度 */
    private BigDecimal latitude;
    /** 该版记录的主商圈（归属口径） */
    private String businessDistrict;
    /** 坐标来源 */
    private String source;
    /** 经手人 */
    private String operatorName;
    /** 发生日 */
    private LocalDate occurDate;
    /** 更正 / 作废原因 */
    private String reason;
}
