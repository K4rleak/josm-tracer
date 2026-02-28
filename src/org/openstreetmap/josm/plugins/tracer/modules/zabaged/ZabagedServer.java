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

import java.io.BufferedReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.tracer.TracerUtils;

/**
 * Queries the ČÚZK ZABAGED ArcGIS REST identify endpoint and returns a parsed ZabagedRecord.
 *
 * The viewport parameters (mapExtent, imageDisplay) must be captured on the EDT
 * by the caller (ZabagedModule.trace()) and passed to getRecord().
 */
public final class ZabagedServer {

    private static final String IDENTIFY_URL =
        "https://ags.cuzk.gov.cz/arcgis/rest/services/ZABAGED_POLOHOPIS/MapServer/identify";

    /**
     * All queryable layer IDs from the ZABAGED MapServer, in descending order
     * (layers 29, 106, 120 are non-queryable / group layers and are omitted).
     */
    private static final String ALL_LAYERS;

    static {
        // Build "144,143,...,0" string (descending), excluding 29, 106, 120
        StringBuilder sb = new StringBuilder();
        for (int i = 151; i >= 0; i--) {
            if (i == 29 || i == 106 || i == 120)
                continue;
            if (sb.length() > 0)
                sb.append(",");
            sb.append(i);
        }
        ALL_LAYERS = sb.toString();
    }

    /**
     * Download and parse a ZABAGED identify response for the given click position
     * and current map viewport.
     *
     * @param pos          The clicked LatLon position
     * @param mapExtent    Comma-separated "minLon,minLat,maxLon,maxLat" of current viewport (WGS84)
     * @param imageWidth   Viewport width in pixels
     * @param imageHeight  Viewport height in pixels
     * @return Parsed ZabagedRecord (may have no features if API returns empty results)
     */
    public ZabagedRecord getRecord(LatLon pos, String mapExtent, int imageWidth, int imageHeight)
            throws MalformedURLException, IOException {

        String url = buildUrl(pos, mapExtent, imageWidth, imageHeight);
        System.out.println("ZabagedServer request: " + url);

        String json = callServer(url);
        System.out.println("ZabagedServer reply: " + json);

        ZabagedRecord record = new ZabagedRecord();
        record.parseJSON(json);
        return record;
    }

    private String buildUrl(LatLon pos, String mapExtent, int imageWidth, int imageHeight)
            throws MalformedURLException {

        // Geometry point: {"x": lon, "y": lat}
        String geometry = String.format(Locale.US,
            "{\"x\":%s,\"y\":%s}",
            formatCoord(pos.lon()),
            formatCoord(pos.lat()));

        // imageDisplay: width,height,dpi
        String imageDisplay = imageWidth + "," + imageHeight + ",96";

        try {
            StringBuilder sb = new StringBuilder(IDENTIFY_URL);
            sb.append("?f=json");
            sb.append("&returnGeometry=true");
            sb.append("&sr=4326");
            sb.append("&geometry=").append(URLEncoder.encode(geometry, "UTF-8"));
            sb.append("&geometryType=esriGeometryPoint");
            sb.append("&tolerance=5");
            sb.append("&imageDisplay=").append(URLEncoder.encode(imageDisplay, "UTF-8"));
            sb.append("&mapExtent=").append(URLEncoder.encode(mapExtent, "UTF-8"));
            sb.append("&layers=visible:").append(URLEncoder.encode(ALL_LAYERS, "UTF-8"));
            return sb.toString();
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always supported
            throw new AssertionError(e);
        }
    }

    private String callServer(String urlString) throws MalformedURLException, IOException {
        try (BufferedReader reader = TracerUtils.openUrlStream(urlString, "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0)
                    sb.append(" ");
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static String formatCoord(double v) {
        // Use enough decimal places for sub-meter precision at equator
        return String.format(Locale.US, "%.8f", v);
    }
}
