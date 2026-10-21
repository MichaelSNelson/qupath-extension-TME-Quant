package qupath.ext.tmequant;

import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * An FX-free snapshot of the frozen TME-Quant tuning parameters, plus the exact parameter math the
 * interactive dialog uses to turn them into the FIRE request meta. This is the single description of
 * the knobs a headless run ({@link TMEQuant} / {@link FiberEngine}) needs, so a scripted run
 * reproduces what the dialog would send.
 *
 * <p>The conversion/threshold formulas here are copied verbatim from
 * {@code FiberAnalysisDialog} ({@code toAnalysedPx}, the {@code *AnalysedPx} clamps,
 * {@code extToCos}/{@code linkaToCos}, {@code serverNormHi}/{@code serverThreshIm2}, and
 * {@code buildMeta}/{@code buildPostMeta}). If the dialog's formulas ever change, update them here
 * too (a future refactor should have the dialog delegate to this class to remove the duplication).</p>
 *
 * <p>Treated as immutable: construct via {@link #fromPreferences(boolean)} and derive with
 * {@link #withOverrides(Map)}; fields are not mutated after creation.</p>
 *
 * <p>Field names carry their unit: {@code Native} = the image's own intensity units;
 * {@code UserUnit} = microns on a calibrated image, full-resolution pixels on an uncalibrated one
 * (the unit the dialog shows); {@code AnalysedPx} = pixels of the image FIRE receives, i.e. at the
 * analysis downsample; {@code Vertices} = a count of trace vertices; {@code Deg} = degrees. A name
 * with no unit is a plain number (a ratio, a fraction, a FIRE weight).</p>
 */
public final class FiberParams {

    private static final Gson GSON =
            new GsonBuilder().serializeSpecialFloatingPointValues().create();

    /** Ceiling for the FIRE 0-255 threshold in the SENT image (see FiberAnalysisDialog). */
    private static final double MAX_SERVER_IM2 = 155.0;

    // Frozen tuning knobs, each in the unit its name gives (see the class comment).
    public int channel;
    public double downsample;
    public double thresholdNative;
    public int fiberMode;         // 1 = Segments, else 2 = Merged
    public double smoothingSigmaAnalysedPx;      // sigma_im
    public double distSmoothingSigmaAnalysedPx;  // sigma_d
    public double seedSpacingUserUnit;
    public double seedSensitivity;
    public double xlinkBoxUserUnit;
    public double linkDistanceUserUnit;
    public double linkAngleDeg;
    public double bendAngleDeg;
    public double angleExtendDeg;
    public double maxWidthUserUnit;
    public double dxlink;
    public double dirDecay;
    public double fiberDirVertices;    // s_fiberdir: how many vertices back the heading is taken from
    public double fireFlenAnalysedPx;  // thresh_flen
    public double maxSpaceAnalysedPx;  // s_maxspace
    public double angIntervalDeg;
    public double lambda;
    public double dangLenAnalysedPx;   // thresh_dang_L
    public double minLengthUserUnit;
    public double tacsZoneUserUnit;
    public String extraJson;      // free-form JSON object string (may be blank)
    public boolean createDetections;
    public boolean maskToObject;
    public boolean clearExisting;

    public FiberParams() {}

    /** Copy constructor (used by {@link #withOverrides}). */
    public FiberParams(FiberParams o) {
        this.channel = o.channel;
        this.downsample = o.downsample;
        this.thresholdNative = o.thresholdNative;
        this.fiberMode = o.fiberMode;
        this.smoothingSigmaAnalysedPx = o.smoothingSigmaAnalysedPx;
        this.distSmoothingSigmaAnalysedPx = o.distSmoothingSigmaAnalysedPx;
        this.seedSpacingUserUnit = o.seedSpacingUserUnit;
        this.seedSensitivity = o.seedSensitivity;
        this.xlinkBoxUserUnit = o.xlinkBoxUserUnit;
        this.linkDistanceUserUnit = o.linkDistanceUserUnit;
        this.linkAngleDeg = o.linkAngleDeg;
        this.bendAngleDeg = o.bendAngleDeg;
        this.angleExtendDeg = o.angleExtendDeg;
        this.maxWidthUserUnit = o.maxWidthUserUnit;
        this.dxlink = o.dxlink;
        this.dirDecay = o.dirDecay;
        this.fiberDirVertices = o.fiberDirVertices;
        this.fireFlenAnalysedPx = o.fireFlenAnalysedPx;
        this.maxSpaceAnalysedPx = o.maxSpaceAnalysedPx;
        this.angIntervalDeg = o.angIntervalDeg;
        this.lambda = o.lambda;
        this.dangLenAnalysedPx = o.dangLenAnalysedPx;
        this.minLengthUserUnit = o.minLengthUserUnit;
        this.tacsZoneUserUnit = o.tacsZoneUserUnit;
        this.extraJson = o.extraJson;
        this.createDetections = o.createDetections;
        this.maskToObject = o.maskToObject;
        this.clearExisting = o.clearExisting;
    }

    /**
     * Snapshot the persistent preferences the dialog writes as it is tuned, for an image with
     * ({@code calibrated}) or without a pixel size: distances are stored once per unit, and this
     * picks the set that matches. The threshold comes from {@code tmequant.threshold}, which the
     * dialog persists on each commit, so a script default matches the last value tuned
     * interactively.
     */
    public static FiberParams fromPreferences(boolean calibrated) {
        FiberParams p = new FiberParams();
        p.channel = FiberSocketPreferences.getCollagenChannel();
        p.downsample = FiberSocketPreferences.downsampleProperty().get();
        p.thresholdNative = FiberSocketPreferences.getThreshold();
        p.fiberMode = FiberSocketPreferences.getFiberMode() == 1 ? 1 : 2;
        p.smoothingSigmaAnalysedPx = FiberSocketPreferences.smoothingProperty().get();
        p.distSmoothingSigmaAnalysedPx = FiberSocketPreferences.distSmoothingProperty().get();
        p.seedSpacingUserUnit = FiberSocketPreferences.seedSpacingProperty().forUnit(calibrated).get();
        p.seedSensitivity = FiberSocketPreferences.seedSensitivityProperty().get();
        p.xlinkBoxUserUnit = FiberSocketPreferences.xlinkBoxProperty().forUnit(calibrated).get();
        p.linkDistanceUserUnit = FiberSocketPreferences.linkDistanceProperty().forUnit(calibrated).get();
        p.linkAngleDeg = FiberSocketPreferences.linkAngleProperty().get();
        p.bendAngleDeg = FiberSocketPreferences.bendAngleProperty().get();
        p.angleExtendDeg = FiberSocketPreferences.angleExtendProperty().get();
        p.maxWidthUserUnit = FiberSocketPreferences.maxWidthProperty().forUnit(calibrated).get();
        p.dxlink = FiberSocketPreferences.dxlinkProperty().get();
        p.dirDecay = FiberSocketPreferences.dirDecayProperty().get();
        p.fiberDirVertices = FiberSocketPreferences.fiberDirVerticesProperty().get();
        p.fireFlenAnalysedPx = FiberSocketPreferences.fireFlenAnalysedPxProperty().get();
        p.maxSpaceAnalysedPx = FiberSocketPreferences.maxSpaceAnalysedPxProperty().get();
        p.angIntervalDeg = FiberSocketPreferences.angIntervalProperty().get();
        p.lambda = FiberSocketPreferences.lambdaProperty().get();
        p.dangLenAnalysedPx = FiberSocketPreferences.dangLenAnalysedPxProperty().get();
        p.minLengthUserUnit = FiberSocketPreferences.minLengthProperty().forUnit(calibrated).get();
        p.tacsZoneUserUnit = FiberSocketPreferences.tacsZoneProperty().forUnit(calibrated).get();
        p.extraJson = FiberSocketPreferences.extraParamsProperty().get();
        p.createDetections = FiberSocketPreferences.getCreateDetections();
        p.maskToObject = FiberSocketPreferences.getMaskToObject();
        p.clearExisting = FiberSocketPreferences.getClearExisting();
        return p;
    }

    /**
     * A copy with a few common fields overridden from a script map (unknown keys are ignored):
     * {@code threshold}, {@code downsample}, {@code channel}, {@code minLength},
     * {@code createDetections}, {@code maskToObject}, {@code clearExisting}.
     */
    public FiberParams withOverrides(Map<String, Object> overrides) {
        FiberParams p = new FiberParams(this);
        if (overrides == null) {
            return p;
        }
        if (overrides.containsKey("threshold")) {
            p.thresholdNative = asDouble(overrides.get("threshold"), p.thresholdNative);
        }
        if (overrides.containsKey("downsample")) {
            p.downsample = asDouble(overrides.get("downsample"), p.downsample);
        }
        if (overrides.containsKey("channel")) {
            p.channel = (int) asDouble(overrides.get("channel"), p.channel);
        }
        if (overrides.containsKey("minLength")) {
            p.minLengthUserUnit = asDouble(overrides.get("minLength"), p.minLengthUserUnit);
        }
        if (overrides.containsKey("createDetections")) {
            p.createDetections = asBoolean(overrides.get("createDetections"), p.createDetections);
        }
        if (overrides.containsKey("maskToObject")) {
            p.maskToObject = asBoolean(overrides.get("maskToObject"), p.maskToObject);
        }
        if (overrides.containsKey("clearExisting")) {
            p.clearExisting = asBoolean(overrides.get("clearExisting"), p.clearExisting);
        }
        return p;
    }

    private static double asDouble(Object v, double dflt) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return dflt;
    }

    private static boolean asBoolean(Object v, boolean dflt) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v != null) {
            return Boolean.parseBoolean(v.toString().trim());
        }
        return dflt;
    }

    // --- Parameter math (verbatim from FiberAnalysisDialog) ---------------------------------------

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static double toAnalysedPx(double userUnitValue, double downsample, boolean calibrated,
            double pixelSizeMicrons) {
        double fullResPx = calibrated ? userUnitValue / pixelSizeMicrons : userUnitValue;
        return fullResPx / downsample;
    }

    /** s_fiberdir as FIRE takes it: a whole number of vertices, at least 2 (as the C++ clamps). */
    static int fiberDirVerticesForFire(double vertices) {
        return Math.max(2, (int) Math.round(vertices));
    }

    static double extToCos(double angleDeg) {
        return Math.cos(Math.toRadians(Math.max(0, Math.min(89.0, angleDeg))));
    }

    static double linkaToCos(double angleDeg) {
        return -Math.cos(Math.toRadians(Math.max(0, Math.min(89.0, angleDeg))));
    }

    /** White point for the image SENT to FIRE, floored so thresh_im2 never saturates. */
    static double serverNormHi(double thresholdNative, double lo, double hi) {
        return serverNormHi(thresholdNative, lo, hi, 0.0);
    }

    /**
     * As the dialog's {@code serverNormHi}: the white point also covers {@code collagenLevelNative}, the
     * 99.9th percentile of the region's above-threshold pixels (see
     * {@link FiberEngine#brightCollagenLevel}), so bright bundles keep their internal gradient instead
     * of clipping to 255. Without it the headless path sent a lower white point and therefore a
     * HIGHER thresh_im2 than the dialog for the same native threshold (103 vs 68 on one region).
     */
    static double serverNormHi(double thresholdNative, double lo, double hi, double collagenLevelNative) {
        double floor = lo + (thresholdNative - lo) * (255.0 / MAX_SERVER_IM2);
        return Math.max(hi, Math.max(floor, collagenLevelNative));
    }

    /** thresh_im2 for the SENT image, using the floored white point (so it never saturates). */
    static int serverThreshIm2(double thresholdNative, double lo, double hi) {
        return serverThreshIm2(thresholdNative, lo, hi, 0.0);
    }

    static int serverThreshIm2(double thresholdNative, double lo, double hi, double collagenLevelNative) {
        double eh = serverNormHi(thresholdNative, lo, hi, collagenLevelNative);
        double range = eh > lo ? eh - lo : 1.0;
        return clamp((int) Math.round((thresholdNative - lo) / range * 255.0), 0, 255);
    }

    /** White point for gray conversion (same as the dialog's {@code serverNormHi()}). */
    public double serverNormHi(double normLoNative, double normHiNative) {
        return serverNormHi(thresholdNative, normLoNative, normHiNative, 0.0);
    }

    /** As {@link #serverNormHi(double, double)}, covering the region's bright collagen too. */
    public double whitePoint(double normLoNative, double normHiNative, double collagenLevelNative) {
        return serverNormHi(thresholdNative, normLoNative, normHiNative, collagenLevelNative);
    }

    /**
     * The FIRE detection meta JSON for a region, reproducing {@code FiberAnalysisDialog.buildMeta}
     * (has_mask is always false in the headless path — no TACS). {@code logDir}/{@code logFile} add
     * the server-transcript fields when non-null.
     */
    public String buildDetectMeta(int rx, int ry, int rw, int rh, double ds, boolean calibrated,
            double pixelSizeMicrons, double normLoNative, double normHiNative, String logDir, String logFile) {
        return buildDetectMeta(rx, ry, rw, rh, ds, calibrated, pixelSizeMicrons, normLoNative, normHiNative, 0.0, logDir, logFile);
    }

    /** As above, with the region's bright-collagen level in the white point, as the dialog does. */
    public String buildDetectMeta(int rx, int ry, int rw, int rh, double ds, boolean calibrated,
            double pixelSizeMicrons, double normLoNative, double normHiNative, double collagenLevelNative, String logDir,
            String logFile) {
        int threshIm2 = serverThreshIm2(thresholdNative, normLoNative, normHiNative, collagenLevelNative);
        int ll1AnalysedPx = Math.max(1,
                (int) Math.round(toAnalysedPx(minLengthUserUnit, ds, calibrated, pixelSizeMicrons)));
        double tacsZoneAnalysedPx = toAnalysedPx(tacsZoneUserUnit, ds, calibrated, pixelSizeMicrons);

        JsonObject overrides = new JsonObject();
        overrides.addProperty("thresh_im2", threshIm2);
        overrides.addProperty("LL1", ll1AnalysedPx);
        overrides.addProperty("thresh_LMPdist",
                Math.max(2, (int) Math.round(toAnalysedPx(seedSpacingUserUnit, ds, calibrated, pixelSizeMicrons))));
        overrides.addProperty("sigma_im", smoothingSigmaAnalysedPx);
        overrides.addProperty("sigma_d", distSmoothingSigmaAnalysedPx);
        overrides.addProperty("thresh_LMP", seedSensitivity);
        overrides.addProperty("s_xlinkbox",
                Math.max(1, (int) Math.round(toAnalysedPx(xlinkBoxUserUnit, ds, calibrated, pixelSizeMicrons))));
        overrides.addProperty("thresh_linkd", toAnalysedPx(linkDistanceUserUnit, ds, calibrated, pixelSizeMicrons));
        overrides.addProperty("thresh_ext", extToCos(bendAngleDeg));
        overrides.addProperty("thresh_dang_aextend", extToCos(angleExtendDeg));
        overrides.addProperty("thresh_linka", linkaToCos(linkAngleDeg));
        overrides.addProperty("widMAX", toAnalysedPx(maxWidthUserUnit, ds, calibrated, pixelSizeMicrons));
        overrides.addProperty("thresh_Dxlink", dxlink);
        overrides.addProperty("lam_dirdecay", dirDecay);
        // FIRE's own units, sent as typed (see FiberSocketPreferences).
        overrides.addProperty("s_fiberdir", fiberDirVerticesForFire(fiberDirVertices));
        overrides.addProperty("thresh_flen", fireFlenAnalysedPx);
        overrides.addProperty("s_maxspace", Math.max(1.0, maxSpaceAnalysedPx));
        overrides.addProperty("ang_interval", angIntervalDeg);
        overrides.addProperty("lambda", lambda);
        overrides.addProperty("thresh_dang_L", dangLenAnalysedPx);
        // Free-form extras win over the controls above.
        JsonObject extra = parseExtra();
        for (Map.Entry<String, JsonElement> e : extra.entrySet()) {
            overrides.add(e.getKey(), e.getValue());
        }

        JsonObject region = new JsonObject();
        region.addProperty("x", rx);
        region.addProperty("y", ry);
        region.addProperty("width", rw);
        region.addProperty("height", rh);
        region.addProperty("downsample", ds);

        JsonObject meta = new JsonObject();
        meta.add("region", region);
        meta.addProperty("fiber_mode", fiberMode);
        meta.addProperty("use_ct_reconstruction", false);
        meta.addProperty("has_mask", false);
        meta.addProperty("distance_threshold", tacsZoneAnalysedPx);
        meta.addProperty("prescaled", true);
        meta.add("overrides", overrides);
        if (logDir != null && logFile != null) {
            meta.addProperty("log_dir", logDir);
            meta.addProperty("log_file", logFile);
        }
        return GSON.toJson(meta);
    }

    /** The POSTPROC (stitch) meta, reproducing {@code FiberAnalysisDialog.buildPostMeta}. */
    public String buildPostMeta(List<FiberSocketClient.Fiber> fibers, double ds, boolean calibrated,
            double pixelSizeMicrons, boolean stitch, String logDir, String logFile) {
        JsonObject m = new JsonObject();
        m.add("fibers", GSON.toJsonTree(fibers));
        m.addProperty("stitch", stitch);
        m.addProperty("has_mask", false);
        m.addProperty("distance_threshold", toAnalysedPx(tacsZoneUserUnit, ds, calibrated, pixelSizeMicrons));
        if (logDir != null && logFile != null) {
            m.addProperty("log_dir", logDir);
            m.addProperty("log_file", logFile);
        }
        return GSON.toJson(m);
    }

    private JsonObject parseExtra() {
        String text = extraJson == null ? "" : extraJson.trim();
        if (text.isEmpty()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject(); // ignore malformed free-form extras in the headless path
        }
    }
}
