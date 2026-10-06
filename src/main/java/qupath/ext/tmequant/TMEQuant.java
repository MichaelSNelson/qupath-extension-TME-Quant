package qupath.ext.tmequant;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.ColorTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Headless scripting API for TME-Quant FIRE fibre detection on a SINGLE image.
 *
 * <p>Tune the parameters once in the "Analyze fibers" dialog (they persist), then drive detection
 * from a QuPath script with no dialog. Because each call works on one {@link ImageData}, it composes
 * with QuPath's "Run → Run for project" (which iterates images for you and saves each one) and the
 * headless {@code QuPath script} CLI, and it also works on a single image with no project open (a
 * common server workflow: feed an image + a script).</p>
 *
 * <p>These methods DO NOT save — the caller decides. Under "Run for project" QuPath saves each image
 * automatically; in a standalone script call {@code imageData} is saved by your own
 * {@code entry.saveImageData(...)} or QuPath's normal flow.</p>
 *
 * <p>The FIRE socket server must be running (Extensions ▸ TME Quant ▸ Ping fiber server, or your
 * launcher). A failure to reach it throws a {@link RuntimeException} explaining that.</p>
 *
 * <pre>{@code
 * // Groovy, e.g. under Run -> Run for project:
 * import qupath.ext.tmequant.TMEQuant
 * int n = TMEQuant.detectFibers(getCurrentImageData())          // whole image, tuned params
 * // or inside your collagen annotations:
 * // int n = TMEQuant.detectFibersInAnnotations(getCurrentImageData())
 * println "TME-Quant added ${n} fibres"
 * }</pre>
 *
 * <p>Parameters come from {@link FiberParams#fromPreferences()} (the last values you tuned,
 * including the threshold persisted on each commit); pass a {@code Map} to override a few
 * ({@code threshold}, {@code downsample}, {@code channel}, {@code minLength}, {@code createDetections},
 * {@code maskToObject}, {@code clearExisting}). Threshold is in NATIVE image units and assumes the
 * project's images are imaged similarly (the same assumption the batch run makes); each image is
 * normalized on its own region.</p>
 */
public final class TMEQuant {

    private static final Logger logger = LoggerFactory.getLogger(TMEQuant.class);

    private TMEQuant() {}

    // --- Public entry points ----------------------------------------------------------------------

    /** Detect fibres across the WHOLE image using the last-tuned parameters. Returns the count. */
    public static int detectFibers(ImageData<BufferedImage> imageData) {
        return detectFibers(imageData, null);
    }

    /** As {@link #detectFibers(ImageData)}, with a few parameter overrides (see class docs). */
    public static int detectFibers(ImageData<BufferedImage> imageData, Map<String, Object> overrides) {
        require(imageData);
        FiberParams p = FiberParams.fromPreferences().withOverrides(overrides);
        ImageServer<BufferedImage> server = imageData.getServer();
        List<ROI> regions = List.of(wholeImageRoi(server));
        return runOnRegions(imageData, p, regions, defaultLog());
    }

    /** Detect fibres inside EVERY area annotation (any class). Skips if there are none. */
    public static int detectFibersInAnnotations(ImageData<BufferedImage> imageData) {
        return detectFibersInAnnotations(imageData, (Set<PathClass>) null);
    }

    /**
     * Detect fibres inside area annotations of the given classes (null/empty = any class). Skips
     * images with no matching annotation (logs and returns 0).
     */
    public static int detectFibersInAnnotations(ImageData<BufferedImage> imageData,
            Set<PathClass> classes) {
        require(imageData);
        FiberParams p = FiberParams.fromPreferences();
        List<PathObject> targets = areaAnnotations(imageData.getHierarchy(),
                classes == null || classes.isEmpty() ? null : classes);
        if (targets.isEmpty()) {
            logger.info("TME-Quant: no matching area annotations — nothing to do.");
            return 0;
        }
        return runOnRegions(imageData, p, roisOf(targets), defaultLog());
    }

    /** Detect fibres inside the currently-selected AREA objects. Returns 0 if none are selected. */
    public static int detectFibersInSelected(ImageData<BufferedImage> imageData) {
        require(imageData);
        FiberParams p = FiberParams.fromPreferences();
        List<ROI> regions = new ArrayList<>();
        for (PathObject o : imageData.getHierarchy().getSelectionModel().getSelectedObjects()) {
            if (o.getROI() != null && o.getROI().isArea()) {
                regions.add(o.getROI());
            }
        }
        if (regions.isEmpty()) {
            logger.info("TME-Quant: no AREA object selected — nothing to do.");
            return 0;
        }
        return runOnRegions(imageData, p, regions, defaultLog());
    }

    // --- Core ------------------------------------------------------------------------------------

