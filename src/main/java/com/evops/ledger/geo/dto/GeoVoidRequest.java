package com.evops.ledger.geo.dto;

import lombok.Data;

import java.time.LocalDate;

/** 坐标作废：点位撤销或坐标录错时作废，作废不删记录、留一版；作废后可重新建档。 */
@Data
public class GeoVoidRequest {
    /** 作废原因 */
    private String reason;
    /** 发生日，留空取今天 */
    private LocalDate occurDate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
}
