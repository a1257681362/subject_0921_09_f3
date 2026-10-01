package com.evops.ledger.geo.dto;

import com.evops.ledger.entity.Site;
import com.evops.ledger.geo.entity.SiteGeo;
import com.evops.ledger.geo.entity.SiteGeoVersion;
import lombok.Data;

import java.util.List;

/** 点位坐标档案详情：档案本体 + 逐版本留痕。 */
@Data
public class GeoDetailView {
    private Site site;
    private SiteGeo geo;
    private List<SiteGeoVersion> versions;
}
