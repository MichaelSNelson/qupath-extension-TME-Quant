package qupath.ext.tmequant;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent preferences for the Fiber Socket extension: where the Python
 * fiber server lives and how the returned fibers are added to the image.
 *
 * <p>Mirrors the host/port preference pattern used by qupath-extension-qpsc's
 * {@code QPPreferenceDialog} (microscope socket client).</p>
 */
public final class FiberSocketPreferences {

    private FiberSocketPreferences() {}

    private static final StringProperty HOST =
            PathPrefs.createPersistentPreference("tmequant.host", "127.0.0.1");

    private static final IntegerProperty PORT =
            PathPrefs.createPersistentPreference("tmequant.port", 5101);

    /** Path to the server launcher (e.g. tmequant_server.bat) QuPath runs to start the server. */
    private static final StringProperty SERVER_LAUNCHER =
            PathPrefs.createPersistentPreference("tmequant.serverLauncher", "");

    /** When true, auto-launch the server (via {@link #SERVER_LAUNCHER}) if it isn't reachable. */
    private static final BooleanProperty AUTO_LAUNCH =
            PathPrefs.createPersistentPreference("tmequant.autoLaunchServer", true);

    /** When true, create lightweight detection objects instead of annotations.
     *  Defaults to true: a run can produce tens of thousands of fibers, and that many
     *  ANNOTATIONS bogs down (or crashes) the QuPath hierarchy/viewer, whereas detections
     *  are lightweight. */
    private static final BooleanProperty CREATE_DETECTIONS =
            PathPrefs.createPersistentPreference("tmequant.createDetections", true);

    // ---- settings added in 0.2.0. Global (not per-image): a tracer choice or an exclusion source
    // is a way of working, not a property of one slide, so it should survive restarts and follow
    // the user from image to image.

    /** Which tracer the dialog last used (the combo's own label). */
    private static final StringProperty TRACER =
            PathPrefs.createPersistentPreference("tmequant.tracer", "");

    /** Flow tracer: minimum lateral spacing between parallel fibres, in the active unit. */
    private static final UnitScopedDouble FLOW_PARALLEL =
            UnitScopedDouble.migrated("tmequant.flowParallel", "tmequant.flowParallelUm", 3.0);

    /** Optional independent anti-flow self-check (adds a per-fibre score). */
    private static final BooleanProperty ANTI_FLOW =
            PathPrefs.createPersistentPreference("tmequant.antiFlow", false);

    /** Connected-web guard cap, in skeleton px. */
    private static final DoubleProperty WEB_CAP =
            PathPrefs.createPersistentPreference("tmequant.webCap", 200000.0);

    /** Tiles traced at once (client threads + server worker processes). 1 = one at a time. */
    private static final DoubleProperty PARALLEL_TILES =
            PathPrefs.createPersistentPreference("tmequant.parallelTiles", 1.0);

    /** Stage 0 exclusion regions: on/off, where they come from, and how they are applied. */
    private static final BooleanProperty EXCL_ENABLED =
            PathPrefs.createPersistentPreference("tmequant.exclEnabled", false);
    private static final BooleanProperty EXCL_FROM_FILE =
            PathPrefs.createPersistentPreference("tmequant.exclFromFile", false);
    private static final StringProperty EXCL_FILE =
            PathPrefs.createPersistentPreference("tmequant.exclFile", "");
    private static final StringProperty EXCL_OBJECTS =
            PathPrefs.createPersistentPreference("tmequant.exclObjects", "");
    private static final StringProperty EXCL_ROI =
            PathPrefs.createPersistentPreference("tmequant.exclRoi", "");
    private static final UnitScopedDouble EXCL_DILATE =
            UnitScopedDouble.migrated("tmequant.exclDilate", "tmequant.exclDilateUm", 1.0);
    private static final DoubleProperty EXCL_MAX_FRAC =
            PathPrefs.createPersistentPreference("tmequant.exclMaxFrac", 50.0);
    private static final BooleanProperty EXCL_TRIM =
            PathPrefs.createPersistentPreference("tmequant.exclTrim", true);
    private static final BooleanProperty EXCL_ERASE =
            PathPrefs.createPersistentPreference("tmequant.exclErase", false);

    public static StringProperty tracerProperty() {
        return TRACER;
    }

    public static UnitScopedDouble flowParallelProperty() {
        return FLOW_PARALLEL;
    }

