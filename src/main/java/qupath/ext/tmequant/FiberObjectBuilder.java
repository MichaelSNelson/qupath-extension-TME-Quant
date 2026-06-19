package qupath.ext.tmequant;

import java.util.ArrayList;
import java.util.List;
import qupath.lib.common.ColorTools;
import qupath.lib.geom.Point2;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Converts fibers returned by the Python server into QuPath objects.
 *
 * <p>The server reports fiber polylines in <b>region-local</b> pixel
 * coordinates at the downsample the region was read at. This builder maps them
 * back to full-resolution image coordinates:</p>
 *
 * <pre>
 *   fullX = regionX + localX * downsample
 *   fullY = regionY + localY * downsample
 * </pre>
 *
 * Each fiber becomes a {@link qupath.lib.roi.interfaces.ROI#isLine() line/polyline}
 * ROI (a PolylineROI), wrapped as either an annotation or a detection. Per-fiber
 * properties (angle, length, width, straightness) are stored as measurements.
 */
public final class FiberObjectBuilder {

    private FiberObjectBuilder() {}

    public static List<PathObject> build(
            List<FiberSocketClient.Fiber> fibers,
            double regionX,
            double regionY,
            double downsample,
            double pixelSizeMicrons,
            ImagePlane plane,
            boolean asDetections,
            PathClass fiberClass) {

        List<PathObject> out = new ArrayList<>();
        if (fibers == null) {
            return out;
        }
        // Server lengths/distances are in analysed (downsampled) pixels. Convert
        // to full-resolution pixels (x downsample), then to microns if calibrated.
        boolean calibrated = pixelSizeMicrons > 0 && !Double.isNaN(pixelSizeMicrons);
        double toUnit = downsample * (calibrated ? pixelSizeMicrons : 1.0);
        String lenUnit = calibrated ? "um" : "px";

        for (FiberSocketClient.Fiber f : fibers) {
            if (f.points == null || f.points.length < 2) {
                continue;
            }
            List<Point2> pts = new ArrayList<>(f.points.length);
            for (double[] p : f.points) {
                if (p == null || p.length < 2) {
                    continue;
                }
                double fullX = regionX + p[0] * downsample;
                double fullY = regionY + p[1] * downsample;
                pts.add(new Point2(fullX, fullY));
            }
            if (pts.size() < 2) {
                continue;
            }

            // Colour by TACS class when the server classified it; else default.
            PathClass cls = (f.tacs != null && !f.tacs.isBlank())
                    ? tacsClass(f.tacs)
                    : fiberClass;

            ROI roi = ROIs.createPolylineROI(pts, plane);
            PathObject obj = asDetections
                    ? PathObjects.createDetectionObject(roi, cls)
                    : PathObjects.createAnnotationObject(roi, cls);

            var ml = obj.getMeasurementList();
            ml.put("Fiber ID", f.id);
            ml.put("Angle (deg)", f.angle);
            ml.put("Length (" + lenUnit + ")", f.lengthArc * toUnit);
            ml.put("End-to-end length (" + lenUnit + ")", f.lengthEnd * toUnit);
            if (!Double.isNaN(f.width)) {
                ml.put("Width (" + lenUnit + ")", f.width * toUnit);
            }
            ml.put("Straightness", f.straightness);
            if (f.angleToTangent != null) {
                ml.put("Angle to boundary tangent (deg)", f.angleToTangent);
            }
            if (f.distanceToBoundary != null) {
                ml.put("Distance to boundary (" + lenUnit + ")", f.distanceToBoundary * toUnit);
            }
            ml.close();

            obj.setName((f.tacs != null && !f.tacs.isBlank() ? f.tacs + " " : "Fiber ") + f.id);
            out.add(obj);
        }
        return out;
    }

    /**
     * PathClass for a TACS label, using the canonical TMEQuant colours
     * (TACS-3 red / invasive, TACS-2 green / parallel, TACS-1 blue / random).
     */
    private static PathClass tacsClass(String tacs) {
        int rgb;
        switch (tacs) {
            case "TACS-3":
                rgb = ColorTools.packRGB(255, 0, 0);
                break;
            case "TACS-2":
                rgb = ColorTools.packRGB(0, 220, 0);
                break;
            case "TACS-1":
                rgb = ColorTools.packRGB(0, 110, 255);
                break;
            default:
                rgb = ColorTools.packRGB(128, 128, 128);
                break;
        }
        return PathClass.fromString(tacs, rgb);
    }
}
