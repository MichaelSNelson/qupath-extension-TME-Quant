package qupath.ext.tmequant;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

/**
 * A set of "don't trace fibres here" regions, in full-image pixel coordinates, that can be
 * rasterised into a per-tile mask for the FIRE server.
 *
 * <p>The motivating case is nuclei: a paired brightfield image of the same field can be segmented
 * in QuPath, and those nuclei are strongly birefringent, so FIRE traces them as short arcs and
 * "circles" that are not collagen. The same mechanism takes any regions the user wants left
 * alone — blob detections, debris annotations, a hand-drawn region.</p>
 *
 * <p>Two sources, both ending up as the same flat list of polygons:</p>
 * <ul>
 *   <li>{@link #fromObjects} — objects already in a QuPath hierarchy (the common case: no export
 *       and re-import needed).</li>
 *   <li>{@link #fromGeoJson} — a GeoJSON file exported from QuPath (or anywhere else), for objects
 *       that live in a <em>different</em> image, e.g. nuclei segmented on the brightfield partner
 *       of a birefringence image.</li>
 * </ul>
 *
 * <p>The GeoJSON reader is <b>streaming</b>: a real cell-detection export runs to hundreds of MB
 * (123k cells ≈ 350 MB) but only a few percent of that is geometry, the rest measurements. Parsing
 * it into a DOM, or importing it as QuPath objects, is far more expensive than the masking it
 * feeds. Streaming the whole slide takes a couple of seconds and only the polygons are kept.</p>
 *
 * <p>Geometry is stored as compact {@code float[]} coordinate runs plus a bounds array rather than
 * {@code Path2D} objects, so a whole slide of nuclei costs ~15 MB instead of several times that.
 * Rasterising a tile scans the bounds (cheap, ~100k comparisons) and only builds paths for the
 * polygons that actually touch it.</p>
 */
public final class ExclusionMask {

    private static final Logger logger = LoggerFactory.getLogger(ExclusionMask.class);

    /** Which ROI of a cell-like object defines the excluded area. */
    public enum RoiChoice {
        /** The nucleus if the object has one, else the whole object. Right for birefringent nuclei. */
        NUCLEUS,
        /** The object's own ROI (the cell boundary for cell detections). */
        WHOLE
    }

    /** Flattened polygon rings, {@code [x0,y0,x1,y1,...]} in FULL-image pixels. */
    private final List<float[]> polys;
    /** Per-polygon bounds, 4 entries each: minX, minY, maxX, maxY. Parallel to {@link #polys}. */
    private final float[] bounds;
    private final String describeSource;