    public static BooleanProperty antiFlowProperty() {
        return ANTI_FLOW;
    }

    public static DoubleProperty parallelTilesProperty() {
        return PARALLEL_TILES;
    }

    public static DoubleProperty webCapProperty() {
        return WEB_CAP;
    }

    public static BooleanProperty exclEnabledProperty() {
        return EXCL_ENABLED;
    }

    public static BooleanProperty exclFromFileProperty() {
        return EXCL_FROM_FILE;
    }

    public static StringProperty exclFileProperty() {
        return EXCL_FILE;
    }

    public static StringProperty exclObjectsProperty() {
        return EXCL_OBJECTS;
    }

    public static StringProperty exclRoiProperty() {
        return EXCL_ROI;
    }

    public static UnitScopedDouble exclDilateProperty() {
        return EXCL_DILATE;
    }

    public static DoubleProperty exclMaxFracProperty() {
        return EXCL_MAX_FRAC;
    }

    public static BooleanProperty exclTrimProperty() {
        return EXCL_TRIM;
    }

    public static BooleanProperty exclEraseProperty() {
        return EXCL_ERASE;
    }

    /** When true, remove prior Fiber/TACS objects in the region before adding new ones. */
    private static final BooleanProperty CLEAR_EXISTING =
            PathPrefs.createPersistentPreference("tmequant.clearExisting", true);

    /** When true (and a project is open), save a per-run log — the client's progress and
     *  the FIRE server's full transcript — under {@code <project>/logs/fiber-analysis/}. */
    private static final BooleanProperty LOG_TO_PROJECT =
            PathPrefs.createPersistentPreference("tmequant.logToProject", true);

    /**
     * When true, restrict analysis to the selected object: zero the pixels outside it before
     * sending each tile to FIRE, and clip the returned fibers to its boundary.
     */
    private static final BooleanProperty MASK_TO_OBJECT =
            PathPrefs.createPersistentPreference("tmequant.maskToObject", false);

    /** Fiber mode forwarded to the pipeline: 1 = segments, 2/3 = merged fibers. */
    private static final IntegerProperty FIBER_MODE =
            PathPrefs.createPersistentPreference("tmequant.fiberMode", 2);

    /** Index of the channel holding the collagen/fiber signal (0-based). */
    private static final IntegerProperty COLLAGEN_CHANNEL =
            PathPrefs.createPersistentPreference("tmequant.collagenChannel", 0);

