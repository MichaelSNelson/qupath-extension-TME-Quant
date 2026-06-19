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

    /** When true, create lightweight detection objects instead of annotations. */
    private static final BooleanProperty CREATE_DETECTIONS =
            PathPrefs.createPersistentPreference("tmequant.createDetections", false);

    /** When true, remove prior Fiber/TACS objects in the region before adding new ones. */
    private static final BooleanProperty CLEAR_EXISTING =
            PathPrefs.createPersistentPreference("tmequant.clearExisting", true);

    /** Fiber mode forwarded to the pipeline: 1 = segments, 2/3 = merged fibers. */
    private static final IntegerProperty FIBER_MODE =
            PathPrefs.createPersistentPreference("tmequant.fiberMode", 2);

    /** Index of the channel holding the collagen/fiber signal (0-based). */
    private static final IntegerProperty COLLAGEN_CHANNEL =
            PathPrefs.createPersistentPreference("tmequant.collagenChannel", 0);

    // Remembered dialog field values (image-independent settings).
    private static final DoubleProperty DOWNSAMPLE =
            PathPrefs.createPersistentPreference("tmequant.downsample", 4.0);
    private static final DoubleProperty MIN_LENGTH =
            PathPrefs.createPersistentPreference("tmequant.minLength", 15.0);
    private static final DoubleProperty SEED_SPACING =
            PathPrefs.createPersistentPreference("tmequant.seedSpacingUm", 16.0);
    private static final DoubleProperty SMOOTHING =
            PathPrefs.createPersistentPreference("tmequant.smoothing", 0.0);
    private static final DoubleProperty DIST_SMOOTHING =
            PathPrefs.createPersistentPreference("tmequant.distSmoothing", 0.3);
    private static final DoubleProperty SEED_SENSITIVITY =
            PathPrefs.createPersistentPreference("tmequant.seedSensitivity", 0.2);
    private static final DoubleProperty BEND_ANGLE =
            PathPrefs.createPersistentPreference("tmequant.bendAngle", 70.0);
    private static final DoubleProperty LINK_DISTANCE =
            PathPrefs.createPersistentPreference("tmequant.linkDistanceUm", 30.0);
    private static final DoubleProperty LINK_ANGLE =
            PathPrefs.createPersistentPreference("tmequant.linkAngle", 30.0);
    private static final DoubleProperty MAX_WIDTH =
            PathPrefs.createPersistentPreference("tmequant.maxWidthUm", 40.0);
    private static final DoubleProperty XLINK_BOX =
            PathPrefs.createPersistentPreference("tmequant.xlinkBoxUm", 6.0);
    private static final DoubleProperty ANGLE_EXTEND =
            PathPrefs.createPersistentPreference("tmequant.angleExtend", 10.0);
    private static final DoubleProperty DXLINK =
            PathPrefs.createPersistentPreference("tmequant.dxlink", 1.0);
    private static final DoubleProperty DIR_DECAY =
            PathPrefs.createPersistentPreference("tmequant.dirDecay", 0.5);
    private static final DoubleProperty FIBER_DIR =
            PathPrefs.createPersistentPreference("tmequant.fiberDirUm", 6.0);
    private static final DoubleProperty FIRE_FLEN =
            PathPrefs.createPersistentPreference("tmequant.fireFlenUm", 30.0);
    private static final DoubleProperty MAX_SPACE =
            PathPrefs.createPersistentPreference("tmequant.maxSpaceUm", 10.0);
    private static final DoubleProperty ANG_INTERVAL =
            PathPrefs.createPersistentPreference("tmequant.angInterval", 5.0);
    private static final DoubleProperty LAMBDA =
            PathPrefs.createPersistentPreference("tmequant.lambda", 0.01);
    private static final DoubleProperty DANG_LEN =
            PathPrefs.createPersistentPreference("tmequant.dangLenUm", 30.0);
    private static final DoubleProperty COUNT_TOL =
            PathPrefs.createPersistentPreference("tmequant.countTol", 15.0);
    private static final IntegerProperty MAX_ROUNDS =
            PathPrefs.createPersistentPreference("tmequant.maxRounds", 3);
    private static final IntegerProperty PIECE_WEIGHT =
            PathPrefs.createPersistentPreference("tmequant.pieceWeight", 0);
    private static final DoubleProperty TACS_ZONE =
            PathPrefs.createPersistentPreference("tmequant.tacsZone", 100.0);
    private static final IntegerProperty PREVIEW_SCOPE =
            PathPrefs.createPersistentPreference("tmequant.previewScope", 0);

    public static DoubleProperty downsampleProperty() {
        return DOWNSAMPLE;
    }

    public static DoubleProperty minLengthProperty() {
        return MIN_LENGTH;
    }

    public static DoubleProperty seedSpacingProperty() {
        return SEED_SPACING;
    }

    public static DoubleProperty smoothingProperty() {
        return SMOOTHING;
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

    public static DoubleProperty linkDistanceProperty() {
        return LINK_DISTANCE;
    }

    public static DoubleProperty linkAngleProperty() {
        return LINK_ANGLE;
    }

    public static DoubleProperty maxWidthProperty() {
        return MAX_WIDTH;
    }

    public static DoubleProperty xlinkBoxProperty() {
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

    public static DoubleProperty fiberDirProperty() {
        return FIBER_DIR;
    }

    public static DoubleProperty fireFlenProperty() {
        return FIRE_FLEN;
    }

    public static DoubleProperty maxSpaceProperty() {
        return MAX_SPACE;
    }

    public static DoubleProperty angIntervalProperty() {
        return ANG_INTERVAL;
    }

    public static DoubleProperty lambdaProperty() {
        return LAMBDA;
    }

    public static DoubleProperty dangLenProperty() {
        return DANG_LEN;
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

    public static DoubleProperty tacsZoneProperty() {
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
}
