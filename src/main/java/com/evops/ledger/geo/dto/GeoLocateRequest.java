package com.evops.ledger.geo.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 点位经纬度建档 / 更正请求。点位已有有效坐标时按“更正”另起一版，不抹历史。 */
@Data
public class GeoLocateRequest {
    /** 点位编号（已挂牌点位才能建档坐标） */
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    /** 经度，十进制度，-180~180 */
    @NotNull(message = "经度不能为空")
    @DecimalMin(value = "-180", message = "经度不能小于 -180")
    @DecimalMax(value = "180", message = "经度不能大于 180")
    private BigDecimal longitude;
    /** 纬度，十进制度，-90~90 */
    @NotNull(message = "纬度不能为空")
    @DecimalMin(value = "-90", message = "纬度不能小于 -90")
    @DecimalMax(value = "90", message = "纬度不能大于 90")
    private BigDecimal latitude;
    /**
     * 主商圈：归属口径。留空取点位档案当前商圈；一旦建档，归属以这里录入的为准，
     * 不随后续点位档案改动漂移。更正坐标时留空表示维持原归属。
     */
    private String businessDistrict;
    /** 坐标来源：GPS（默认）/ MANUAL */
    private String source;
    /** 发生日，留空取今天 */
    private LocalDate occurDate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    /** 更正原因（首次建档可留空） */
    private String reason;
    private String remark;
}