    // Remembered dialog field values (image-independent settings).
    private static final DoubleProperty DOWNSAMPLE =
            PathPrefs.createPersistentPreference("tmequant.downsample", 4.0);
    /** Last-tuned background threshold (native units); persisted so the headless script API has a
     *  sensible default. Image-specific, but stored for {@link FiberParams#fromPreferences()}. */
    private static final DoubleProperty THRESHOLD =
            PathPrefs.createPersistentPreference("tmequant.threshold", 0.0);
    // Distances the user types in the dialog's own unit: microns on a calibrated image,
    // full-resolution pixels on an uncalibrated one. Each is stored once PER UNIT, so a value tuned
    // on an uncalibrated image never comes back as microns on a calibrated one (or the reverse).
    // The micron keys keep the names older versions used; where an old name had no unit, the new
    // micron key starts from the old key's value.
    private static final UnitScopedDouble MIN_LENGTH =
            UnitScopedDouble.migrated("tmequant.minLength", "tmequant.minLengthUm", 15.0);
    private static final UnitScopedDouble SEED_SPACING =
            new UnitScopedDouble("tmequant.seedSpacingUm", 16.0);
    private static final DoubleProperty SMOOTHING =
            PathPrefs.createPersistentPreference("tmequant.smoothing", 0.0);
    /** Largest tile side (analysed px) the region is split into; caps tile size so huge regions
     *  make more tiles instead of a few giant ones. */
    private static final DoubleProperty MAX_TILE_DIM =
            PathPrefs.createPersistentPreference("tmequant.maxTileDim", 2048.0);
    private static final DoubleProperty DIST_SMOOTHING =
            PathPrefs.createPersistentPreference("tmequant.distSmoothing", 0.3);
    private static final DoubleProperty SEED_SENSITIVITY =
            PathPrefs.createPersistentPreference("tmequant.seedSensitivity", 0.2);
    // Key bumped to v2 so the lowered, fit-tuned default takes effect even where an older value was
    // persisted (a parameter search on real FNA collagen favoured ~60 deg: straighter traces, less
    // wander into adjacent structures, minimal recall cost vs 70-89 deg). Raise for curvier samples.
    private static final DoubleProperty BEND_ANGLE =
            PathPrefs.createPersistentPreference("tmequant.bendAngle2", 60.0);
    private static final UnitScopedDouble LINK_DISTANCE =
            new UnitScopedDouble("tmequant.linkDistanceUm", 30.0);
    private static final DoubleProperty LINK_ANGLE =
            PathPrefs.createPersistentPreference("tmequant.linkAngle", 30.0);
    private static final UnitScopedDouble MAX_WIDTH =
            new UnitScopedDouble("tmequant.maxWidthUm", 40.0);
    private static final UnitScopedDouble XLINK_BOX =
            new UnitScopedDouble("tmequant.xlinkBoxUm", 6.0);
    private static final DoubleProperty ANGLE_EXTEND =
            PathPrefs.createPersistentPreference("tmequant.angleExtend", 10.0);
    private static final DoubleProperty DXLINK =
            PathPrefs.createPersistentPreference("tmequant.dxlink", 1.0);
    // Key bumped to v2 so the lowered default takes effect over an older persisted value. The search
    // showed higher decay adds wander (traces swing off the fibre into neighbours) at ZERO recall
    // gain, so 0.3 holds the heading steadier on straight collagen. Raise toward 0.6+ for wavy fibres.
    private static final DoubleProperty DIR_DECAY =
            PathPrefs.createPersistentPreference("tmequant.dirDecay2", 0.3);
    // Four FIRE internals kept in FIRE's own units and at FIRE's own defaults (ct_fire.py). Until
    // 0.2.4 they were typed in microns and converted, which made no sense for the direction window
    // (a COUNT of vertices) and inflated the rest on fine-pixel images: at 0.1725 um/px the old
    // 30 um FIRE min length became 174 px, longer than the fibres. New keys, so an old micron value
    // is never read back in the new unit.
    private static final DoubleProperty FIBER_DIR_VERTICES =
            PathPrefs.createPersistentPreference("tmequant.fiberDirVertices", 4.0);
    private static final DoubleProperty FIRE_FLEN_ANALYSED_PX =
            PathPrefs.createPersistentPreference("tmequant.fireFlenAnalysedPx", 15.0);
    private static final DoubleProperty MAX_SPACE_ANALYSED_PX =
            PathPrefs.createPersistentPreference("tmequant.maxSpaceAnalysedPx", 5.0);
    private static final DoubleProperty ANG_INTERVAL =
            PathPrefs.createPersistentPreference("tmequant.angInterval", 5.0);
    private static final DoubleProperty LAMBDA =
            PathPrefs.createPersistentPreference("tmequant.lambda", 0.01);
    private static final DoubleProperty DANG_LEN_ANALYSED_PX =
            PathPrefs.createPersistentPreference("tmequant.dangLenAnalysedPx", 15.0);
    private static final DoubleProperty COUNT_TOL =
            PathPrefs.createPersistentPreference("tmequant.countTol", 15.0);
    private static final IntegerProperty MAX_ROUNDS =
            PathPrefs.createPersistentPreference("tmequant.maxRounds", 3);
    private static final IntegerProperty PIECE_WEIGHT =
            PathPrefs.createPersistentPreference("tmequant.pieceWeight", 0);
    private static final UnitScopedDouble TACS_ZONE =
            UnitScopedDouble.migrated("tmequant.tacsZone", "tmequant.tacsZoneUm", 100.0);
    private static final IntegerProperty PREVIEW_SCOPE =
            PathPrefs.createPersistentPreference("tmequant.previewScope", 0);
    private static final StringProperty EXTRA_PARAMS =
            PathPrefs.createPersistentPreference("tmequant.extraParams", "");

