/**
 *  Tracer - plugin for JOSM
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along
 *  with this program; if not, write to the Free Software Foundation, Inc.,
 *  51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.openstreetmap.josm.plugins.tracer.modules.zabaged;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.tracer.TracerRecord;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

/**
 * Holds all features returned by a single ZABAGED identify query.
 * Extends TracerRecord so it fits into the AbstractTracerTask pipeline.
 *
 * Only one feature is "active" at a time (the one being traced).
 * Call activateFeature(f) to switch the active geometry.
 */
public final class ZabagedRecord extends TracerRecord {

    private List<ZabagedFeature> m_features;
    private ZabagedFeature m_activeFeature;

    // -----------------------------------------------------------------------
    // Attribute-mapping tables
    // -----------------------------------------------------------------------

    /**
     * Global numeric attributes: ZABAGED key → OSM key.
     * Applied to every feature regardless of layer.
     * Values are normalised to decimal notation (dot separator) before storage.
     */
    private static final Map<String, String> GLOBAL_NUMERIC_ATTRS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("vyska",           "height");   // výška nad terénem [m]
        m.put("nadmorska_vyska", "ele");      // nadmořská výška [m n.m.]
        GLOBAL_NUMERIC_ATTRS = Collections.unmodifiableMap(m);
    }

    public ZabagedRecord() {
        super(0.0, 0.0);
        m_features = new ArrayList<>();
        m_activeFeature = null;
    }

    // -------------------------------------------------------------------------
    // TracerRecord contract
    // -------------------------------------------------------------------------

    @Override
    public boolean hasData() {
        return !m_features.isEmpty();
    }

    @Override
    public Map<String, String> getKeys(boolean alt) {
        if (m_activeFeature == null)
            return Collections.emptyMap();
        return buildOsmTags(m_activeFeature);
    }

    // -------------------------------------------------------------------------
    // Feature list access
    // -------------------------------------------------------------------------

    public List<ZabagedFeature> getFeatures() {
        return Collections.unmodifiableList(m_features);
    }

    public ZabagedFeature getActiveFeature() {
        return m_activeFeature;
    }

    /**
     * Make f the active feature. Resets outer/inner geometry via super.init(),
     * then loads f's geometry so that TracerRecord.createObject() / getBBox() work.
     */
    public void activateFeature(ZabagedFeature f) {
        m_activeFeature = f;
        super.init();

        if (f == null || !f.isPolygon())
            return;

        List<List<LatLon>> rings = f.getRings();
        if (rings.isEmpty())
            return;

        try {
            setOuter(rings.get(0));
        } catch (IllegalStateException e) {
            // degenerate ring — skip
            System.out.println("ZabagedRecord: degenerate outer ring, skipping: " + e.getMessage());
            super.init();
            return;
        }

        for (int i = 1; i < rings.size(); i++) {
            try {
                addInner(rings.get(i));
            } catch (IllegalStateException e) {
                System.out.println("ZabagedRecord: degenerate inner ring [" + i + "], skipping: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    /**
     * Parse an ArcGIS identify JSON response.
     * Populates m_features and pre-activates the first polygon feature (if any)
     * so that getMissingAreaToDownload() has geometry to work with.
     */
    public void parseJSON(String jsonStr) {
        m_features = new ArrayList<>();
        m_activeFeature = null;
        super.init();

        JsonObject root;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(jsonStr.getBytes("UTF-8")))) {
            root = reader.readObject();
        } catch (Exception e) {
            System.out.println("ZabagedRecord: JSON parse error: " + e.getMessage());
            return;
        }

        JsonArray results = retrieveJsonArray(root, "results");
        if (results == null)
            return;

        for (JsonValue rv : results) {
            if (rv.getValueType() != JsonValue.ValueType.OBJECT)
                continue;
            JsonObject r = (JsonObject) rv;

            int layerId = r.containsKey("layerId") ? r.getInt("layerId", -1) : -1;
            String layerName = parseJsonString(r, "layerName", "");
            String geomTypeStr = parseJsonString(r, "geometryType", "");

            // parse attributes
            Map<String, String> attrs = new HashMap<>();
            JsonObject attrsObj = retrieveJsonObject(r, "attributes");
            if (attrsObj != null) {
                for (Map.Entry<String, JsonValue> entry : attrsObj.entrySet()) {
                    JsonValue v = entry.getValue();
                    String sv;
                    if (v.getValueType() == JsonValue.ValueType.STRING) {
                        sv = ((jakarta.json.JsonString) v).getString();
                    } else if (v.getValueType() == JsonValue.ValueType.NULL) {
                        sv = "";
                    } else {
                        sv = v.toString();
                    }
                    attrs.put(entry.getKey(), sv);
                }
            }

            JsonObject geom = retrieveJsonObject(r, "geometry");
            if (geom == null)
                continue;

            ZabagedFeature feature = null;

            if ("esriGeometryPolygon".equals(geomTypeStr)) {
                List<List<LatLon>> rings = parseRingsOrPaths(geom, "rings");
                if (!rings.isEmpty())
                    feature = ZabagedFeature.polygon(layerId, layerName, attrs, rings);

            } else if ("esriGeometryPolyline".equals(geomTypeStr)) {
                List<List<LatLon>> paths = parseRingsOrPaths(geom, "paths");
                if (!paths.isEmpty())
                    feature = ZabagedFeature.polyline(layerId, layerName, attrs, paths);

            } else if ("esriGeometryPoint".equals(geomTypeStr)) {
                double lon = getJsonDouble(geom, "x", Double.NaN);
                double lat = getJsonDouble(geom, "y", Double.NaN);
                if (!Double.isNaN(lon) && !Double.isNaN(lat))
                    feature = ZabagedFeature.point(layerId, layerName, attrs, new LatLon(lat, lon));
            }

            if (feature != null)
                m_features.add(feature);
        }

        // Pre-activate first polygon for getMissingAreaToDownload()
        for (ZabagedFeature f : m_features) {
            if (f.isPolygon()) {
                activateFeature(f);
                break;
            }
        }
        // If no polygon, just leave active feature as first feature (no geometry loaded into TracerRecord)
        if (m_activeFeature == null && !m_features.isEmpty()) {
            m_activeFeature = m_features.get(0);
        }
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /** Parse "rings" or "paths" array from ArcGIS geometry object. Coords are [lon, lat]. */
    private static List<List<LatLon>> parseRingsOrPaths(JsonObject geom, String key) {
        List<List<LatLon>> result = new ArrayList<>();
        JsonArray outer = retrieveJsonArray(geom, key);
        if (outer == null)
            return result;

        for (JsonValue ringVal : outer) {
            if (ringVal.getValueType() != JsonValue.ValueType.ARRAY)
                continue;
            JsonArray ring = (JsonArray) ringVal;
            List<LatLon> coords = new ArrayList<>(ring.size());
            for (JsonValue ptVal : ring) {
                if (ptVal.getValueType() != JsonValue.ValueType.ARRAY)
                    continue;
                JsonArray pt = (JsonArray) ptVal;
                if (pt.size() < 2)
                    continue;
                double lon = getArrayDouble(pt, 0);
                double lat = getArrayDouble(pt, 1);
                if (!Double.isNaN(lon) && !Double.isNaN(lat))
                    coords.add(new LatLon(lat, lon));
            }
            if (!coords.isEmpty())
                result.add(coords);
        }
        return result;
    }

    private static double getJsonDouble(JsonObject obj, String key, double dflt) {
        JsonValue v = obj.get(key);
        if (v == null) return dflt;
        if (v.getValueType() == JsonValue.ValueType.NUMBER)
            return ((JsonNumber) v).doubleValue();
        return dflt;
    }

    private static double getArrayDouble(JsonArray arr, int index) {
        JsonValue v = arr.get(index);
        if (v == null) return Double.NaN;
        if (v.getValueType() == JsonValue.ValueType.NUMBER)
            return ((JsonNumber) v).doubleValue();
        return Double.NaN;
    }

    // -------------------------------------------------------------------------
    // OSM tag mapping — all ~150 ZABAGED layers
    // -------------------------------------------------------------------------

    private static Map<String, String> buildOsmTags(ZabagedFeature f) {
        Map<String, String> tags = new HashMap<>();
        int id = f.getLayerId();

        boolean mapped = applyLayerTags(id, f.getLayerName(), tags);
        if (!mapped) {
            tags.put("note:zabaged", f.getLayerName());
        }

        applyAttributeTags(f, tags);

        tags.put("source", "cuzk:zabaged");
        return tags;
    }

    /**
     * Apply attribute-level tags on top of the layer-level tags.
     *
     * Three passes, in order:
     *   1. Global name  (jmeno / nazev → name)
     *   2. Global numeric passthrough  (GLOBAL_NUMERIC_ATTRS table)
     *   3. Per-layer complex attributes  (applyLayerSpecificAttrs)
     */
    private static void applyAttributeTags(ZabagedFeature f, Map<String, String> tags) {
        Map<String, String> attrs = f.getAttributes();

        // 1. Name
        applyNameAttr(attrs, tags);

        // 2. Global numeric passthrough
        for (Map.Entry<String, String> e : GLOBAL_NUMERIC_ATTRS.entrySet()) {
            String val = normalizeNumericAttr(getCleanAttr(attrs, e.getKey()));
            if (val != null)
                tags.put(e.getValue(), val);
        }

        // 3. Per-layer attributes
        applyLayerSpecificAttrs(f, attrs, tags);
    }

    // --- name helper --------------------------------------------------------

    private static void applyNameAttr(Map<String, String> attrs, Map<String, String> tags) {
        String name = getCleanAttr(attrs, "jmeno");
        if (name == null)
            name = getCleanAttr(attrs, "nazev");
        if (name != null)
            tags.put("name", name);
    }

    // --- per-layer dispatcher -----------------------------------------------

    /**
     * Dispatches to a layer-specific attribute handler.
     * Groups of layers that share the same attribute schema are handled together.
     */
    private static void applyLayerSpecificAttrs(ZabagedFeature f,
            Map<String, String> attrs, Map<String, String> tags) {
        int id = f.getLayerId();

        // Buildings: layers 23, 99–109
        if (id == 23 || (id >= 99 && id <= 109)) {
            applyBuildingAttrs(attrs, tags);
            // druhbud refines building=* only for the "generic building" layers
            if (id == 99 || id == 23)
                applyDruhbud(getCleanAttr(attrs, "druhbud"), tags);
            return;
        }

        switch (id) {
            // Roads
            case 79: case 80: case 81: case 82: case 83:
            case 84: case 85: case 86: case 87:
                applyRoadAttrs(attrs, tags);
                break;

            // Railways (track layers only — not stations / halts)
            case 70: case 71: case 74: case 75: case 76:
                applyRailwayAttrs(attrs, tags);
                break;

            // Waterways
            case 93: case 94: case 95: case 96:
                applyWaterwayAttrs(attrs, tags);
                break;

            // Power lines
            case 88: case 89:
                applyPowerLineAttrs(attrs, tags);
                break;

            // Bridge (man_made=bridge)
            case 73:
                applyBridgeAttrs(attrs, tags);
                break;

            case 149:
                applyGardenAttrs(attrs, tags);
                break;
        }
    }

    // --- per-layer attribute handlers ---------------------------------------

    /** Buildings (layers 23, 99–109). */
    private static void applyBuildingAttrs(Map<String, String> attrs, Map<String, String> tags) {
        // pocet_pater → building:levels  (above-ground floor count)
        String levels = normalizeIntAttr(getCleanAttr(attrs, "pocet_pater"));
        if (levels != null)
            tags.put("building:levels", levels);
    }

    /** Roads (layers 79–87). */
    private static void applyRoadAttrs(Map<String, String> attrs, Map<String, String> tags) {
        // cislo → ref  (road number)
        String cislo = getCleanAttr(attrs, "cislo");
        if (cislo != null)
            tags.put("ref", cislo);

        // pocet_pruhu → lanes
        String pruhu = normalizeIntAttr(getCleanAttr(attrs, "pocet_pruhu"));
        if (pruhu != null)
            tags.put("lanes", pruhu);

        // sirka / sirka_vozovky → width [m]
        String sirka = normalizeNumericAttr(getCleanAttr(attrs, "sirka_vozovky"));
        if (sirka == null)
            sirka = normalizeNumericAttr(getCleanAttr(attrs, "sirka"));
        if (sirka != null)
            tags.put("width", sirka);

        // povrch → surface  (with Czech value mapping)
        String osmSurface = mapPovrch(getCleanAttr(attrs, "povrch"));
        if (osmSurface != null)
            tags.put("surface", osmSurface);
    }

    private static String mapPovrch(String povrch) {
        if (povrch == null) return null;
        switch (povrch.toLowerCase(Locale.ROOT)) {
            case "asfalt":              return "asphalt";
            case "beton":               return "concrete";
            case "dlažba":              return "sett";
            case "žulová dlažba":       return "cobblestone";
            case "štěrk":               return "gravel";
            case "makadam":             return "compacted";
            case "zemina":              return "dirt";
            case "tráva":               return "grass";
            case "dřevo":               return "wood";
            case "kov":                 return "metal";
            default:                    return null;
        }
    }

    /** Railways — track layers (70, 71, 74–76). */
    private static void applyRailwayAttrs(Map<String, String> attrs, Map<String, String> tags) {
        // rozchod → gauge [mm]
        String rozchod = normalizeIntAttr(getCleanAttr(attrs, "rozchod"));
        if (rozchod != null)
            tags.put("gauge", rozchod);

        // pocet_kolej → tracks
        String koleje = normalizeIntAttr(getCleanAttr(attrs, "pocet_kolej"));
        if (koleje != null)
            tags.put("tracks", koleje);

        // elektrifikace → electrified  (with Czech value mapping)
        String elekt = mapElektrifikace(getCleanAttr(attrs, "elektrifikace"));
        if (elekt != null)
            tags.put("electrified", elekt);
    }

    private static String mapElektrifikace(String val) {
        if (val == null) return null;
        switch (val.toLowerCase(Locale.ROOT)) {
            case "ano": case "yes": case "1":   return "yes";
            case "ne":  case "no":  case "0":   return "no";
            case "trolej":                      return "contact_line";
            case "třetí kolejnice":             return "rail";
            default:                            return null;
        }
    }

    /** Waterways (layers 93–96). */
    private static void applyWaterwayAttrs(Map<String, String> attrs, Map<String, String> tags) {
        // sirka → width [m]
        String sirka = normalizeNumericAttr(getCleanAttr(attrs, "sirka"));
        if (sirka != null)
            tags.put("width", sirka);
    }

    /** Power lines (layers 88–89). */
    private static void applyPowerLineAttrs(Map<String, String> attrs, Map<String, String> tags) {
        // napeti → voltage [V]
        String napeti = normalizeIntAttr(getCleanAttr(attrs, "napeti"));
        if (napeti != null)
            tags.put("voltage", napeti);

        // pocet_vodicovych_lan / pocet_lan → cables
        String kabely = normalizeIntAttr(getCleanAttr(attrs, "pocet_vodicovych_lan"));
        if (kabely == null)
            kabely = normalizeIntAttr(getCleanAttr(attrs, "pocet_lan"));
        if (kabely != null)
            tags.put("cables", kabely);
    }

    /** Bridge (layer 73). */
    private static void applyBridgeAttrs(Map<String, String> attrs, Map<String, String> tags) {
        // nosnost → maxweight [t]
        String nosnost = normalizeNumericAttr(getCleanAttr(attrs, "nosnost"));
        if (nosnost != null)
            tags.put("maxweight", nosnost);

        // sirka → width [m]
        String sirka = normalizeNumericAttr(getCleanAttr(attrs, "sirka"));
        if (sirka != null)
            tags.put("width", sirka);

        // delka → length [m]
        String delka = normalizeNumericAttr(getCleanAttr(attrs, "delka"));
        if (delka != null)
            tags.put("length", delka);
    }

    private static void applyGardenAttrs(Map<String, String> attrs,
            Map<String, String> tags) {
        String typ = getCleanAttr(attrs, "TYP_PUDY_K"); // ← ověř přesný název atributu
        if (typ == null) return;
        switch (typ.toUpperCase(Locale.ROOT)) {
            case "OS":   // ovocný sad
                tags.put("landuse", "orchard");
                tags.remove("leisure");
                break;
            case "ZA":   // zahrada
                // leisure=garden už tam je, necháme
                break;
            case "OTK":  // ostatní trvalá kultura
                tags.put("natural", "scrub");
                tags.remove("leisure");
                break;
        }
    }

    // --- attribute utilities ------------------------------------------------

    /**
     * Returns a trimmed, non-empty attribute value, or {@code null} if the key
     * is absent, blank, or the literal string {@code "null"} (as returned by
     * some ArcGIS endpoints for missing values).
     */
    private static String getCleanAttr(Map<String, String> attrs, String key) {
        String val = attrs.get(key);
        if (val == null) return null;
        val = val.trim();
        if (val.isEmpty() || val.equalsIgnoreCase("null")) return null;
        return val;
    }

    /**
     * Parses a numeric attribute string (accepts both {@code .} and {@code ,}
     * as decimal separator).  Returns a normalised decimal string (dot
     * separator, no unnecessary trailing zeros), or {@code null} if the value
     * cannot be parsed or is not a finite non-negative number.
     */
    private static String normalizeNumericAttr(String val) {
        if (val == null) return null;
        try {
            double d = Double.parseDouble(val.replace(',', '.'));
            if (!Double.isFinite(d) || d < 0) return null;
            // Whole numbers without decimal point; fractional values with up to
            // two significant decimal places (strips trailing zeros).
            if (d == Math.floor(d))
                return Long.toString((long) d);
            return String.format(Locale.ROOT, "%.2f", d).replaceAll("\\.?0+$", "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Like {@link #normalizeNumericAttr} but rounds to the nearest integer and
     * returns {@code null} for values ≤ 0 (invalid counts / dimensions).
     */
    private static String normalizeIntAttr(String val) {
        if (val == null) return null;
        try {
            long l = Math.round(Double.parseDouble(val.replace(',', '.')));
            return l > 0 ? Long.toString(l) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Maps ZABAGED druhbud attribute values to OSM building tags.
     * Overrides the default building=yes set by applyLayerTags(99, ...).
     */
    private static void applyDruhbud(String druhbud, Map<String, String> tags) {
        if (druhbud == null) return;
        switch (druhbud.toLowerCase(Locale.ROOT)) {

            // --- Industry ---
            case "strojírenský průmysl":
                tags.put("building", "industrial");
                tags.put("industrial", "machine_shop");
                break;
            case "chemický průmysl":
                tags.put("building", "industrial");
                tags.put("industrial", "chemical");
                break;
            case "textilní, oděvní a kožedělný průmysl":
                tags.put("building", "industrial");
                tags.put("industrial", "textile");
                break;
            case "průmysl skla, keramiky a stavebních hmot":
                tags.put("building", "industrial");
                tags.put("industrial", "glass");
                break;
            case "potravinářský průmysl":
                tags.put("building", "industrial");
                tags.put("industrial", "food");
                break;
            case "dřevozpracující a papírenský průmysl":
                tags.put("building", "industrial");
                tags.put("industrial", "wood");
                break;
            case "polygrafický průmysl":
                tags.put("building", "industrial");
                tags.put("industrial", "printing");
                break;
            case "hutnický průmysl":
                tags.put("building", "industrial");
                tags.put("industrial", "metallurgical");
                break;
            case "ostatní, nerozlišený průmysl":
                tags.put("building", "industrial");
                break;

            // --- Agriculture ---
            case "chov hospodářských zvířat":
                tags.put("building", "farm_auxiliary");
                tags.put("landuse", "animal_keeping");
                break;
            case "zemědělský podnik ostatní":
                tags.put("building", "farm_auxiliary");
                break;

            // --- Infrastructure ---
            case "přečerpávací stanice produktovodu":
                tags.put("building", "industrial");
                tags.put("man_made", "pumping_station");
                break;
            case "rozvodna, transformovna":
                tags.put("building", "service");
                tags.put("power", "substation");
                break;
            case "vodojem zemní":
                tags.put("building", "water_works");
                tags.put("man_made", "water_works");
                break;
            case "meteorologická stanice":
                tags.put("building", "yes");
                tags.put("man_made", "monitoring_station");
                tags.put("monitoring:weather", "yes");
                break;

            // --- Culture / science ---
            case "hvězdárna":
                tags.put("building", "observatory");
                tags.put("amenity", "observatory");
                break;
            case "muzeum":
                tags.put("building", "civic");
                tags.put("tourism", "museum");
                break;
            case "divadlo":
                tags.put("building", "civic");
                tags.put("amenity", "theatre");
                break;
            case "kulturní objekt ostatní":
                tags.put("building", "civic");
                tags.put("amenity", "arts_centre");
                break;
            case "zábavní park":
                tags.put("building", "commercial");
                tags.put("tourism", "theme_park");
                break;

            // --- Religion ---
            case "kostel":
                tags.put("building", "church");
                tags.put("amenity", "place_of_worship");
                tags.put("religion", "christian");
                break;
            case "kaple":
                tags.put("building", "chapel");
                tags.put("amenity", "place_of_worship");
                break;
            case "klášter":
                tags.put("building", "monastery");
                tags.put("amenity", "monastery");
                break;
            case "synagoga":
                tags.put("building", "synagogue");
                tags.put("amenity", "place_of_worship");
                tags.put("religion", "jewish");
                break;

            // --- Education / health / social ---
            case "škola":
                tags.put("building", "school");
                tags.put("amenity", "school");
                break;
            case "školské zařízení":
                tags.put("building", "school");
                break;
            case "nemocnice":
                tags.put("building", "hospital");
                tags.put("amenity", "hospital");
                break;
            case "další zdravotní a sociální zařízení":
                tags.put("building", "clinic");
                tags.put("amenity", "clinic");
                break;

            // --- Sport ---
            case "sportovní hala":
                tags.put("building", "sports_hall");
                tags.put("leisure", "sports_centre");
                break;
            case "krytý bazén":
                tags.put("building", "sports_centre");
                tags.put("leisure", "swimming_pool");
                tags.put("location", "indoor");
                break;

            // --- Civic / public ---
            case "správní a soudní budova":
                tags.put("building", "civic");
                tags.put("office", "government");
                break;
            case "věznice":
                tags.put("building", "civic");
                tags.put("amenity", "prison");
                break;
            case "kasárny a vojenské objekty":
                tags.put("building", "barracks");
                tags.put("military", "barracks");
                break;
            case "policejní služebna":
                tags.put("building", "civic");
                tags.put("amenity", "police");
                break;
            case "hasičská stanice, zbrojnice":
                tags.put("building", "civic");
                tags.put("amenity", "fire_station");
                break;
            case "cizí zastupitelský úřad":
                tags.put("building", "civic");
                tags.put("office", "diplomatic");
                tags.put("diplomatic", "embassy");
                break;
            case "pošta":
                tags.put("building", "civic");
                tags.put("amenity", "post_office");
                break;

            // --- Transport / parking ---
            case "garážový dům":
                tags.put("building", "parking");
                tags.put("amenity", "parking");
                tags.put("parking", "multi-storey");
                break;
            case "hangár, sklad":
                tags.put("building", "warehouse");
                break;

            // --- Retail ---
            case "čerpací stanice pohonných hmot":
                tags.put("building", "retail");
                tags.put("amenity", "fuel");
                break;
            case "obchodní středisko s potravinami":
                tags.put("building", "supermarket");
                tags.put("shop", "supermarket");
                break;
            case "obchodní středisko bez potravin":
                tags.put("building", "retail");
                tags.put("shop", "mall");
                break;

            // --- Fallback ---
            case "budova blíže neurčená":
            default:
                tags.put("building", "yes");
                break;
        }
    }

    /**
     * Returns true if the layer was mapped to known OSM tags.
     * Layer IDs and names from the ČÚZK ZABAGED MapServer JSON (2024).
     */
    private static boolean applyLayerTags(int id, String layerName, Map<String, String> tags) {
        switch (id) {

            // ---- Administrative / boundary ----
            case 1:
                tags.put("boundary", "administrative");
                return true;
            case 2:
                tags.put("boundary", "administrative");
                tags.put("admin_level", "2");
                return true;
            case 3:
                tags.put("place", "*");
                return true;
            case 4:
                tags.put("boundary", "protected_area");
                return true;
            case 5:
                tags.put("boundary", "protected_area");
                return true;
            case 6:
                tags.put("man_made", "survey_point");
                return true;
            case 7:
                tags.put("man_made", "survey_point");
                tags.put("survey_point:purpose ", "vertical");
                return true;
            case 8:
                tags.put("man_made", "survey_point");
                tags.put("survey_point:purpose", "gravity");
                return true;

            // ---- Water bodies ----
            case 9:
                tags.put("ele", "*");
                tags.put("natural", "*");
                return true;
            case 10:
                tags.put("natural", "stone");
                return true;
            case 11:
                tags.put("natural", "cave_entrance");
                return true;
            case 12:
                tags.put("natural", "stone");
                return true;
            case 13:
                tags.put("natural", "stone");
                return true;
            case 14:
                tags.put("natural", "tree");
                return true;
            case 15:
                tags.put("natural", "tree_row");
                return true;
            case 16:
                tags.put("man_made", "cutline");
                return true;
            case 17:
                tags.put("natural", "wetland");
                tags.put("wetland", "bog");
                return true;

            // ---- Wetlands ----
            case 18:
                tags.put("natural", "wetland");
                tags.put("wetland", "bog");
                return true;
            case 19:
                tags.put("natural", "spring");
                return true;
            case 20:
                tags.put("waterway", "waterfall");
                return true;
            case 21:
                tags.put("waterway", "waterfall");
                return true;
            case 22:
                tags.put("waterway", "dam");
                return true;
            case 23:
                tags.put("building", "yes");
                return true;
            case 24:
                tags.put("historic", "wayside_cross");
                return true;
            case 25:
                tags.put("historic", "memorial");
                return true;
            case 26:
                tags.put("man_made", "tower");
                return true;
            case 27:
                tags.put("man_made", "water_tower");
                return true;
            case 28:
                tags.put("man_made", "silo");
                return true;
            // 29 — gap in layer list, not queryable
            case 30:
                tags.put("man_made", "mineshaft");
                tags.put("mineshaft:headframe", "yes");
                return true;
            case 31:
                tags.put("man_made", "chimney");
                return true;
            case 32:
                tags.put("man_made", "windmill");
                return true;
            case 33:
                tags.put("man_made", "windpump");
                return true;
            case 34:
                tags.put("man_made", "mine_shaft");
                return true;
            case 35:
                tags.put("amenity", "fuel");
                return true;
            case 36:
                tags.put("man_made", "weather_station");
                return true;

            // ---- Barriers / fences / walls ----
            case 37:
                tags.put("military", "bunker");
                return true;
            case 38:
                tags.put("historic", "fortification");
                return true;
            case 39:
                tags.put("barrier", "wall");
                return true;
            case 40:
                tags.put("man_made", "conveyor");
                return true;
            case 41:
                tags.put("man_made", "ski_jump");
                return true;

            // ---- Education / health / emergency ----
            case 42:
                tags.put("landuse", "*");
                return true;
            case 43:
                tags.put("amenity", "police");
                return true;
            case 44:
                tags.put("amenity", "fire_station");
                return true;
            case 45:
                tags.put("office", "government");
                return true;
            case 46:
                tags.put("amenity", "post_office");
                return true;
            case 47:
                tags.put("amenity", "school");
                return true;
            case 48:
                tags.put("amenity", "school");
                tags.put("school:facility", "*");
                return true;
            case 49:
                tags.put("amenity", "social_facility");
                return true;
            case 50:
                tags.put("amenity", "hospital");
                return true;
            case 51:
                tags.put("amenity", "clinic");
                return true;
            case 52:
                tags.put("amenity", "pharmacy");
                return true;

            // ---- Religion ----
            case 53:
                tags.put("amenity", "place_of_worship");
                tags.put("building", "church");
                return true;
            case 54:
                tags.put("amenity", "place_of_worship");
                tags.put("building", "chapel");
                return true;
            case 55:
                tags.put("historic", "wayside_cross");
                return true;
            case 56:
                tags.put("historic", "wayside_shrine");
                return true;

            // ---- Historic ----
            case 57:
                tags.put("historic", "ruins");
                return true;
            case 58:
                tags.put("historic", "monument");
                return true;
            case 59:
                tags.put("historic", "memorial");
                return true;
            case 60:
                tags.put("tourism", "viewpoint");
                return true;
            case 61:
                tags.put("tourism", "artwork");
                return true;
            case 62:
                tags.put("tourism", "museum");
                return true;
            case 63:
                tags.put("tourism", "hotel");
                return true;
            case 64:
                tags.put("tourism", "camp_site");
                return true;

            // ---- Transport — railways ----
            case 70:
                tags.put("railway", "subway");
                return true;
            case 71:
                tags.put("railway", "tram");
                return true;
            case 72:
                tags.put("aerialway", "cable_car");
                return true;
            case 73:
                tags.put("man_made", "bridge");
                return true;
            case 74:
                tags.put("railway", "narrow_gauge");
                return true;
            case 75:
                tags.put("railway", "rail");
                return true;
            case 76:
                tags.put("railway", "light_rail");
                return true;
            case 77:
                tags.put("railway", "station");
                return true;
            case 78:
                tags.put("railway", "halt");
                return true;

            // ---- Transport — roads ----
            case 79:
                tags.put("highway", "road");
                return true;
            case 80:
                tags.put("highway", "motorway");
                return true;
            case 81:
                tags.put("highway", "primary");
                return true;
            case 82:
                tags.put("highway", "path");
                return true;
            case 83:
                tags.put("highway", "track");
                return true;
            case 84:
                tags.put("highway", "unclassified");
                return true;
            case 85:
                tags.put("highway", "residential");
                return true;
            case 86:
                tags.put("highway", "service");
                return true;
            case 87:
                tags.put("highway", "pedestrian");
                return true;

            // ---- Utilities — power ----
            case 88:
                tags.put("power", "line");
                return true;
            case 89:
                tags.put("power", "minor_line");
                return true;
            case 90:
                tags.put("power", "plant");
                return true;
            case 91:
                tags.put("power", "substation");
                return true;

            // ---- Utilities — pipelines ----
            case 92:
                tags.put("man_made", "pipeline");
                tags.put("substance", "gas");
                return true;

            // ---- Waterways (lines) ----
            case 93:
                tags.put("waterway", "stream");
                return true;
            case 94:
                tags.put("waterway", "river");
                return true;
            case 95:
                tags.put("waterway", "drain");
                return true;
            case 96:
                tags.put("waterway", "canal");
                return true;

            // ---- Aeroway ----
            case 97:
                tags.put("aeroway", "runway");
                return true;
            case 98:
                tags.put("aeroway", "taxiway");
                return true;

            // ---- Buildings ----
            case 99:
                tags.put("building", "yes");
                return true;
            case 100:
                tags.put("building", "industrial");
                return true;
            case 101:
                tags.put("historic", "castle");
                tags.put("castle_type", "fortress");
                return true;
            case 102:
                tags.put("historic", "castle");
                tags.put("castle_type", "palace");
                return true;
            case 103:
                tags.put("building", "church");
                tags.put("amenity", "place_of_worship");
                return true;
            case 104:
                tags.put("building", "hospital");
                tags.put("amenity", "hospital");
                return true;
            case 105:
                tags.put("building", "school");
                tags.put("amenity", "school");
                return true;
            // 106 — gap in layer list
            case 107:
                tags.put("building", "greenhouse");
                return true;
            case 108:
                tags.put("building", "storage_tank");
                return true;
            case 109:
                tags.put("man_made", "storage_tank");
                return true;

            // ---- Land use ----
            case 110:
                tags.put("landuse", "military");
                return true;
            case 111:
                tags.put("landuse", "industrial");
                return true;
            case 112:
                tags.put("landuse", "commercial");
                return true;
            case 113:
                tags.put("landuse", "retail");
                return true;
            case 114:
                tags.put("landuse", "residential");
                return true;
            case 115:
                tags.put("landuse", "allotments");
                return true;
            case 116:
                tags.put("landuse", "cemetery");
                return true;
            case 117:
                tags.put("landuse", "landfill");
                return true;
            case 118:
                tags.put("landuse", "quarry");
                return true;
            case 119:
                tags.put("landuse", "brownfield");
                return true;
            // 120 — gap in layer list
            case 121:
                tags.put("landuse", "greenfield");
                return true;
            case 122:
                tags.put("leisure", "sports_centre");
                return true;
            case 123:
                tags.put("amenity", "parking");
                return true;
            case 124:
                tags.put("leisure", "stadium");
                return true;
            case 125:
                tags.put("aeroway", "aerodrome");
                return true;
            case 126:
                tags.put("aeroway", "helipad");
                return true;
            case 127:
                tags.put("landuse", "port");
                return true;
            case 128:
                tags.put("waterway", "dam");
                return true;
            case 129:
                tags.put("waterway", "weir");
                return true;
            case 130:
                tags.put("natural", "bare_rock");
                return true;
            case 131:
                tags.put("natural", "wetland");
                tags.put("wetland", "marsh");
                return true;
            case 132:
                tags.put("natural", "water");
                return true;
            case 133:
                tags.put("natural", "beach");
                return true;
            case 134:
                tags.put("leisure", "park");
                return true;
            case 135:
                tags.put("leisure", "garden");
                return true;
            case 136:
                tags.put("landuse", "vineyard");
                return true;
            case 137:
                tags.put("landuse", "farmland");
                tags.put("crop", "hop");
                return true;
            case 138:
                tags.put("landuse", "farmland");
                return true;
            case 139:
                tags.put("landuse", "meadow");
                return true;
            case 140:
                tags.put("natural", "scrub");
                return true;
            case 141:
                tags.put("natural", "scrub");
                return true;
            case 142:
                tags.put("landuse", "forest");
                return true;
            case 143:
                tags.put("landuse", "forest");
                tags.put("leaf_type", "needleleaved");
                return true;
            case 144:
                tags.put("landuse", "forest");
                tags.put("leaf_type", "broadleaved");
                return true;
            case 145:
                tags.put("leisure", "garden");
                return true;
            case 146:
                tags.put("leisure", "golf_course");
                return true;
            case 147:
                tags.put("leisure", "track");
                return true;
            case 148:
                tags.put("leisure", "pitch");
                return true;
            case 149:
                tags.put("amenity", "charging_station");
                return true;
            case 150:
                tags.put("type", "route");
                tags.put("route", "hiking");
                return true;
            case 151:
                // GIA zástavba — no OSM equivalent, intentionally ignored
                return false;

            default:
                return false;
        }
    }
}
