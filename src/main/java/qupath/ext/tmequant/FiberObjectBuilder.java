package qupath.ext.tmequant;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import qupath.lib.common.ColorTools;
import qupath.lib.geom.Point2;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
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
 *
 * <p>When a {@code restrictRoi} is supplied, each fiber is geometrically clipped to
 * that ROI (the inside portion(s) are kept), remnants shorter than the minimum fiber
 * length are dropped, and a fiber split by a concave boundary yields several objects
 * whose names are suffixed {@code .1}, {@code .2}, … Length/straightness/angle are
 * recomputed from the clipped geometry; width is retained from the server (a local
 * property that cannot be recomputed from the trimmed centreline).</p>
 */
public final class FiberObjectBuilder {

    private FiberObjectBuilder() {}

    /** Build with no spatial restriction (server metrics used verbatim). */
    /**
     * Identifier of the run currently writing objects, stamped onto every one as a "Run ID"
     * measurement so a detection can be traced back to the settings that produced it.
     *
     * <p>The value is the run's start time in MINUTES since 2024-01-01 00:00 local (about 1.47
     * million in late 2026), and {@code runs.json} maps it to the full stamp, settings and log
     * pair in {@code <project>/logs/fiber-analysis/}. 0 means "not set" and no measurement is
     * written.</p>
     *
     * <p>Why not the readable {@code yyyyMMddHHmmss}? QuPath stores every measurement as a
     * 32-bit float -- the "double" list type included -- and a float has 24 bits of mantissa:
     * integers are exact only below 16,777,216. Near 2e13 the spacing is 2,097,152, so two
     * runs a day apart read back as the SAME id and neither matched the exact value in
     * runs.json (found by reading the ids back through QuPath). Minutes since 2024 stay exact
     * until 2055. A run starting in the same minute as the previous one in this session takes
     * the next free value, so ids are unique per session; the index carries the real stamp.</p>
     *
     * <p>Static because a run is a single sequential pass on one background thread, and threading
     * it through every call site buys nothing; set it with {@link #setRunId} around a run.</p>
     */
    private static volatile double runId = 0.0;

    /** Largest integer a float holds exactly; a run id must stay below it to survive storage. */
    public static final double MAX_EXACT_FLOAT = 16_777_216.0;

    private static final java.time.LocalDateTime RUN_ID_EPOCH =
            java.time.LocalDateTime.of(2024, 1, 1, 0, 0);
    private static final java.time.format.DateTimeFormatter STAMP_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static long lastIssuedRunId = 0;

    /** Stamp this run id onto every object built from now on; 0 disables. */
    public static void setRunId(double id) {
        runId = id;
    }

    /**
     * Turn a {@code yyyyMMdd-HHmmss} run stamp into the numeric id (minutes since 2024-01-01,
     * bumped past the previous id of this session if it would repeat), or 0 if it does not parse.
     */
    public static synchronized double runIdFromStamp(String stamp) {
        if (stamp == null) {
            return 0.0;
        }
        try {
            var t = java.time.LocalDateTime.parse(stamp.trim(), STAMP_FORMAT);
            long minutes = java.time.Duration.between(RUN_ID_EPOCH, t).toMinutes();
            if (minutes <= 0 || minutes >= MAX_EXACT_FLOAT) {
                return 0.0;
            }
            if (minutes <= lastIssuedRunId) {
                minutes = lastIssuedRunId + 1;
            }
            lastIssuedRunId = minutes;
            return (double) minutes;
        } catch (java.time.format.DateTimeParseException e) {
            return 0.0;
        }
    }

    public static List<PathObject> build(
            List<FiberSocketClient.Fiber> fibers,
            double regionX,
            double regionY,
            double downsample,
            double pixelSizeMicrons,
            ImagePlane plane,
            boolean asDetections,
            PathClass fiberClass) {
        return build(fibers, regionX, regionY, downsample, pixelSizeMicrons, plane,
                asDetections, fiberClass, null, 0.0);
    }

