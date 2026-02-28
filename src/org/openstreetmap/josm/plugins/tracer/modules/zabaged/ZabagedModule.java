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

import java.awt.Cursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.tracer.CombineTagsResolver;
import org.openstreetmap.josm.plugins.tracer.TracerModule;
import org.openstreetmap.josm.plugins.tracer.TracerRecord;
import org.openstreetmap.josm.plugins.tracer.connectways.EdNode;
import org.openstreetmap.josm.plugins.tracer.connectways.EdObject;
import org.openstreetmap.josm.plugins.tracer.connectways.EdWay;
import org.openstreetmap.josm.plugins.tracer.connectways.LatLonSize;
import org.openstreetmap.josm.plugins.tracer.connectways.WayEditor;
import static org.openstreetmap.josm.tools.I18n.tr;
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * Tracer module for the Czech ČÚZK ZABAGED topographic database.
 *
 * Queries the ArcGIS REST identify endpoint, optionally lets the user choose
 * which returned feature to trace, then creates OSM geometry with mapped tags.
 */
public final class ZabagedModule extends TracerModule {

    private boolean moduleEnabled;
    private final ZabagedServer m_server = new ZabagedServer();

    private static final double oversizeInDataBoundsMeters = 5.0;
    private static final double automaticOsmDownloadMeters = 500.0;

    public ZabagedModule(boolean enabled) {
        moduleEnabled = enabled;
    }

    @Override
    public void init() {
    }

    @Override
    public Cursor getCursor() {
        return ImageProvider.getCursor("crosshair", "tracer-zabaged-sml");
    }

    @Override
    public Cursor getCursor(boolean ctrl, boolean alt, boolean shift) {
        if (ctrl)
            return ImageProvider.getCursor("crosshair", "tracer-zabaged-new-sml");
        if (shift)
            return ImageProvider.getCursor("crosshair", "tracer-zabaged-tags-sml");
        return ImageProvider.getCursor("crosshair", "tracer-zabaged-sml");
    }

    @Override
    public String getName() {
        return tr("ZABAGED");
    }

    @Override
    public boolean moduleIsEnabled() {
        return moduleEnabled;
    }

    @Override
    public void setModuleIsEnabled(boolean enabled) {
        moduleEnabled = enabled;
    }

    /**
     * Called on the EDT. Capture viewport here before spawning the background task.
     */
    @Override
    public AbstractTracerTask trace(final LatLon pos, final boolean ctrl, final boolean alt, final boolean shift) {
        // Capture viewport on EDT
        String mapExtent = "";
        int viewWidth = 800;
        int viewHeight = 600;

        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            org.openstreetmap.josm.gui.MapView mv = MainApplication.getMap().mapView;
            viewWidth = mv.getWidth();
            viewHeight = mv.getHeight();
            Bounds realBounds = mv.getRealBounds();
            if (realBounds != null) {
                mapExtent = String.format(Locale.US, "%.8f,%.8f,%.8f,%.8f",
                    realBounds.getMinLon(), realBounds.getMinLat(),
                    realBounds.getMaxLon(), realBounds.getMaxLat());
            }
        }