    private static int runOnRegions(ImageData<BufferedImage> imageData, FiberParams p,
            List<ROI> regions, Consumer<String> log) {
        ImageServer<BufferedImage> server = imageData.getServer();
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        double[] cal = calibrationOf(server);
        boolean calibrated = cal[0] != 0;
        double pix = cal[1];
        String host = FiberSocketPreferences.getHost();
        int port = FiberSocketPreferences.getPort();
        if (p.threshold <= 0) {
            logger.warn("TME-Quant: background threshold is unset (0) — tune it once in the Analyze "
                    + "dialog (it persists on 'Add to image'), or pass a 'threshold' override. "
                    + "Detecting with threshold 0 will treat almost everything as foreground.");
        }
        PathClass fiberClass = PathClass.fromString("Fiber", ColorTools.packRGB(0, 220, 0));
        int total = 0;
        try {
            for (ROI roi : regions) {
                int rx = Math.max(0, (int) Math.floor(roi.getBoundsX()));
                int ry = Math.max(0, (int) Math.floor(roi.getBoundsY()));
                int rw = Math.min(server.getWidth() - rx, (int) Math.ceil(roi.getBoundsWidth()));
                int rh = Math.min(server.getHeight() - ry, (int) Math.ceil(roi.getBoundsHeight()));
                rw = Math.max(1, rw);
                rh = Math.max(1, rh);
                ROI restrict = (p.maskToObject && roi.isArea()) ? roi : null;

                double[] norm = FiberEngine.computeRegionNorm(server, p.channel, rx, ry, rw, rh,
                        p.downsample);
                FiberEngine.AnalyzeResult res = FiberEngine.analyzeRegion(host, port, server,
                        p.channel, rx, ry, rw, rh, p, calibrated, pix, norm[0], norm[1], restrict, log);

                List<PathObject> objects = FiberObjectBuilder.build(res.fibers, rx, ry, p.downsample,
                        pix, roi.getImagePlane(), p.createDetections, fiberClass, restrict,
                        p.minLengthUnit);
                if (p.clearExisting) {
                    removeExistingFiberObjects(hierarchy, rx, ry, rw, rh);
                }
                if (!objects.isEmpty()) {
                    hierarchy.addObjects(objects);
                }
                total += objects.size();
                logger.info("TME-Quant: region ({},{}) {}x{} -> {} {}", rx, ry, rw, rh, objects.size(),
                        p.createDetections ? "detections" : "annotations");
            }
        } catch (Exception ex) {
            throw new RuntimeException("TME-Quant fibre detection failed: " + ex.getMessage()
                    + " — is the FIRE server running? (Extensions ▸ TME Quant ▸ Ping fiber server)",
                    ex);
        }
        return total;
    }

    // --- Helpers (copied from FiberAnalysisDialog) ------------------------------------------------

    private static ROI wholeImageRoi(ImageServer<BufferedImage> srv) {
        return ROIs.createRectangleROI(
                0, 0, srv.getWidth(), srv.getHeight(), ImagePlane.getDefaultPlane());
    }

    /** {calibratedFlag(1/0), pixelSizeMicrons} for a server. */
    private static double[] calibrationOf(ImageServer<BufferedImage> srv) {
        PixelCalibration cal = srv.getPixelCalibration();
        double avg = (cal != null && cal.hasPixelSizeMicrons())
                ? cal.getAveragedPixelSizeMicrons() : Double.NaN;
        boolean calibrated = Double.isFinite(avg) && avg > 0;
        return new double[] {calibrated ? 1 : 0, calibrated ? avg : Double.NaN};
    }

    /** Area annotations, optionally filtered by class (null = any class, incl. unclassified). */
    private static List<PathObject> areaAnnotations(PathObjectHierarchy h, Set<PathClass> classes) {
        List<PathObject> out = new ArrayList<>();
        for (PathObject o : h.getAnnotationObjects()) {
            ROI r = o.getROI();
            if (r != null && r.isArea() && (classes == null || classes.contains(o.getPathClass()))) {
                out.add(o);
            }
        }
        return out;
    }

    private static List<ROI> roisOf(List<PathObject> objs) {
        List<ROI> out = new ArrayList<>(objs.size());
        for (PathObject o : objs) {
            if (o.getROI() != null) {
                out.add(o.getROI());
            }
        }
        return out;
    }

    /** Remove prior Fiber/TACS objects whose centroid falls in the region, from a hierarchy. */
    private static int removeExistingFiberObjects(PathObjectHierarchy hier, int x, int y, int w,
            int h) {
        Set<String> targetClasses = Set.of("Fiber", "TACS-1", "TACS-2", "TACS-3");
        List<PathObject> toRemove = new ArrayList<>();
        List<PathObject> candidates = new ArrayList<>();
        candidates.addAll(hier.getAnnotationObjects());
        candidates.addAll(hier.getDetectionObjects());
        for (PathObject o : candidates) {
            PathClass pc = o.getPathClass();
            if (pc == null || !targetClasses.contains(pc.toString()) || !o.hasROI()) {
                continue;
            }
            ROI r = o.getROI();
            double cx = r.getCentroidX();
            double cy = r.getCentroidY();
            if (cx >= x && cx < x + w && cy >= y && cy < y + h) {
                toRemove.add(o);
            }
        }
        if (!toRemove.isEmpty()) {
            hier.removeObjects(toRemove, false);
        }
        return toRemove.size();
    }

    private static Consumer<String> defaultLog() {
        return logger::info;
    }

    private static void require(ImageData<BufferedImage> imageData) {
        if (imageData == null || imageData.getServer() == null) {
            throw new IllegalArgumentException("TME-Quant: no image (imageData/server is null).");
        }
    }
}