    /**
     * Build, optionally clipping every fiber to {@code restrictRoi}.
     *
     * @param restrictRoi   area ROI (full-resolution coords) to clip fibers to, or null for none.
     * @param minLenInUnit  minimum fiber length in the active unit (µm if calibrated, else px);
     *                      clipped remnants shorter than this are dropped. Ignored when restrictRoi is null.
     */
    public static List<PathObject> build(
            List<FiberSocketClient.Fiber> fibers,
            double regionX,
            double regionY,
            double downsample,
            double pixelSizeMicrons,
            ImagePlane plane,
            boolean asDetections,
            PathClass fiberClass,
            ROI restrictRoi,
            double minLenInUnit) {

        List<PathObject> out = new ArrayList<>();
        if (fibers == null) {
            return out;
        }
        // Server lengths/distances are in analysed (downsampled) pixels. Convert
        // to full-resolution pixels (x downsample), then to microns if calibrated.
        boolean calibrated = pixelSizeMicrons > 0 && !Double.isNaN(pixelSizeMicrons);
        double toUnit = downsample * (calibrated ? pixelSizeMicrons : 1.0);
        double pxToUnit = calibrated ? pixelSizeMicrons : 1.0; // full-res px -> unit (downsample already applied)
        String lenUnit = calibrated ? "um" : "px";

        // Pre-convert the restriction ROI to a JTS geometry once.
        Geometry restrictGeom = null;
        if (restrictRoi != null) {
            try {
                restrictGeom = GeometryTools.roiToGeometry(restrictRoi);
            } catch (Exception e) {
                restrictGeom = null; // fall back to no clipping rather than fail the whole commit
            }
        }

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

            if (restrictGeom == null) {
                // ---- Unrestricted: keep the server-reported metrics verbatim ----
                ROI roi = ROIs.createPolylineROI(pts, plane);
                PathObject obj = asDetections
                        ? PathObjects.createDetectionObject(roi, cls)
                        : PathObjects.createAnnotationObject(roi, cls);

                var ml = obj.getMeasurementList();
                if (runId != 0.0) {
                    ml.put("Run ID", runId);
                }
                ml.put("Fiber ID", f.id);
                ml.put("Angle (deg)", f.angle);
                ml.put("Length (" + lenUnit + ")", f.lengthArc * toUnit);
                ml.put("End-to-end length (" + lenUnit + ")", f.lengthEnd * toUnit);
                putWidths(ml, f, lenUnit, toUnit);
                ml.put("Straightness", f.straightness);
                // The clipped path below has always written these; this one had not, so the
                // crimp/kink metrics appeared only when a run restricted to an object.
                putWaviness(ml, f, lenUnit, toUnit);
                if (f.angleToTangent != null) {
                    ml.put("Angle to boundary tangent (deg)", f.angleToTangent);
                }
                if (f.distanceToBoundary != null) {
                    ml.put("Distance to boundary (" + lenUnit + ")", f.distanceToBoundary * toUnit);
                }
                ml.close();

                obj.setName((f.tacs != null && !f.tacs.isBlank() ? f.tacs + " " : "Fiber ") + f.id);
                out.add(obj);
                continue;
            }

            // ---- Restricted: clip the fiber to the ROI and keep in-length pieces ----
            List<List<Point2>> kept = new ArrayList<>();
            for (List<Point2> piece : clipToGeometry(pts, restrictGeom, plane)) {
                if (piece.size() < 2) {
                    continue;
                }
                double arcUnit = arcLength(piece) * pxToUnit;
                if (arcUnit + 1e-9 < minLenInUnit) {
                    continue; // remnant shorter than the min fiber length
                }
                kept.add(piece);
            }
            boolean split = kept.size() > 1;
            for (int i = 0; i < kept.size(); i++) {
                out.add(makeClippedFiber(kept.get(i), plane, asDetections, cls, f,
                        toUnit, pxToUnit, lenUnit, split ? (i + 1) : -1));
            }
        }
        return out;
    }

    /** Intersect a full-res polyline with a geometry; return each inside piece as a point list. */
    private static List<List<Point2>> clipToGeometry(List<Point2> pts, Geometry restrictGeom, ImagePlane plane) {
        List<List<Point2>> pieces = new ArrayList<>();
        Geometry inside;
        try {
            ROI line = ROIs.createPolylineROI(pts, plane);
            inside = restrictGeom.intersection(line.getGeometry());
        } catch (Exception e) {
            return pieces; // drop this fiber on a geometry error rather than crash the run
        }
        if (inside == null || inside.isEmpty()) {
            return pieces;
        }
        for (int i = 0; i < inside.getNumGeometries(); i++) {
            Geometry g = inside.getGeometryN(i);
            Coordinate[] cs = g.getCoordinates();
            if (cs == null || cs.length < 2) {
                continue; // skip point/degenerate intersections
            }
            List<Point2> pc = new ArrayList<>(cs.length);
            for (Coordinate c : cs) {
                pc.add(new Point2(c.x, c.y));
            }
            pieces.add(pc);
        }
        return pieces;
    }

    /** Build one clipped-fiber object with metrics recomputed from the clipped geometry. */
    private static PathObject makeClippedFiber(
            List<Point2> pts, ImagePlane plane, boolean asDetections, PathClass cls,
            FiberSocketClient.Fiber f, double toUnit, double pxToUnit, String lenUnit, int part) {
        double arcPx = arcLength(pts);
        Point2 a = pts.get(0);
        Point2 b = pts.get(pts.size() - 1);
        double endPx = dist(a, b);
        double straight = arcPx > 0 ? Math.min(1.0, endPx / arcPx) : 0.0;
        double angle = orientationDeg(a, b);

        ROI roi = ROIs.createPolylineROI(pts, plane);
        PathObject obj = asDetections
                ? PathObjects.createDetectionObject(roi, cls)
                : PathObjects.createAnnotationObject(roi, cls);

        var ml = obj.getMeasurementList();
        if (runId != 0.0) {
            ml.put("Run ID", runId);
        }
        ml.put("Fiber ID", f.id);
        if (part > 0) {
            ml.put("Fiber part", part);
        }
        ml.put("Angle (deg)", angle);
        ml.put("Length (" + lenUnit + ")", arcPx * pxToUnit);
        ml.put("End-to-end length (" + lenUnit + ")", endPx * pxToUnit);
        putWidths(ml, f, lenUnit, toUnit); // server widths (analysed px), best-effort after clipping
        ml.put("Straightness", straight);
        if (f.angleToTangent != null) {
            ml.put("Angle to boundary tangent (deg)", f.angleToTangent);
        }
        if (f.distanceToBoundary != null) {
            ml.put("Distance to boundary (" + lenUnit + ")", f.distanceToBoundary * toUnit);
        }
        ml.close();

        obj.setName((f.tacs != null && !f.tacs.isBlank() ? f.tacs + " " : "Fiber ")
                + f.id + (part > 0 ? "." + part : ""));
        return obj;
    }

    /**
     * Write mean / max / std-dev fibre-width measurements (each = 2× the distance-map radius along the
     * fibre, converted from analysed px to the active unit). NaNs are skipped. A single fibre's width
     * varies along its length, so all three are exposed rather than one collapsed value.
     */
    private static void putWidths(qupath.lib.measurements.MeasurementList ml,
                                  FiberSocketClient.Fiber f, String lenUnit, double toUnit) {
        // Mean AND median: a fibre's width samples are skewed by junctions and blobs the trace
        // passes through, so the mean overstates a clean fibre while the median tracks its body.
        // Mean/median well above 1 flags a contaminated trace (29% of centreline fibres on the GT
        // box had >20% skew); for a clean fibre the two agree. Every tracer reports both now --
        // before, Flow sent its median as "width" and the others their mean, so the one column
        // meant different things depending on the tracer.
        if (!Double.isNaN(f.width)) {
            ml.put("Width mean (" + lenUnit + ")", f.width * toUnit);
        }
        if (!Double.isNaN(f.widthMedian)) {
            ml.put("Width median (" + lenUnit + ")", f.widthMedian * toUnit);
        }
        if (!Double.isNaN(f.widthMax)) {
            ml.put("Width max (" + lenUnit + ")", f.widthMax * toUnit);
        }
        if (!Double.isNaN(f.widthStd)) {
            ml.put("Width SD (" + lenUnit + ")", f.widthStd * toUnit);
        }
        putWaviness(ml, f, lenUnit, toUnit);
    }

    /**
     * Crimp / waviness measurements, from the traced centreline. Angles are unit-free; the amplitude
     * and period are lengths (analysed px -> output unit); curvature is turning PER length, so it is
     * DIVIDED by toUnit (deg/px -> deg/unit). A tracer that smooths the crimp reports a small
     * amplitude and few kinks, which is what makes the two tracers comparable.
     */
    private static void putWaviness(qupath.lib.measurements.MeasurementList ml,
                                    FiberSocketClient.Fiber f, String lenUnit, double toUnit) {
        if (!Double.isNaN(f.kinkMaxDeg)) {
            ml.put("Kink max (deg)", f.kinkMaxDeg);
        }
        if (!Double.isNaN(f.kinkCount)) {
            ml.put("Kink count", f.kinkCount);
        }
        if (!Double.isNaN(f.crimpAmp)) {
            ml.put("Crimp amplitude (" + lenUnit + ")", f.crimpAmp * toUnit);
        }
        if (!Double.isNaN(f.crimpPeriod)) {
            ml.put("Crimp period (" + lenUnit + ")", f.crimpPeriod * toUnit);
        }
        if (!Double.isNaN(f.curvature) && toUnit > 0) {
            ml.put("Curvature (deg/" + lenUnit + ")", f.curvature / toUnit);
        }
        // Only present when the optional anti-flow check ran. Sort by it to review the fibres the
        // pipeline is least sure about: ~0 runs along the collagen, toward 90 runs across it.
        if (!Double.isNaN(f.antiFlowDeg)) {
            ml.put("Anti-flow (deg)", f.antiFlowDeg);
        }
    }

    private static double arcLength(List<Point2> pts) {
        double s = 0;
        for (int i = 1; i < pts.size(); i++) {
            s += dist(pts.get(i - 1), pts.get(i));
        }
        return s;
    }

    private static double dist(Point2 a, Point2 b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }

    /** Overall orientation of a segment as a value in [0, 180). */
    /**
     * Fibre orientation in [0,180). The y term is NEGATED because image y grows downward: without
     * that, this returns the mirror of the angle the server reports for the same fibre (theta vs
     * 180-theta), so a restrict-to-object run and an ordinary one disagreed about every angle --
     * and a colour map of the two together showed two fanned populations where there is one.
     */
    private static double orientationDeg(Point2 a, Point2 b) {
        double ang = Math.toDegrees(Math.atan2(-(b.getY() - a.getY()), b.getX() - a.getX()));
        return ((ang % 180) + 180) % 180;
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