    // "Last image" memory: downsample / smoothing / the two thresholds / the two-pass + ridge
    // checkboxes are restored ONLY when the image being opened matches this stored id (else fresh).
    // A single slot, no history.
    private static final StringProperty LAST_IMAGE_ID =
            PathPrefs.createPersistentPreference("tmequant.lastImageId", "");
    private static final DoubleProperty LAST_DOWNSAMPLE =
            PathPrefs.createPersistentPreference("tmequant.lastDownsample", 0.0);
    private static final DoubleProperty LAST_SMOOTHING =
            PathPrefs.createPersistentPreference("tmequant.lastSmoothing", 0.0);
    private static final DoubleProperty LAST_THRESHOLD =
            PathPrefs.createPersistentPreference("tmequant.lastThreshold", 0.0);
    private static final DoubleProperty LAST_SECOND_THRESHOLD =
            PathPrefs.createPersistentPreference("tmequant.lastSecondThreshold", 0.0);
    private static final BooleanProperty LAST_TWO_PASS =
            PathPrefs.createPersistentPreference("tmequant.lastTwoPass", false);
    private static final BooleanProperty LAST_RIDGE =
            PathPrefs.createPersistentPreference("tmequant.lastRidge", false);

    /** Persist the last-used per-image settings, keyed to {@code imageId} (a single slot; no history). */
    public static void saveLastImageSettings(String imageId, double downsample, double smoothing,
            double threshold, double secondThreshold, boolean twoPass, boolean ridge) {
        LAST_IMAGE_ID.set(imageId == null ? "" : imageId);
        LAST_DOWNSAMPLE.set(downsample);
        LAST_SMOOTHING.set(smoothing);
        LAST_THRESHOLD.set(threshold);
        LAST_SECOND_THRESHOLD.set(secondThreshold);
        LAST_TWO_PASS.set(twoPass);
        LAST_RIDGE.set(ridge);
    }

    /** True if {@code imageId} matches the stored last-image slot (non-empty), so its settings apply. */
    public static boolean hasLastImageSettings(String imageId) {
        String stored = LAST_IMAGE_ID.get();
        return stored != null && !stored.isEmpty() && stored.equals(imageId);
    }

    public static double lastDownsample() {
        return LAST_DOWNSAMPLE.get();
    }

    public static double lastSmoothing() {
        return LAST_SMOOTHING.get();
    }

    public static double lastThreshold() {
        return LAST_THRESHOLD.get();
    }

    public static double lastSecondThreshold() {
        return LAST_SECOND_THRESHOLD.get();
    }

    public static boolean lastTwoPass() {
        return LAST_TWO_PASS.get();
    }

    public static boolean lastRidge() {
        return LAST_RIDGE.get();
    }

    public static StringProperty extraParamsProperty() {
        return EXTRA_PARAMS;
    }

    public static DoubleProperty downsampleProperty() {
        return DOWNSAMPLE;
    }

    public static DoubleProperty thresholdProperty() {
        return THRESHOLD;
    }

    public static double getThreshold() {
        return THRESHOLD.get();
    }

    public static void setThreshold(double v) {
        THRESHOLD.set(v);
    }

    public static UnitScopedDouble minLengthProperty() {
        return MIN_LENGTH;
    }

    public static UnitScopedDouble seedSpacingProperty() {
        return SEED_SPACING;
    }

    public static DoubleProperty smoothingProperty() {
        return SMOOTHING;
    }

    public static DoubleProperty maxTileDimProperty() {
        return MAX_TILE_DIM;
    }

    public static DoubleProperty distSmoothingProperty() {
        return DIST_SMOOTHING;
    }

    public static DoubleProperty seedSensitivityProperty() {
        return SEED_SENSITIVITY;
    }

    public static DoubleProperty bendAngleProperty() {
        return BEND_ANGLE;
    }

    public static UnitScopedDouble linkDistanceProperty() {
        return LINK_DISTANCE;
    }

    public static DoubleProperty linkAngleProperty() {
        return LINK_ANGLE;
    }

    public static UnitScopedDouble maxWidthProperty() {
        return MAX_WIDTH;
    }

    public static UnitScopedDouble xlinkBoxProperty() {
        return XLINK_BOX;
    }

    public static DoubleProperty angleExtendProperty() {
        return ANGLE_EXTEND;
    }

    public static DoubleProperty dxlinkProperty() {
        return DXLINK;
    }

    public static DoubleProperty dirDecayProperty() {
        return DIR_DECAY;
    }

    public static DoubleProperty fiberDirVerticesProperty() {
        return FIBER_DIR_VERTICES;
    }

    public static DoubleProperty fireFlenAnalysedPxProperty() {
        return FIRE_FLEN_ANALYSED_PX;
    }

    public static DoubleProperty maxSpaceAnalysedPxProperty() {
        return MAX_SPACE_ANALYSED_PX;
    }

    public static DoubleProperty angIntervalProperty() {
        return ANG_INTERVAL;
    }

