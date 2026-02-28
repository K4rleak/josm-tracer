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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.openstreetmap.josm.data.coor.LatLon;

/**
 * Holds a single feature returned by the ZABAGED ArcGIS identify endpoint.
 * Geometry type is one of: esriGeometryPolygon, esriGeometryPolyline, esriGeometryPoint.
 */
public final class ZabagedFeature {

    public enum GeometryType {
        POLYGON,
        POLYLINE,
        POINT
    }

    private final int m_layerId;
    private final String m_layerName;
    private final GeometryType m_geometryType;
    private final Map<String, String> m_attributes;

    // Polygon: list of rings; ring[0] = outer, ring[1..n] = holes
    private final List<List<LatLon>> m_rings;

    // Polyline: list of paths
    private final List<List<LatLon>> m_paths;

    // Point
    private final LatLon m_point;

    // --- private constructors ---

    private ZabagedFeature(int layerId, String layerName, GeometryType geomType,
            Map<String, String> attributes,
            List<List<LatLon>> rings,
            List<List<LatLon>> paths,
            LatLon point) {
        m_layerId = layerId;
        m_layerName = layerName;
        m_geometryType = geomType;
        m_attributes = Collections.unmodifiableMap(attributes);
        m_rings = rings != null ? rings : Collections.emptyList();
        m_paths = paths != null ? paths : Collections.emptyList();
        m_point = point;
    }

    // --- static factory methods ---

    public static ZabagedFeature polygon(int layerId, String layerName,
            Map<String, String> attributes, List<List<LatLon>> rings) {
        return new ZabagedFeature(layerId, layerName, GeometryType.POLYGON, attributes, rings, null, null);
    }

    public static ZabagedFeature polyline(int layerId, String layerName,
            Map<String, String> attributes, List<List<LatLon>> paths) {
        return new ZabagedFeature(layerId, layerName, GeometryType.POLYLINE, attributes, null, paths, null);
    }

    public static ZabagedFeature point(int layerId, String layerName,
            Map<String, String> attributes, LatLon pt) {
        return new ZabagedFeature(layerId, layerName, GeometryType.POINT, attributes, null, null, pt);
    }

    // --- accessors ---

    public int getLayerId() {
        return m_layerId;
    }

    public String getLayerName() {
        return m_layerName;
    }

    public GeometryType getGeometryType() {
        return m_geometryType;
    }

    public Map<String, String> getAttributes() {
        return m_attributes;
    }

    /** For POLYGON: returns all rings. rings.get(0) = outer, the rest = holes. */
    public List<List<LatLon>> getRings() {
        return m_rings;
    }

    /** For POLYLINE: returns all paths. */
    public List<List<LatLon>> getPaths() {
        return m_paths;
    }

    /** For POINT: returns the coordinate. */
    public LatLon getPoint() {
        return m_point;
    }

    public boolean isPolygon() {
        return m_geometryType == GeometryType.POLYGON;
    }

    public boolean isPolyline() {
        return m_geometryType == GeometryType.POLYLINE;
    }

    public boolean isPoint() {
        return m_geometryType == GeometryType.POINT;
    }

    @Override
    public String toString() {
        return "[" + m_layerId + "] " + m_layerName + " (" + m_geometryType + ")";
    }
}