        return new ZabagedTracerTask(pos, ctrl, alt, shift, mapExtent, viewWidth, viewHeight);
    }

    // =========================================================================
    // Inner task class
    // =========================================================================

    class ZabagedTracerTask extends AbstractTracerTask {

        private final String m_mapExtent;
        private final int m_viewWidth;
        private final int m_viewHeight;

        ZabagedTracerTask(LatLon pos, boolean ctrl, boolean alt, boolean shift,
                String mapExtent, int viewWidth, int viewHeight) {
            super(pos, ctrl, alt, shift);
            m_mapExtent = mapExtent;
            m_viewWidth = viewWidth;
            m_viewHeight = viewHeight;
        }

        private ZabagedRecord zabagedRecord() {
            return (ZabagedRecord) super.getRecord();
        }

        @Override
        protected TracerRecord downloadRecord(LatLon pos) throws Exception {
            return m_server.getRecord(pos, m_mapExtent, m_viewWidth, m_viewHeight);
        }

        @Override
        protected LatLonSize getMissingAreaCheckExtraSize(LatLon pos) {
            return LatLonSize.get(pos, 3 * oversizeInDataBoundsMeters);
        }

        @Override
        protected double getAutomaticOsmDownloadMeters() {
            return automaticOsmDownloadMeters;
        }

        // -----------------------------------------------------------------
        // Feature selection dialog (shown on EDT inside stepCreateTracedPolygon)
        // -----------------------------------------------------------------

        /**
         * If more than one feature was returned, show a selection dialog and
         * activate the user's choice. Returns false if the user cancelled.
         */
        private boolean selectAndActivateFeature() {
            ZabagedRecord rec = zabagedRecord();
            List<ZabagedFeature> features = new ArrayList<>(rec.getFeatures());

            if (features.isEmpty())
                return false;

            if (features.size() == 1) {
                rec.activateFeature(features.get(0));
                return true;
            }

            // Build list model
            DefaultListModel<String> model = new DefaultListModel<>();
            for (ZabagedFeature f : features) {
                String geomIcon;
                switch (f.getGeometryType()) {
                    case POLYGON:  geomIcon = "[A]"; break;
                    case POLYLINE: geomIcon = "[L]"; break;
                    default:       geomIcon = "[P]"; break;
                }
                model.addElement(geomIcon + " " + f.getLayerName());
            }

            JList<String> list = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(0);

            JScrollPane scroll = new JScrollPane(list);
            scroll.setPreferredSize(new java.awt.Dimension(380, Math.min(240, features.size() * 22 + 20)));

            ExtendedDialog dialog = new ExtendedDialog(
                MainApplication.getMainFrame(),
                tr("Select ZABAGED feature"),
                new String[]{tr("Trace"), tr("Cancel")});
            dialog.setButtonIcons(new String[]{"ok", "cancel"});
            dialog.setIcon(JOptionPane.QUESTION_MESSAGE);
            dialog.setContent(scroll);
            dialog.showDialog();

            if (dialog.getValue() != 1) // not "Trace"
                return false;

            int idx = list.getSelectedIndex();
            if (idx < 0 || idx >= features.size())
                return false;

            rec.activateFeature(features.get(idx));
            return true;
        }

        // -----------------------------------------------------------------
        // Core tracing
        // -----------------------------------------------------------------

        @Override
        protected EdObject createTracedPolygonImpl(WayEditor editor) {

            ZabagedRecord rec = zabagedRecord();

            // Let user pick the feature (blocks on EDT dialog)
            if (!selectAndActivateFeature()) {
                postTraceNotifications().add(tr("No ZABAGED feature selected."));
                return null;
            }

            ZabagedFeature feature = rec.getActiveFeature();
            if (feature == null)
                return null;

            System.out.println("ZabagedModule: tracing layer " + feature.getLayerId()
                + " (" + feature.getLayerName() + ") type=" + feature.getGeometryType());

            switch (feature.getGeometryType()) {
                case POLYGON:
                    return tracePolygon(editor, rec, feature);
                case POLYLINE:
                    return tracePolyline(editor, rec, feature);
                case POINT:
                    return tracePoint(editor, rec, feature);
                default:
                    postTraceNotifications().add(tr("Unsupported geometry type."));
                    return null;
            }
        }

        // --- Polygon tracing (uses full TracerRecord pipeline) ---

        private EdObject tracePolygon(WayEditor editor, ZabagedRecord rec, ZabagedFeature feature) {
            // rec.activateFeature() already loaded outer/inner into TracerRecord

            if (!rec.hasOuter()) {
                postTraceNotifications().add(tr("ZABAGED polygon has no usable geometry."));
                return null;
            }

            // Check ctrl: if fresh trace requested, skip retrace
            EdObject trobj;
            try {
                trobj = rec.createObject(editor);
            } catch (Exception e) {
                System.out.println("ZabagedModule: createObject failed: " + e.getMessage());
                postTraceNotifications().add(tr("Failed to create ZABAGED polygon: {0}", e.getMessage()));
                return null;
            }

            // Inside data source bounds check
            LatLonSize boundsOversize = LatLonSize.get(trobj.getBBox(), oversizeInDataBoundsMeters);
            if (!trobj.isInsideDataSourceBounds(boundsOversize)) {
                wayIsOutsideDownloadedAreaDialog();
                return null;
            }

            // Shift = tags-only update: find existing object near click position
            if (m_updateTagsOnly) {
                // There is no meaningful retrace for ZABAGED (no stable ID), so just tag
                // the closest existing object that the click landed on — handled by tagTracedObject
                // called below. If we have no existing object, refuse.
                postTraceNotifications().add(tr("ZABAGED tags-only update: use Shift+click on an existing object."));
                return null;
            }

            // Tag the new object
            if (!tagTracedObject(trobj, rec))
                return null;

            return trobj;
        }

        // --- Polyline tracing ---

        private EdObject tracePolyline(WayEditor editor, ZabagedRecord rec, ZabagedFeature feature) {
            List<List<LatLon>> paths = feature.getPaths();
            if (paths.isEmpty()) {
                postTraceNotifications().add(tr("ZABAGED polyline has no geometry."));
                return null;
            }

            // Trace only the first path (a single identify hit returns one feature per path group)
            List<LatLon> path = paths.get(0);
            if (path.size() < 2) {
                postTraceNotifications().add(tr("ZABAGED polyline path too short."));
                return null;
            }

            List<EdNode> nodes = new ArrayList<>(path.size());
            for (LatLon ll : path) {
                nodes.add(editor.newNode(ll));
            }

            // Open way — do NOT close it
            EdWay way = editor.newWay(nodes);

            // Tag
            Map<String, String> keys = rec.getKeys();
            way.setKeys(keys);

            return way;
        }

        // --- Point tracing ---

        private EdObject tracePoint(WayEditor editor, ZabagedRecord rec, ZabagedFeature feature) {
            LatLon pt = feature.getPoint();
            if (pt == null) {
                postTraceNotifications().add(tr("ZABAGED point has no geometry."));
                return null;
            }

            EdNode node = editor.newNode(pt);

            // Tag
            Map<String, String> keys = rec.getKeys();
            node.setKeys(keys);

            return node;
        }

        // --- Tag helper ---

        private boolean tagTracedObject(EdObject obj, ZabagedRecord rec) {
            Map<String, String> oldKeys = obj.getKeys();
            Map<String, String> newKeys = new java.util.HashMap<>(rec.getKeys());

            // Merge non-conflicting keys
            for (Map.Entry<String, String> tag : oldKeys.entrySet()) {
                if (!newKeys.containsKey(tag.getKey()))
                    newKeys.put(tag.getKey(), tag.getValue());
            }
            for (Map.Entry<String, String> tag : newKeys.entrySet()) {
                if (!oldKeys.containsKey(tag.getKey()))
                    oldKeys.put(tag.getKey(), tag.getValue());
            }

            Map<String, String> result = CombineTagsResolver.launchIfNecessary(oldKeys, newKeys);
            if (result == null)
                return false;

            obj.setKeys(result);
            return true;
        }
    }
}
