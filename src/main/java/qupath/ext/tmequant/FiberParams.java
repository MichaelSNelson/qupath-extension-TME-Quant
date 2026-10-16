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
 * <p>Treated as immutable: construct via {@link #fromPreferences()} and derive with
 * {@link #withOverrides(Map)}; fields are not mutated after creation.</p>
 */
public final class FiberParams {

    private static final Gson GSON =
            new GsonBuilder().serializeSpecialFloatingPointValues().create();

    /** Ceiling for the FIRE 0-255 threshold in the SENT image (see FiberAnalysisDialog). */
    private static final double MAX_SERVER_IM2 = 155.0;

    // Frozen tuning knobs (raw UI units: native threshold, µm/px distances, degrees, etc.).
    public int channel;
    public double downsample;
    public double threshold;      // native units
    public int fiberMode;         // 1 = Segments, else 2 = Merged
    public double smoothing;      // sigma_im
    public double distSmoothing;  // sigma_d
    public double seedSpacingUm;
    public double seedSensitivity;
    public double xlinkBoxUm;
    public double linkDistanceUm;
    public double linkAngle;      // degrees
    public double bendAngle;      // degrees
    public double angleExtend;    // degrees
    public double maxWidthUm;
    public double dxlink;
    public double dirDecay;
    public double fiberDirUm;
    public double fireFlenUm;
    public double maxSpaceUm;
    public double angInterval;
    public double lambda;
    public double dangLenUm;
    public double minLengthUnit;
    public double tacsZoneUm;
    public String extraJson;      // free-form JSON object string (may be blank)
    public boolean createDetections;
    public boolean maskToObject;
    public boolean clearExisting;

    public FiberParams() {}

    /** Copy constructor (used by {@link #withOverrides}). */
    public FiberParams(FiberParams o) {
        this.channel = o.channel;
        this.downsample = o.downsample;
        this.threshold = o.threshold;
        this.fiberMode = o.fiberMode;
        this.smoothing = o.smoothing;
        this.distSmoothing = o.distSmoothing;
        this.seedSpacingUm = o.seedSpacingUm;
        this.seedSensitivity = o.seedSensitivity;
        this.xlinkBoxUm = o.xlinkBoxUm;
        this.linkDistanceUm = o.linkDistanceUm;
        this.linkAngle = o.linkAngle;
        this.bendAngle = o.bendAngle;
        this.angleExtend = o.angleExtend;
        this.maxWidthUm = o.maxWidthUm;
        this.dxlink = o.dxlink;
        this.dirDecay = o.dirDecay;
        this.fiberDirUm = o.fiberDirUm;
        this.fireFlenUm = o.fireFlenUm;
        this.maxSpaceUm = o.maxSpaceUm;
        this.angInterval = o.angInterval;
        this.lambda = o.lambda;
        this.dangLenUm = o.dangLenUm;
        this.minLengthUnit = o.minLengthUnit;
        this.tacsZoneUm = o.tacsZoneUm;
        this.extraJson = o.extraJson;
        this.createDetections = o.createDetections;
        this.maskToObject = o.maskToObject;
        this.clearExisting = o.clearExisting;
    }

    /**
     * Snapshot the persistent preferences the dialog writes as it is tuned. The threshold comes
     * from {@code tmequant.threshold}, which the dialog persists on each commit — so a script
     * default matches the last value tuned interactively.
     */
    public static FiberParams fromPreferences() {
        FiberParams p = new FiberParams();
        p.channel = FiberSocketPreferences.getCollagenChannel();
        p.downsample = FiberSocketPreferences.downsampleProperty().get();
        p.threshold = FiberSocketPreferences.getThreshold();
        p.fiberMode = FiberSocketPreferences.getFiberMode() == 1 ? 1 : 2;
        p.smoothing = FiberSocketPreferences.smoothingProperty().get();
        p.distSmoothing = FiberSocketPreferences.distSmoothingProperty().get();
        p.seedSpacingUm = FiberSocketPreferences.seedSpacingProperty().get();
        p.seedSensitivity = FiberSocketPreferences.seedSensitivityProperty().get();
        p.xlinkBoxUm = FiberSocketPreferences.xlinkBoxProperty().get();
        p.linkDistanceUm = FiberSocketPreferences.linkDistanceProperty().get();
        p.linkAngle = FiberSocketPreferences.linkAngleProperty().get();
        p.bendAngle = FiberSocketPreferences.bendAngleProperty().get();
        p.angleExtend = FiberSocketPreferences.angleExtendProperty().get();
        p.maxWidthUm = FiberSocketPreferences.maxWidthProperty().get();
        p.dxlink = FiberSocketPreferences.dxlinkProperty().get();
        p.dirDecay = FiberSocketPreferences.dirDecayProperty().get();
        p.fiberDirUm = FiberSocketPreferences.fiberDirProperty().get();
        p.fireFlenUm = FiberSocketPreferences.fireFlenProperty().get();
        p.maxSpaceUm = FiberSocketPreferences.maxSpaceProperty().get();
        p.angInterval = FiberSocketPreferences.angIntervalProperty().get();
        p.lambda = FiberSocketPreferences.lambdaProperty().get();
        p.dangLenUm = FiberSocketPreferences.dangLenProperty().get();
        p.minLengthUnit = FiberSocketPreferences.minLengthProperty().get();
        p.tacsZoneUm = FiberSocketPreferences.tacsZoneProperty().get();
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
            p.threshold = asDouble(overrides.get("threshold"), p.threshold);
        }
        if (overrides.containsKey("downsample")) {
            p.downsample = asDouble(overrides.get("downsample"), p.downsample);
        }
        if (overrides.containsKey("channel")) {
            p.channel = (int) asDouble(overrides.get("channel"), p.channel);
        }
        if (overrides.containsKey("minLength")) {
            p.minLengthUnit = asDouble(overrides.get("minLength"), p.minLengthUnit);
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

    static double toAnalysedPx(double userValue, double downsample, boolean calibrated, double pix) {
        double fullResPx = calibrated ? userValue / pix : userValue;
        return fullResPx / downsample;
    }

    static double extToCos(double degrees) {
        return Math.cos(Math.toRadians(Math.max(0, Math.min(89.0, degrees))));
    }

    static double linkaToCos(double degrees) {
        return -Math.cos(Math.toRadians(Math.max(0, Math.min(89.0, degrees))));
    }

    /** White point for the image SENT to FIRE, floored so thresh_im2 never saturates. */
    static double serverNormHi(double threshold, double lo, double hi) {
        return serverNormHi(threshold, lo, hi, 0.0);
    }

    /**
     * As the dialog's {@code serverNormHi}: the white point also covers {@code collagenLevel}, the
     * 99.9th percentile of the region's above-threshold pixels (see
     * {@link FiberEngine#brightCollagenLevel}), so bright bundles keep their internal gradient instead
     * of clipping to 255. Without it the headless path sent a lower white point and therefore a
     * HIGHER thresh_im2 than the dialog for the same native threshold (103 vs 68 on one region).
     */
    static double serverNormHi(double threshold, double lo, double hi, double collagenLevel) {
        double floor = lo + (threshold - lo) * (255.0 / MAX_SERVER_IM2);
        return Math.max(hi, Math.max(floor, collagenLevel));
    }

    /** thresh_im2 for the SENT image, using the floored white point (so it never saturates). */
    static int serverThreshIm2(double threshold, double lo, double hi) {
        return serverThreshIm2(threshold, lo, hi, 0.0);
    }

    static int serverThreshIm2(double threshold, double lo, double hi, double collagenLevel) {
        double eh = serverNormHi(threshold, lo, hi, collagenLevel);
        double range = eh > lo ? eh - lo : 1.0;
        return clamp((int) Math.round((threshold - lo) / range * 255.0), 0, 255);
    }

    /** White point for gray conversion (same as the dialog's {@code serverNormHi()}). */
    public double serverNormHi(double normLo, double normHi) {
        return serverNormHi(threshold, normLo, normHi, 0.0);
    }

    /** As {@link #serverNormHi(double, double)}, covering the region's bright collagen too. */
    public double whitePoint(double normLo, double normHi, double collagenLevel) {
        return serverNormHi(threshold, normLo, normHi, collagenLevel);
    }

    /**
     * The FIRE detection meta JSON for a region, reproducing {@code FiberAnalysisDialog.buildMeta}
     * (has_mask is always false in the headless path — no TACS). {@code logDir}/{@code logFile} add
     * the server-transcript fields when non-null.
     */
    public String buildDetectMeta(int rx, int ry, int rw, int rh, double ds, boolean calibrated,
            double pix, double normLo, double normHi, String logDir, String logFile) {
        return buildDetectMeta(rx, ry, rw, rh, ds, calibrated, pix, normLo, normHi, 0.0, logDir, logFile);
    }

    /** As above, with the region's bright-collagen level in the white point, as the dialog does. */
    public String buildDetectMeta(int rx, int ry, int rw, int rh, double ds, boolean calibrated,
            double pix, double normLo, double normHi, double collagenLevel, String logDir,
            String logFile) {
        int threshIm2 = serverThreshIm2(threshold, normLo, normHi, collagenLevel);
        int ll1 = Math.max(1, (int) Math.round(toAnalysedPx(minLengthUnit, ds, calibrated, pix)));
        double distPx = toAnalysedPx(tacsZoneUm, ds, calibrated, pix);

        JsonObject overrides = new JsonObject();
        overrides.addProperty("thresh_im2", threshIm2);
        overrides.addProperty("LL1", ll1);
        overrides.addProperty("thresh_LMPdist",
                Math.max(2, (int) Math.round(toAnalysedPx(seedSpacingUm, ds, calibrated, pix))));
        overrides.addProperty("sigma_im", smoothing);
        overrides.addProperty("sigma_d", distSmoothing);
        overrides.addProperty("thresh_LMP", seedSensitivity);
        overrides.addProperty("s_xlinkbox",
                Math.max(1, (int) Math.round(toAnalysedPx(xlinkBoxUm, ds, calibrated, pix))));
        overrides.addProperty("thresh_linkd", toAnalysedPx(linkDistanceUm, ds, calibrated, pix));
        overrides.addProperty("thresh_ext", extToCos(bendAngle));
        overrides.addProperty("thresh_dang_aextend", extToCos(angleExtend));
        overrides.addProperty("thresh_linka", linkaToCos(linkAngle));
        overrides.addProperty("widMAX", toAnalysedPx(maxWidthUm, ds, calibrated, pix));
        overrides.addProperty("thresh_Dxlink", dxlink);
        overrides.addProperty("lam_dirdecay", dirDecay);
        overrides.addProperty("s_fiberdir",
                Math.max(2, (int) Math.round(toAnalysedPx(fiberDirUm, ds, calibrated, pix))));
        overrides.addProperty("thresh_flen", toAnalysedPx(fireFlenUm, ds, calibrated, pix));
        overrides.addProperty("s_maxspace",
                Math.max(1.0, toAnalysedPx(maxSpaceUm, ds, calibrated, pix)));
        overrides.addProperty("ang_interval", angInterval);
        overrides.addProperty("lambda", lambda);
        overrides.addProperty("thresh_dang_L", toAnalysedPx(dangLenUm, ds, calibrated, pix));
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
        meta.addProperty("distance_threshold", distPx);
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
            double pix, boolean stitch, String logDir, String logFile) {
        JsonObject m = new JsonObject();
        m.add("fibers", GSON.toJsonTree(fibers));
        m.addProperty("stitch", stitch);
        m.addProperty("has_mask", false);
        m.addProperty("distance_threshold", toAnalysedPx(tacsZoneUm, ds, calibrated, pix));
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
