package com.evops.ledger.geo;

/** 经纬度建档口径。 */
public final class GeoEnums {
    private GeoEnums() {}

    /** 坐标档案状态：已定位 / 已作废 */
    public static final String GEO_LOCATED = "LOCATED";
    public static final String GEO_VOID = "VOID";

    /** 建档版本动作：首次定位 / 坐标更正 / 坐标作废 */
    public static final String CHANGE_LOCATE = "LOCATE";
    public static final String CHANGE_CORRECT = "CORRECT";
    public static final String CHANGE_VOID = "VOID";

    /** 坐标来源：GPS 实测 / 手工录入 */
    public static final String SOURCE_GPS = "GPS";
    public static final String SOURCE_MANUAL = "MANUAL";
}
