package com.evops.ledger.geo.support;

import java.math.BigDecimal;

/** 两点球面距离（haversine，地球平均半径 6371.0088 km）。 */
public final class GeoDistance {

    /** 地球平均半径（公里） */
    public static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoDistance() {}

    /** 两点距离，单位米，四舍五入取整。 */
    public static int meters(BigDecimal lon1, BigDecimal lat1, BigDecimal lon2, BigDecimal lat2) {
        double phi1 = Math.toRadians(lat1.doubleValue());
        double phi2 = Math.toRadians(lat2.doubleValue());
        double dPhi = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLambda = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) Math.round(EARTH_RADIUS_KM * c * 1000);
    }
}
