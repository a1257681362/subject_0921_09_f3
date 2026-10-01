package com.evops.ledger.geo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 点位坐标档案：一个点位一份有效坐标，归属按建档时录入的主商圈走。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_site_geo")
public class SiteGeo extends BaseEntity {
    /** 点位档案 id */
    private Long siteId;
    /** 经度（十进制度，DECIMAL(10,7)，小数位不丢） */
    private BigDecimal longitude;
    /** 纬度（十进制度，DECIMAL(10,7)，小数位不丢） */
    private BigDecimal latitude;
    /** 录入时定下的主商圈：归属口径以它为准，不随后续点位档案改动漂移 */
    private String businessDistrict;
    /** 坐标来源：GPS 实测 / MANUAL 手工录入 */
    private String source;
    /** 状态：LOCATED 已定位 / VOID 已作废 */
    private String geoStatus;
    /** 当前坐标版本号，更正不抹历史、另起一版 */
    private Integer latestVersionNo;
    /** 建档经手人 */
    private String operatorName;
    /** 作废原因 */
    private String voidReason;
    /** 作废时刻 */
    private LocalDateTime voidTime;
    private String remark;
}