    private ExclusionMask(List<float[]> polys, String describeSource) {
        this.polys = polys;
        this.describeSource = describeSource;
        this.bounds = new float[polys.size() * 4];
        for (int i = 0; i < polys.size(); i++) {
            float[] p = polys.get(i);
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int k = 0; k + 1 < p.length; k += 2) {
                minX = Math.min(minX, p[k]);
                maxX = Math.max(maxX, p[k]);
                minY = Math.min(minY, p[k + 1]);
                maxY = Math.max(maxY, p[k + 1]);
            }
            bounds[i * 4] = minX;
            bounds[i * 4 + 1] = minY;
            bounds[i * 4 + 2] = maxX;
            bounds[i * 4 + 3] = maxY;
        }
    }

    /** How many excluded regions this mask holds. */
    public int size() {
        return polys.size();
    }

    public boolean isEmpty() {
        return polys.isEmpty();
    }

    /** Short human description for the dialog ("123,793 nuclei from …"). */
    public String describe() {
        return describeSource;
    }

    /** The full extent covered by the regions, as {minX, minY, maxX, maxY}; null when empty. */
    public double[] extent() {
        if (polys.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < polys.size(); i++) {
            minX = Math.min(minX, bounds[i * 4]);
            minY = Math.min(minY, bounds[i * 4 + 1]);
            maxX = Math.max(maxX, bounds[i * 4 + 2]);
            maxY = Math.max(maxY, bounds[i * 4 + 3]);
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    // ------------------------------------------------------------------ sources

    /**
     * Build from objects already in a hierarchy — the common case, since it needs no export and
     * re-import. Objects are read in the image's own full-resolution pixel coordinates.
     *
     * @param objects the candidate objects (typically {@code hierarchy.getDetectionObjects()})
     * @param filter keep-test applied to each object (class filter, or {@code o -> true})
     * @param roiChoice which ROI of each object to exclude
     */
    public static ExclusionMask fromObjects(
            Collection<? extends PathObject> objects, Predicate<PathObject> filter, RoiChoice roiChoice) {
        List<float[]> out = new ArrayList<>();
        int skipped = 0;
        for (PathObject o : objects) {
            if (filter != null && !filter.test(o)) {
                continue;
            }
            ROI roi = null;
            if (roiChoice == RoiChoice.NUCLEUS) {
                // Cell objects carry a separate nucleus ROI; anything else just uses its own.
                if (o instanceof qupath.lib.objects.PathCellObject cell) {
                    roi = cell.getNucleusROI();
                }
            }
            if (roi == null) {
                roi = o.getROI();
            }
            if (roi == null || roi.isEmpty() || roi.isLine() || roi.isPoint()) {
                skipped++;
                continue;
            }
            addShape(out, roi.getShape());
        }
        if (skipped > 0) {
            logger.debug("Exclusion source: skipped {} object(s) without a usable area ROI", skipped);
        }
        return new ExclusionMask(out, String.format(
                "%,d region%s from the current image", out.size(), out.size() == 1 ? "" : "s"));
    }

    /**
     * Stream a GeoJSON FeatureCollection and keep only the geometry.
     *
     * <p>Handles QuPath's cell-detection export, where each feature carries both {@code geometry}
     * (the cell boundary) and {@code nucleusGeometry} (the nucleus). Measurements — the bulk of the
     * file — are skipped without being parsed.</p>
     *
     * @param file the .geojson / .json file
     * @param objectTypeFilter keep only features whose {@code properties.objectType} equals this
     *     (e.g. {@code "cell"}); null or blank keeps every feature
     * @param roiChoice NUCLEUS prefers {@code nucleusGeometry} when the feature has one
     */
    public static ExclusionMask fromGeoJson(Path file, String objectTypeFilter, RoiChoice roiChoice)
            throws IOException {
        List<float[]> out = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        int features = 0;
        boolean wantNucleus = roiChoice == RoiChoice.NUCLEUS;
        String want = objectTypeFilter == null || objectTypeFilter.isBlank()
                ? null : objectTypeFilter.trim();

        try (Reader r = new BufferedReader(
                Files.newBufferedReader(file, StandardCharsets.UTF_8), 1 << 20);
                JsonReader in = new JsonReader(r)) {
            // Tolerate either a bare feature array or a FeatureCollection wrapper.
            JsonToken first = in.peek();
            if (first == JsonToken.BEGIN_OBJECT) {
                in.beginObject();
                boolean sawFeatures = false;
                while (in.hasNext()) {
                    if ("features".equals(in.nextName())) {
                        sawFeatures = true;
                        features += readFeatureArray(in, out, want, wantNucleus);
                    } else {
                        in.skipValue();
                    }
                }
                in.endObject();
                if (!sawFeatures) {
                    throw new IOException("No \"features\" array found — is this a GeoJSON "
                            + "FeatureCollection exported from QuPath?");
                }
            } else if (first == JsonToken.BEGIN_ARRAY) {
                features += readFeatureArray(in, out, want, wantNucleus);
            } else {
                throw new IOException("Unexpected content at the start of " + file.getFileName());
            }
        }
        long ms = System.currentTimeMillis() - t0;
        logger.info("Exclusion GeoJSON: read {} feature(s) -> {} region(s) from {} in {} ms",
                features, out.size(), file.getFileName(), ms);
        return new ExclusionMask(out, String.format("%,d region%s from %s",
                out.size(), out.size() == 1 ? "" : "s", file.getFileName()));
    }

    /** Reads a features array, appending each kept feature's geometry. Returns features seen. */
    private static int readFeatureArray(
            JsonReader in, List<float[]> out, String want, boolean wantNucleus) throws IOException {
        int seen = 0;
        in.beginArray();
        while (in.hasNext()) {
            seen++;
            // Per feature we need at most three things, and they can arrive in any order, so
            // buffer the two geometries and decide once the objectType is known.
            List<float[]> geom = new ArrayList<>(2);
            List<float[]> nucleus = new ArrayList<>(2);
            String objectType = null;
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                switch (name) {
                    case "geometry" -> readGeometry(in, geom);
                    case "nucleusGeometry" -> readGeometry(in, nucleus);
                    case "properties" -> objectType = readObjectType(in);
                    default -> in.skipValue();
                }
            }
            in.endObject();
            if (want != null && !want.equalsIgnoreCase(objectType)) {
                continue;
            }
            List<float[]> use = (wantNucleus && !nucleus.isEmpty()) ? nucleus : geom;
            out.addAll(use);
        }
        in.endArray();
        return seen;
    }

    /** Pull {@code objectType} out of a properties object, skipping the measurements bulk. */
    private static String readObjectType(JsonReader in) throws IOException {
        if (in.peek() != JsonToken.BEGIN_OBJECT) {
            in.skipValue();
            return null;
        }
        String type = null;
        in.beginObject();
        while (in.hasNext()) {
            if ("objectType".equals(in.nextName())) {
                type = in.nextString();
            } else {
                in.skipValue();
            }
        }
        in.endObject();
        return type;
    }

    /**
     * Read a GeoJSON geometry, appending each exterior ring as a flat coordinate run. Interior
     * rings (holes) are ignored: a hole in an excluded region only makes the exclusion smaller,
     * and nuclei do not have them.
     */
    private static void readGeometry(JsonReader in, List<float[]> out) throws IOException {
        if (in.peek() != JsonToken.BEGIN_OBJECT) {
            in.skipValue();
            return;
        }
        String type = null;
        // coordinates may precede "type" in the stream, so keep the raw nesting depth and
        // re-interpret once both are known. In practice QuPath writes "type" first.
        List<float[]> rings = new ArrayList<>();
        in.beginObject();
        while (in.hasNext()) {
            String name = in.nextName();
            if ("type".equals(name)) {
                type = in.nextString();
            } else if ("coordinates".equals(name)) {
                collectRings(in, rings);
            } else {
                in.skipValue();
            }
        }
        in.endObject();
        if (type != null && type.toLowerCase().contains("point")) {
            return; // a point has no area to exclude
        }
        out.addAll(rings);
    }

    /**
     * Collect every ring of coordinates found at any nesting depth. Polygon, MultiPolygon and
     * LineString all reduce to "a list of coordinate lists" for masking purposes, so rather than
     * branching per geometry type we descend until we find arrays of numbers.
     */
    private static void collectRings(JsonReader in, List<float[]> rings) throws IOException {
        if (in.peek() != JsonToken.BEGIN_ARRAY) {
            in.skipValue();
            return;
        }
        in.beginArray();
        collectRingsOpen(in, rings);
        in.endArray();
    }

    /**
     * Collect rings from an array that has ALREADY been opened. Recurses through whatever nesting
     * the geometry type implies — Polygon is {@code [ring…]}, MultiPolygon {@code [[ring…]…]} — so
     * the caller never has to branch on {@code type}. Coordinate pairs found at a level are
     * gathered into one ring; anything deeper recurses.
     */
    private static void collectRingsOpen(JsonReader in, List<float[]> rings) throws IOException {
        List<Float> flat = new ArrayList<>();
        while (in.hasNext()) {
            if (in.peek() != JsonToken.BEGIN_ARRAY) {
                in.skipValue(); // a bare [x, y] Point, or something unexpected
                continue;
            }
            in.beginArray();
            if (in.peek() == JsonToken.NUMBER) {
                float x = (float) in.nextDouble();
                float y = (float) in.nextDouble();
                while (in.hasNext()) {
                    in.skipValue(); // ignore any z / m
                }
                flat.add(x);
                flat.add(y);
            } else {
                collectRingsOpen(in, rings);
            }
            in.endArray();
        }
        if (flat.size() >= 6) {
            float[] arr = new float[flat.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = flat.get(i);
            }
            rings.add(arr);
        }
    }

    private static void addShape(List<float[]> out, java.awt.Shape shape) {
        if (shape == null) {
            return;
        }
        java.awt.geom.PathIterator it = shape.getPathIterator(null, 0.5); // flatten curves
        List<Float> flat = new ArrayList<>();
        double[] c = new double[6];
        while (!it.isDone()) {
            int seg = it.currentSegment(c);
            if (seg == java.awt.geom.PathIterator.SEG_CLOSE) {
                if (flat.size() >= 6) {
                    float[] arr = new float[flat.size()];
                    for (int i = 0; i < arr.length; i++) {
                        arr[i] = flat.get(i);
                    }
                    out.add(arr);
                }
                flat.clear();
            } else {
                flat.add((float) c[0]);
                flat.add((float) c[1]);
            }
            it.next();
        }
        if (flat.size() >= 6) {
            float[] arr = new float[flat.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = flat.get(i);
            }
            out.add(arr);
        }
    }

    // ------------------------------------------------------------------ rasterising

    /**
     * Rasterise the regions overlapping a tile into a binary mask sized {@code outW x outH}.
     *
     * <p>White (255) = excluded. Full-image coordinates map to tile-local pixels as
     * {@code local = (full - origin) / downsample}, matching {@link MaskRasterizer}.</p>
     *
     * @param dilatePx how far to grow each region, in ANALYSED (downsampled) pixels. Segmentation
     *     outlines often sit just inside the bright object they mark, so a small dilation catches
     *     the halo. 0 for none.
     * @return the mask, or null when no region touches this tile (so the caller can skip sending it)
     */
    public BufferedImage rasterise(
            int regionX, int regionY, double downsample, int outW, int outH, double dilatePx) {
        double x0 = regionX;
        double y0 = regionY;
        double x1 = regionX + outW * downsample;
        double y1 = regionY + outH * downsample;
        double padFull = Math.max(0, dilatePx) * downsample;

        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < polys.size(); i++) {
            if (bounds[i * 4 + 2] + padFull < x0 || bounds[i * 4] - padFull > x1
                    || bounds[i * 4 + 3] + padFull < y0 || bounds[i * 4 + 1] - padFull > y1) {
                continue;
            }
            hits.add(i);
        }
        if (hits.isEmpty()) {
            return null;
        }

        BufferedImage mask = new BufferedImage(outW, outH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = mask.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, outW, outH);
            // Crisp binary edges — the server thresholds at 127, and anti-aliased greys would make
            // the mask edge depend on which side of that a partially covered pixel landed.
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Color.WHITE);
            double s = 1.0 / downsample;
            // Dilation is done by ALSO stroking the outline with a pen of that width, which grows
            // the filled shape by half the pen on each side — cheap and good enough at these sizes.
            java.awt.Stroke stroke = dilatePx > 0
                    ? new BasicStroke((float) (dilatePx * 2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    : null;
            for (int idx : hits) {
                float[] p = polys.get(idx);
                Path2D.Float path = new Path2D.Float();
                path.moveTo((p[0] - regionX) * s, (p[1] - regionY) * s);
                for (int k = 2; k + 1 < p.length; k += 2) {
                    path.lineTo((p[k] - regionX) * s, (p[k + 1] - regionY) * s);
                }
                path.closePath();
                g.fill(path);
                if (stroke != null) {
                    g.setStroke(stroke);
                    g.draw(path);
                }
            }
        } finally {
            g.dispose();
        }
        return mask;
    }

    /** Distinct {@code objectType} values in a GeoJSON file, read from a bounded prefix. */
    public static List<String> peekObjectTypes(Path file, int maxFeatures) throws IOException {
        List<String> types = new ArrayList<>();
        try (Reader r = new BufferedReader(
                Files.newBufferedReader(file, StandardCharsets.UTF_8), 1 << 20);
                JsonReader in = new JsonReader(r)) {
            if (in.peek() != JsonToken.BEGIN_OBJECT) {
                return types;
            }
            in.beginObject();
            while (in.hasNext()) {
                if (!"features".equals(in.nextName())) {
                    in.skipValue();
                    continue;
                }
                in.beginArray();
                int n = 0;
                while (in.hasNext() && n++ < maxFeatures) {
                    in.beginObject();
                    while (in.hasNext()) {
                        if ("properties".equals(in.nextName())) {
                            String t = readObjectType(in);
                            if (t != null && !types.contains(t)) {
                                types.add(t);
                            }
                        } else {
                            in.skipValue();
                        }
                    }
                    in.endObject();
                }
                return types; // stop early; no need to drain a 350 MB file
            }
            in.endObject();
        }
        return types;
    }
}