    public static DoubleProperty lambdaProperty() {
        return LAMBDA;
    }

    public static DoubleProperty dangLenAnalysedPxProperty() {
        return DANG_LEN_ANALYSED_PX;
    }

    public static DoubleProperty countTolProperty() {
        return COUNT_TOL;
    }

    public static IntegerProperty maxRoundsProperty() {
        return MAX_ROUNDS;
    }

    public static IntegerProperty pieceWeightProperty() {
        return PIECE_WEIGHT;
    }

    public static UnitScopedDouble tacsZoneProperty() {
        return TACS_ZONE;
    }

    public static IntegerProperty previewScopeProperty() {
        return PREVIEW_SCOPE;
    }

    public static StringProperty hostProperty() {
        return HOST;
    }

    public static IntegerProperty portProperty() {
        return PORT;
    }

    public static BooleanProperty createDetectionsProperty() {
        return CREATE_DETECTIONS;
    }

    public static IntegerProperty fiberModeProperty() {
        return FIBER_MODE;
    }

    public static String getHost() {
        return HOST.get();
    }

    public static int getPort() {
        return PORT.get();
    }

    public static StringProperty serverLauncherProperty() {
        return SERVER_LAUNCHER;
    }

    public static String getServerLauncher() {
        return SERVER_LAUNCHER.get();
    }

    public static void setServerLauncher(String path) {
        SERVER_LAUNCHER.set(path == null ? "" : path);
    }

    public static BooleanProperty autoLaunchProperty() {
        return AUTO_LAUNCH;
    }

    public static boolean getAutoLaunch() {
        return AUTO_LAUNCH.get();
    }

    public static boolean getCreateDetections() {
        return CREATE_DETECTIONS.get();
    }

    public static BooleanProperty clearExistingProperty() {
        return CLEAR_EXISTING;
    }

    public static boolean getClearExisting() {
        return CLEAR_EXISTING.get();
    }

    public static BooleanProperty logToProjectProperty() {
        return LOG_TO_PROJECT;
    }

    public static boolean getLogToProject() {
        return LOG_TO_PROJECT.get();
    }

    public static BooleanProperty maskToObjectProperty() {
        return MASK_TO_OBJECT;
    }

    public static boolean getMaskToObject() {
        return MASK_TO_OBJECT.get();
    }

    public static int getFiberMode() {
        return FIBER_MODE.get();
    }

    public static IntegerProperty collagenChannelProperty() {
        return COLLAGEN_CHANNEL;
    }

    public static int getCollagenChannel() {
        return COLLAGEN_CHANNEL.get();
    }

    public static void setCollagenChannel(int channel) {
        COLLAGEN_CHANNEL.set(channel);
    }

    /**
     * A distance setting stored once per unit: {@link #um} for calibrated images (microns) and
     * {@link #fullResPx} for uncalibrated ones (full-resolution pixels). Read and write through
     * {@link #forUnit(boolean)} with the CURRENT image's calibration.
     */
    public static final class UnitScopedDouble {
        private final DoubleProperty um;
        private final DoubleProperty fullResPx;

        /** {@code umKey} holds microns; the pixel key is derived from it ("...Um" becomes "...FullResPx"). */
        UnitScopedDouble(String umKey, double dflt) {
            this.um = PathPrefs.createPersistentPreference(umKey, dflt);
            this.fullResPx = PathPrefs.createPersistentPreference(pxKey(umKey), dflt);
        }

        /** As above, with the micron key seeded from an older key that carried no unit. */
        static UnitScopedDouble migrated(String oldKey, String umKey, double dflt) {
            double old = PathPrefs.createPersistentPreference(oldKey, dflt).get();
            return new UnitScopedDouble(umKey, old, dflt);
        }

        private UnitScopedDouble(String umKey, double umDefault, double pxDefault) {
            this.um = PathPrefs.createPersistentPreference(umKey, umDefault);
            this.fullResPx = PathPrefs.createPersistentPreference(pxKey(umKey), pxDefault);
        }

        private static String pxKey(String umKey) {
            return (umKey.endsWith("Um") ? umKey.substring(0, umKey.length() - 2) : umKey) + "FullResPx";
        }

        /** The stored value for an image with ({@code true}) or without a pixel size. */
        public DoubleProperty forUnit(boolean calibrated) {
            return calibrated ? um : fullResPx;
        }
    }
}
