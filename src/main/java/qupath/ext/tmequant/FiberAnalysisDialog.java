package qupath.ext.tmequant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interactive dialog for "Analyze fibers in selected region": pick the collagen
 * channel, tune the FIRE thresholding/segmentation knobs in real-world units,
 * PREVIEW the detected fibers over the region, then commit them as QuPath
 * objects. For TACS, the analysis <b>region</b> (the current selection) is kept
 * separate from the <b>boundary</b> (a tumour annotation chosen from a dropdown
 * that sits inside the region), so there is genuine "outside" stroma to classify.
 */
public final class FiberAnalysisDialog {

    private static final Logger logger = LoggerFactory.getLogger(FiberAnalysisDialog.class);
    private static final String TITLE = "TME Quant - Analyze region";
    /** Documentation links shown at the bottom of the dialog. */
    private static final String DOC_README =
            "https://github.com/MichaelSNelson/qupath-extension-TME-Quant#readme";
    private static final String DOC_SETTINGS =
            "https://github.com/MichaelSNelson/qupath-extension-TME-Quant"
                    + "/blob/main/docs/COLLAGEN_SETTINGS.md";
    // Distinct title for the Suggest/tuning popups, so the dialog-manager extension doesn't
    // confuse them with the main dialog (which shares TITLE).
    private static final String SUGGEST_TITLE = "TME Quant - Suggest parameters";
    private static final int PREVIEW_MAX = 400; // per-canvas cap (two stacked previews)
    // Tiling (in analysed pixels): FIRE memory scales with seed count, so large
    // regions are split into overlapping tiles and processed piece by piece.
    private static final int TILE_CORE = 768; // tile core size
    private static final int TILE_OVERLAP = 96; // margin so seam-crossing fibers are traced
    private static final int MAX_TILES = 64; // soft cap on tile COUNT (grow tiles to stay under it)
    // Hard cap on tile SIZE (set in Preferences ▸ TME Quant, default 2048): even a huge region
    // never balloons a tile past this (analysed px per side). Without it, a whole-slide region at
    // ds=1 grew each of 64 tiles to ~11k px (~129 MP), which froze/OOM'd the preview and choked FIRE.
    // Bigger => fewer tiles (faster, fewer server round-trips) but heavier per tile.
    // (The tile grid is drawn as subsampled faint boundary lines in drawTileGrid, so a huge tile
    // count stays visible without becoming a wall — no fixed cell-count cutoff needed.)
    // Threshold-mask foreground colour: a semi-transparent VIOLET. The mask is a large region and
    // the fibre traces are hairlines drawn on top of it, so the two must differ in LUMINANCE, not
    // just hue -- thin lines are resolved by the eye's brightness channel, and its colour channels
    // have far coarser spatial resolution. The old bright cyan was almost exactly as bright as the
    // orange traces (contrast 1.86:1, below the 3:1 usually asked of a graphical object), which made
    // the fibres vanish into the mask when projected. Violet is dark, so the bright-yellow traces
    // clear 6.1:1 against it, 6.4:1 against grey tissue and 19.6:1 against the black background --
    // the worst case across everything a fibre crosses. It also stays obviously non-grey against
    // greyscale tissue, which a dark teal did not.
    // Deep violet at high opacity. Both parts matter: at the old 44% the bright tissue showed
    // through and pulled the violet toward a washed-out orchid, which is neither dark nor clearly
    // an overlay. A dark base at ~80% stays dark over bright collagen while the tissue's texture
    // still reads through it (and QuPath's overlay-opacity slider can take it back down).
    private static final int MASK_FG_ARGB = 0xCC3E_0064;
    // Subtle blue "aura" (glow + thin border) marking the two "Show tile in viewer" buttons.
    private static final String TILE_VIEW_AURA =
            "-fx-effect: dropshadow(gaussian, rgba(46,100,255,0.65), 8, 0.35, 0, 0);"
            + " -fx-border-color: rgba(46,100,255,0.85); -fx-border-width: 1.2;"
            + " -fx-border-radius: 4; -fx-background-radius: 4;";
    // Amber halo tying the "Clear overlay" buttons to the amber ⚠ message text — shown only while
    // an overlay is actually present (a picked-tile mask or a "Show tile in viewer" overlay).
    private static final String CLEAR_OVERLAY_HALO =
            "-fx-effect: dropshadow(gaussian, rgba(230,160,0,0.75), 8, 0.4, 0, 0);"
            + " -fx-border-color: rgba(230,160,0,0.9); -fx-border-width: 1.2;"
            + " -fx-border-radius: 4; -fx-background-radius: 4;";
    // The threshold preview is FAITHFUL only when read at the selected downsample. If the region at
    // that downsample fits this many analysed px (~1600^2) we read it whole and threshold at the
    // real resolution; otherwise the whole-region mask would be a lie (thresholding a coarser image),
    // so we suppress it and let the user pick ONE tile to preview faithfully.
    private static final long PREVIEW_FAITHFUL_MAX_PX = 2_560_000L;

    private final QuPathGUI qupath;
    // Per-image bindings: rebound when QuPath's active image changes (see rebindToImage).
    private ImageData<BufferedImage> imageData;
    private ImageServer<BufferedImage> server;
    private PathObjectHierarchy hierarchy;
    private PathObject selected; // current region annotation — kept in sync with QuPath's selection
    // Region + selection: mutable so "Apply to chosen area" can retarget to the current QuPath
    // selection without reopening the dialog.
    private ROI selectedRoi;
    private int rx;
    private int ry;
    private int rw;
    private int rh;
    private boolean calibrated;
    private double pixelSizeMicrons;
    private String unit; // "um" or "px"

    private ChoiceBox<String> channelBox;
    private ChoiceBox<String> previewScopeBox; // one tile (fast) vs whole region
    private Spinner<Double> downsampleSpinner;
    private Label tileSizeLabel; // read-only: physical tile size at the current downsample (max tile size is a Preference)
    private javafx.beans.value.ChangeListener<Number> maxTileDimListener; // refreshes tileSizeLabel when the pref changes
    private Spinner<Double> thresholdSpinner; // native intensity units
    private Label thresholdFeedback;          // live "x% of region above" readout
    private CheckBox twoPassCheck;            // run a 2nd higher-threshold pass for thick bundles
    private Spinner<Double> secondThresholdSpinner; // 2nd-pass background threshold (native units)
    private CheckBox ridgeCheck;             // server-side grayscale ridge/vesselness pre-filter (experimental)
    private CheckBox thicknessWidthCheck;    // draw viewer-overlay fibres at their estimated thickness
    // While set (during the 2nd pass), the threshold→FIRE mapping uses this instead of the spinner,
    // so a second detection pass runs at a higher threshold without disturbing the visible control.
    private volatile Double thresholdOverride;
    // Global intensity reference (native units) computed once per channel, shared by
    // every tile so the scale + threshold are consistent (no per-tile noise blow-up).
    private double normLo = 0;
    private double normHi = 255;
    private double statMin = 0;
    private double statMax = 255;
    private double[] statSample = new double[0];
    private double[] tileSample = new double[0]; // native samples of the SELECTED tile (per-tile %)
    private Spinner<Double> minLenSpinner; // um or px
    private Spinner<Double> seedSpacingSpinner; // µm (calibrated) / full-res px — downsample-independent
    private ChoiceBox<String> fiberModeBox;
    private CheckBox tacsCheck;
    private ChoiceBox<String> boundaryBox;
    private Spinner<Double> tacsZoneSpinner; // um or px
    private CheckBox detectionsCheck;
    private Label annotationWarnLabel;            // shown only while annotation output is chosen
    /** Above this many fibres, creating ANNOTATIONS needs explicit confirmation (QuPath slows/crashes). */
    static final int ANNOTATION_WARN_COUNT = 400;
    private CheckBox clearExistingCheck; // remove prior Fiber/TACS objects in-region before adding
    private CheckBox wholeImageCheck;    // analyze the whole image instead of the selected region
    private CheckBox maskToObjectCheck;  // restrict analysis to the selected object (mask pixels + clip fibers)
    private CheckBox saveLogCheck;       // save per-run log (client + FIRE server) into the project
    private ROI runRestrictRoi;          // ROI the CURRENT run is restricted to (null = no restriction)
    // Set for the duration of a run so buildMeta() tells the server where to write its
    // transcript (<project>/logs/fiber-analysis); null = don't ask the server to log.
    private volatile String runServerLogDir;
    private volatile String runServerLogFile;
    // Transient viewer overlay that paints live fibers during a commit (instead of adding
    // per-tile PathObjects, which churn the hierarchy on big runs). Removed when the run ends.
    private FiberPreviewOverlay liveOverlay;
    private QuPathViewer liveOverlayViewer;
    private volatile long lastOverlayRepaintMs;
    // Repaints the viewer when the "C" overlay toggle flips, so the fibre/threshold overlay
    // shows/hides immediately (the overlay itself honours the flag in paintOverlay).
    private javafx.beans.value.ChangeListener<Boolean> overlayVisListener;
    // Set during a batch run so analyzeFull fires it once per completed tile, letting the
    // batch track progress/ETA across ALL images (not just per-image). Null outside batch.
    private volatile Runnable batchTileTick;
    // ② detection — everyday tracing knobs (promoted out of Advanced to match CT-FIRE)
    private Spinner<Double> smoothingSpinner; // sigma_im (stage ①)
    private Spinner<Double> seedSensSpinner; // thresh_LMP
    private Spinner<Double> xlinkBoxSpinner; // s_xlinkbox — µm (calibrated) / full-res px, downsample-independent
    private Spinner<Double> bendAngleSpinner; // thresh_ext, shown in degrees
    private Spinner<Double> angleExtendSpinner; // thresh_dang_aextend, shown in degrees -> cos
    private Spinner<Double> linkAngleSpinner; // thresh_linka, shown in degrees
    // advanced
    private Spinner<Double> distSmoothSpinner; // sigma_d (distance-map smoothing)
    private Spinner<Double> linkDistSpinner; // thresh_linkd — µm (calibrated) / full-res px
    private Spinner<Double> maxWidthSpinner; // widMAX — µm (calibrated) / full-res px (0=off)
    // Advanced SERVER settings — recent guard/cleanup knobs exposed for tuning (sent in the meta).
    private Spinner<Double> densityCapSpinner;   // density guard foreground cap (%)
    private Spinner<Double> seedCapSpinner;       // seed-count guard
    private Spinner<Double> webCapSpinner;        // giant-connected-web guard (skeleton px)
    private CheckBox antiFlowCheck;               // optional independent anti-flow self-check
    private Spinner<Double> flowParallelSpinner;  // flow tracer: min spacing between parallel fibres
    private Spinner<Double> fireTimeoutSpinner;   // per-tile FIRE timeout (s)
    private Spinner<Double> splitJumpMultSpinner; // bridge-split geometry multiple
    private Spinner<Double> splitGapFracSpinner;  // bridge-split gap fraction
    private Spinner<Double> edgeTrimSpinner;      // edge-cap trim margin (px)
    private CheckBox flowFilterCheck;             // remove fibres running across the local collagen flow
    private Spinner<Double> flowCohSpinner;       // flow filter coherence gate (safety dial)
    private javafx.scene.control.ComboBox<String> tracerCombo;  // FIRE vs centerline (medial axis)
    private javafx.scene.control.ComboBox<String> presetCombo; // detection presets (straight/wavy/…)
    private boolean applyingPreset = false;       // guard so applying a preset doesn't flip it to Custom
    private CheckBox rawModeCheck;                // bypass ALL bridge guards/cleanup -> plain FIRE output
    private ExclusionSection exclusionSection;    // stage 0: regions fibre tracing must leave alone
    // Fibre overlay colour: the BRIGHTEST thing on screen, because it is also the thinnest. Paired
    // with the dark violet mask above -- see MASK_FG_ARGB for why luminance, not hue, is what makes
    // a hairline visible. Drawn over FIBER_CASING_COLOR so it keeps its contrast even where it
    // crosses bright tissue rather than the mask.
    private static final java.awt.Color FIBER_OVERLAY_COLOR = new java.awt.Color(255, 255, 0);
    /** Dark casing stroked UNDER each fibre so it reads on any background; null disables it. */
    private static final java.awt.Color FIBER_CASING_COLOR = new java.awt.Color(0, 0, 0, 200);
    // advanced parity knobs (live in the FIRE-only backend; pipeline defaults)
    private Spinner<Double> dxlinkSpinner; // thresh_Dxlink
    private Spinner<Double> dirDecaySpinner; // lam_dirdecay
    private Spinner<Double> fiberDirSpinner; // s_fiberdir — µm (calibrated) / full-res px
    private Spinner<Double> fireFlenSpinner; // thresh_flen — µm (calibrated) / full-res px
    private Spinner<Double> maxSpaceSpinner; // s_maxspace — µm (calibrated) / full-res px
    private Spinner<Double> angIntervalSpinner; // ang_interval (deg)
    private Spinner<Double> lambdaSpinner; // lambda (regularisation)
    private Spinner<Double> dangLenSpinner; // thresh_dang_L — µm (calibrated) / full-res px
    // Suggest/tuner options
    private Spinner<Double> countTolSpinner; // count tolerance band (percent)
    private Spinner<Integer> maxRoundsSpinner; // iterative refinement rounds
    private ChoiceBox<String> pieceWeightBox; // flat union vs area-weighted
    private javafx.scene.control.TextArea extraParamsArea; // free-form JSON overrides
    private Canvas maskCanvas;  // stage 1: live threshold mask (no server)
    private Canvas canvas;      // stage 2: fiber detection preview (after Preview)
    private ScrollPane maskScroll;   // wraps maskCanvas — scroll/zoom viewport
    private ScrollPane canvasScroll; // wraps canvas — kept in sync with maskScroll
    private double previewZoom = 1.0; // shared zoom factor for both previews (1.0–8.0)
    private double dragStartSceneX, dragStartSceneY; // drag-to-pan anchor (scene px)
    private double dragStartH, dragStartV;           // scroll values at drag start
    private boolean previewDragged;                  // moved enough to count as a pan, not a click
    private boolean busy;                            // an analysis/suggest/commit is running
    private int suggestEvaluated;                    // distinct param sets the last Suggest evaluated
    private int suggestTotalRuns;                    // total FIRE runs the last Suggest performed
    // The single open "Suggested parameters" window + its data (so a new run can offer to save it).
    private Stage suggestStage;
    private List<AggResult> suggestResults;
    private List<Piece> suggestPieces;
    private boolean suggestSupervised;
    // Live QuPath state: keep the dialog in sync with selection + hierarchy changes.
    private PathObjectHierarchyListener hierarchyListener;
    private PathObjectSelectionListener selectionListener;
    // Follow QuPath's active image: rebind to the new image when the user switches.
    private javafx.beans.value.ChangeListener<ImageData<BufferedImage>> imageChangeListener;
    private ImageData<BufferedImage> pendingImage; // image to rebind to once a running analysis ends
    private int previewTileIdx = -1; // which tile "One tile (fast)" previews (-1 = centre)
    private Label maskTileLabel;     // shows/lets you pick the preview tile

    // serializeSpecialFloatingPointValues: a fibre width can be NaN (a fibre with no valid vertices,
    // or an un-restarted server not sending width_max/width_std). Plain Gson THROWS on NaN when
    // serialising the POSTPROC/stitch request, which failed a whole run at the end. Emit NaN (Python's
    // json.loads accepts it, matching what the server already sends) instead of crashing.
    private static final Gson GSON =
            new GsonBuilder().serializeSpecialFloatingPointValues().create();
    private Label statusLabel;
    private Label runStatusBar; // prominent progress/ETA line right above the Add-to-image button
    private Label tuneFeedback; // local result line under the Calibrate/Suggest buttons
    private Button suggestBtn;
    private Button previewBtn;
    private Button addBtn;

    private final List<PathObject> boundaryOptions = new ArrayList<>();

    private List<FiberSocketClient.Fiber> lastFibers;
    private double lastDownsample = 1.0;
    private boolean lastWasFull = false; // was the last preview a whole-region (stitched) run?
    private String lastSignature = "";

    // Shared preview background (the globally-normalised gray image FIRE sees),
    // read once per channel/downsample and used by BOTH the live threshold mask and
    // the fiber preview. lastBgRatio = analysisDownsample / backgroundDownsample.
    private BufferedImage lastBgGray;
    private javafx.scene.image.Image lastBgFx;
    private int lastBgW;
    private int lastBgH;
    private double lastBgRatio = 1.0;
    private int bgGen = 0; // generation counter so stale background reads are ignored

    // Too-large preview state: when the region can't be shown whole at the selected downsample,
    // the misleading whole-region mask is suppressed and the user picks one tile to preview.
    private boolean previewTooLarge = false;
    private boolean maskTileCleared = false; // "Clear overlay" pressed: show no tile mask
    private BufferedImage pickedTileGray; // cached gray (at the selected ds) of the picked tile
    private int pickedTileGrayIdx = -1;   // which tile pickedTileGray belongs to (-1 = none)
    private int maskTileLoadGen = 0;      // generation guard so stale tile reads are ignored
    private boolean maskTileLoading = false;
    private javafx.scene.image.Image pickedTileOverlay; // cached red overlay (rebuild only on change)
    private int pickedTileOverlayThr = Integer.MIN_VALUE;
    private double pickedTileOverlaySigma = -1;
    private Button backToFullBtn;         // "Clear overlay" in the fiber-detection section
    private Button clearOverlayThreshBtn; // "Clear overlay" twin in the ① threshold section

    private FiberAnalysisDialog(
            QuPathGUI qupath, ImageData<BufferedImage> imageData, PathObject selected) {
        this.qupath = qupath;
        this.imageData = imageData;
        this.server = imageData.getServer();
        this.hierarchy = imageData.getHierarchy();
        this.selected = selected;
        this.selectedRoi = (selected != null && selected.hasROI()) ? selected.getROI() : null;
        recomputeCalibration();
        resetRegionFromRoi();
    }

    /**
     * Derive calibration from the current {@link #server}. Prefer real pixel-size metadata, but
     * only when genuinely usable: QuPath's hasPixelSizeMicrons() only checks the unit is "µm" -- it
     * does NOT verify the value is finite or > 0, so a malformed calibration (unit µm but value
     * 0/NaN) reports calibrated=true and getAveragedPixelSizeMicrons() hands back 0 or NaN.
     * Dividing user microns by that would corrupt every length, so require finite and > 0;
     * otherwise fall back to working directly in PIXELS. Matches FiberObjectBuilder's commit guard.
     */
    private void recomputeCalibration() {
        PixelCalibration cal = server.getPixelCalibration();
        double avgMicrons = (cal != null && cal.hasPixelSizeMicrons())
                ? cal.getAveragedPixelSizeMicrons() : Double.NaN;
        this.calibrated = Double.isFinite(avgMicrons) && avgMicrons > 0;
        this.pixelSizeMicrons = calibrated ? avgMicrons : Double.NaN;
        this.unit = calibrated ? "um" : "px";
    }

    /** Set the analysis region from {@link #selectedRoi} (its bounds), or the whole image if null. */
    private void resetRegionFromRoi() {
        if (selectedRoi != null) {
            int x = (int) Math.floor(selectedRoi.getBoundsX());
            int y = (int) Math.floor(selectedRoi.getBoundsY());
            int w = (int) Math.ceil(selectedRoi.getBoundsWidth());
            int h = (int) Math.ceil(selectedRoi.getBoundsHeight());
            this.rx = Math.max(0, x);
            this.ry = Math.max(0, y);
            this.rw = Math.min(server.getWidth() - rx, w);
            this.rh = Math.min(server.getHeight() - ry, h);
        } else {
            this.rx = 0;
            this.ry = 0;
            this.rw = server.getWidth();
            this.rh = server.getHeight();
        }
    }

    /**
     * Rebind the whole dialog to a newly-activated QuPath image (the user switched images), so the
     * region, calibration, channels, boundary list, threshold stats and live mask all reflect the
     * new image. Follows the QPSC active-image idiom. Deferred while a run is in progress.
     */
    private void rebindToImage(ImageData<BufferedImage> newData) {
        if (newData == null || newData == this.imageData) {
            return; // image closed, or no real change
        }
        if (busy) {
            pendingImage = newData; // apply once the current analysis finishes (see setBusy)
            return;
        }
        logger.info("TME Quant: active image changed — rebinding the dialog to the new image.");
        clearLiveOverlay(); // a live overlay belongs to the OLD viewer/image
        // 1. Detach listeners from the OLD hierarchy.
        if (hierarchy != null) {
            if (hierarchyListener != null) {
                hierarchy.removeListener(hierarchyListener);
            }
            if (selectionListener != null) {
                hierarchy.getSelectionModel().removePathObjectSelectionListener(selectionListener);
            }
        }
        // 2. Rebind per-image references + calibration.
        this.imageData = newData;
        this.server = newData.getServer();
        this.hierarchy = newData.getHierarchy();
        recomputeCalibration();
        updateTileSizeLabel(); // µm value depends on the new image's pixel size
        // 3. Adopt the new image's current selection (honour whole-image mode).
        boolean whole = wholeImageCheck != null && wholeImageCheck.isSelected();
        this.selected = whole ? null : hierarchy.getSelectionModel().getSelectedObject();
        this.selectedRoi = (!whole && selected != null && selected.hasROI() && selected.getROI().isArea())
                ? selected.getROI() : null;
        resetRegionFromRoi();
        // 4. Reset stale preview/analysis state.
        lastFibers = null;
        lastWasFull = false;
        lastSignature = "";
        previewTileIdx = -1;
        previewZoom = 1.0;
        bgGen++;                 // ignore any in-flight background read from the old image
        lastBgGray = null;
        lastBgFx = null;
        if (tuneFeedback != null) {
            tuneFeedback.setVisible(false);
            tuneFeedback.setManaged(false);
        }
        // 5. Refresh per-image UI: channels, boundary list, threshold stats.
        int nCh = server.nChannels();
        channelBox.getItems().setAll(channelLabels());
        channelBox.getSelectionModel().select(
                clamp(FiberSocketPreferences.getCollagenChannel(), 0, Math.max(0, nCh - 1)));
        channelBox.setDisable(nCh <= 1);
        refreshBoundaryBox();
        if (exclusionSection != null) {
            // Regions belong to the image they were loaded from — drop them and re-offer this
            // image's classes rather than silently masking with another image's coordinates.
            exclusionSection.setImageData(newData);
        }
        computeStats(chosenChannel());
        applyStatsToThresholdSpinner();
        restoreLastSettingsIfMatch(); // if this is the same image as last time, restore its settings
        // 6. Reattach listeners to the NEW hierarchy.
        hierarchy.addListener(hierarchyListener);
        hierarchy.getSelectionModel().addPathObjectSelectionListener(selectionListener);
        // 7. Redraw for the new image.
        updateRegionStatus();
        refreshBackground();
        setBusy(false, "Switched to the active image. " + (selectedRoi != null
                ? "Using the selected region." : "No region selected — using the whole image."));
    }

    /** Stable identity of the current image, for the "last image" settings memory. */
    private String imageId() {
        try {
            return server != null ? server.getPath() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /** Save downsample / smoothing / both thresholds / two-pass + ridge for the CURRENT image (single
     *  slot, no history). Called when the image is actually USED (Preview / Apply). */
    private void saveLastSettings() {
        try {
            FiberSocketPreferences.saveLastImageSettings(imageId(),
                    downsampleSpinner.getValue(), smoothingSpinner.getValue(),
                    thresholdSpinner.getValue(),
                    secondThresholdSpinner != null ? secondThresholdSpinner.getValue() : 0.0,
                    twoPassCheck != null && twoPassCheck.isSelected(),
                    ridgeCheck != null && ridgeCheck.isSelected());
        } catch (RuntimeException ex) {
            logger.warn("Could not save last-image settings: {}", ex.getMessage());
        }
    }

    /** If the current image matches the stored "last image", restore its downsample / smoothing / both
     *  thresholds / two-pass + ridge (overriding the fresh, stats-derived defaults). Else no-op. */
    private void restoreLastSettingsIfMatch() {
        if (!FiberSocketPreferences.hasLastImageSettings(imageId())) {
            return; // different image (or nothing stored) — keep the fresh defaults
        }
        setSpinnerClamped(downsampleSpinner, FiberSocketPreferences.lastDownsample());
        setSpinnerClamped(smoothingSpinner, FiberSocketPreferences.lastSmoothing());
        setSpinnerClamped(thresholdSpinner, FiberSocketPreferences.lastThreshold());
        setSpinnerClamped(secondThresholdSpinner, FiberSocketPreferences.lastSecondThreshold());
        if (twoPassCheck != null) {
            twoPassCheck.setSelected(FiberSocketPreferences.lastTwoPass());
        }
        if (ridgeCheck != null) {
            ridgeCheck.setSelected(FiberSocketPreferences.lastRidge());
        }
        logger.info("TME Quant: restored last-used settings for this image.");
    }

    /** Set a Double spinner, clamped to its value-factory range (a stored value can be off-range). */
    private static void setSpinnerClamped(Spinner<Double> s, double v) {
        if (s == null) {
            return;
        }
        if (s.getValueFactory() instanceof javafx.scene.control.SpinnerValueFactory
                .DoubleSpinnerValueFactory f) {
            f.setValue(Math.max(f.getMin(), Math.min(f.getMax(), v)));
        }
    }

    public static void show(QuPathGUI qupath, boolean tacsDefault) {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(TITLE, "No image is open.");
            return;
        }
        PathObject selected = imageData.getHierarchy().getSelectionModel().getSelectedObject();
        if (tacsDefault && (selected == null || !selected.hasROI())) {
            Dialogs.showErrorMessage(
                    TITLE, "TACS needs a selected region annotation (the analysis area).");
            return;
        }
        // Make sure the FIRE server is up (launch it / guide the user) before showing the
        // dialog, instead of failing later at the first preview. onReady runs on the FX thread.
        ServerLauncher.ensureReachable(qupath,
                () -> new FiberAnalysisDialog(qupath, imageData, selected).buildAndShow(tacsDefault));
    }

    private void buildAndShow(boolean tacsDefault) {
        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle(TITLE);

        int nCh = server.nChannels();
        channelBox = new ChoiceBox<>();
        channelBox.getItems().addAll(channelLabels());
        channelBox.getSelectionModel().select(clamp(FiberSocketPreferences.getCollagenChannel(), 0, nCh - 1));
        channelBox.setDisable(nCh <= 1);
        channelBox.setTooltip(ttip(
                "Which image channel holds the collagen signal (e.g. the SHG or a collagen stain "
                        + "channel). Only this one channel is sent to the engine; the others are ignored."));

        previewScopeBox = new ChoiceBox<>();
        previewScopeBox.getItems().addAll("One tile (fast)", "Whole region");
        previewScopeBox.getSelectionModel().select(0);
        previewScopeBox.setTooltip(ttip(
                "Preview scope. 'One tile' runs FIRE on just one tile for fast tuning — double-click "
                        + "the fiber preview to choose WHICH tile (the yellow box). 'Whole region' runs "
                        + "all tiles + stitching + TACS (slower). Apply to chosen area always does the full run."));
        // Toggling scope shows/hides the selected-tile highlight on the mask.
        previewScopeBox.getSelectionModel().selectedIndexProperty()
                .addListener((o, ov, nv) -> redrawFibers());

        double d0 = Math.max(1.0, Math.ceil(Math.max(rw, rh) / 1200.0));
        downsampleSpinner = spinnerD(1.0, 32.0, d0, 1.0);
        tileSizeLabel = new Label();
        tileSizeLabel.setTooltip(ttip(
                "Physical size of the largest tile sent to FIRE at the current downsample "
                        + "(max tile size × downsample × pixel size). Bigger tiles = fewer, faster "
                        + "round-trips but heavier each; a huge region makes MORE tiles rather than "
                        + "giant ones. To change the cap, edit \"Max tile size\" in "
                        + "Preferences ▸ TME Quant."));

        // Compute the global intensity stats for the chosen channel, then build the
        // threshold spinner in NATIVE units (the same values shown when mousing over
        // the image) with a sensible default and a live "% above" feedback label.
        computeStats(chosenChannel());
        double step = Math.max((statMax - statMin) / 100.0, 1e-6);
        thresholdSpinner = spinnerD(statMin, statMax, defaultThreshold(), step);
        thresholdFeedback = new Label();
        thresholdSpinner.valueProperty().addListener((o, ov, nv) -> {
            updateThresholdFeedback();
            redrawMask(); // live threshold mask update (no server / no FIRE)
        });
        // Two-pass detection: a 2nd, higher-threshold pass carves thick bright bundles so their
        // internal striations trace along the bundle instead of across it. Merged + de-duped.
        twoPassCheck = new CheckBox("Two-pass (thin + thick bundles)");
        twoPassCheck.setWrapText(true);
        twoPassCheck.setTooltip(ttip(
                "Run detection TWICE and merge. Pass 1 uses the Background threshold above (thin, "
                        + "faint fibres). Pass 2 uses the higher '2nd-pass threshold' to carve thick "
                        + "bright bundles, so their internal vertical striations become traceable ridges "
                        + "(instead of FIRE tracing across the bundle). The two fibre sets are merged and "
                        + "de-duplicated. Slower (two full runs). TACS is ignored in two-pass mode."));
        secondThresholdSpinner = spinnerD(statMin, statMax,
                Math.min(statMax, defaultThreshold() * 2.0), step);
        secondThresholdSpinner.setTooltip(ttip(
                "The HIGHER background threshold for pass 2 (thick bundles). Raise it until a solid "
                        + "bright bundle breaks into its internal vertical striations in the ① threshold "
                        + "preview — that's when FIRE can trace along the bundle. Must be higher than the "
                        + "main Background threshold. Only used when 'Two-pass' is ticked."));
        secondThresholdSpinner.disableProperty().bind(twoPassCheck.selectedProperty().not());
        ridgeCheck = new CheckBox("Ridge enhancement (experimental)");
        ridgeCheck.setWrapText(true);
        ridgeCheck.setTooltip(ttip(
                "USE CASE: thick bright bundles that trace ACROSS their width, or thin faint fibres a "
                        + "brightness threshold misses — ridge makes FIRE follow line-like STRUCTURE. "
                        + "Best at moderate/high downsample; at downsample 1 (wide fibres in pixels) it "
                        + "tends to drop fibres, so prefer Two-pass or a lower threshold there.\n"
                        + "EXPERIMENTAL detection mode (not a thresholding control). Replaces the image FIRE "
                        + "traces on with a multiscale ridge/vesselness (Frangi) 'fibre-ness' map, so "
                        + "FIRE works on how LINE-LIKE each pixel is instead of raw brightness — lights "
                        + "up striations inside thick bundles and goes near-zero between fibres. Runs "
                        + "server-side; restart the FIRE server after updating.\n"
                        + "IMPORTANT — this does NOT change the ① threshold mask (that is raw "
                        + "brightness, computed here): the server AUTO-thresholds the fibre-ness map "
                        + "(Otsu), so the Background-threshold slider has little effect in this mode. "
                        + "Judge results only from the traced fibres ('Show tile in viewer' / TEST-EXPORT).\n"
                        + "The Frangi scales come from Max fibre width and are capped small, so at LOW "
                        + "downsample (wide fibres in pixels) many fibres get a weak response and drop "
                        + "out — ridge tends to work better at higher downsample (or on thin fibres). "
                        + "An alternative to Two-pass for perpendicular bundles."));
        // Recompute stats + re-read the background when the collagen channel changes.
        channelBox.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> {
            computeStats(Math.max(0, nv.intValue()));
            applyStatsToThresholdSpinner();
            refreshBackground();
        });
        // Downsample changes the analysed grid (and tile layout): re-read the
        // background and reset the picked preview tile to centre.
        downsampleSpinner.valueProperty().addListener((o, ov, nv) -> {
            previewTileIdx = -1;
            previewZoom = 1.0; // the preview image resizes with downsample; reset zoom/scroll so the
                               // retained pan doesn't park the view off the (now differently-sized) image
            updateTileSizeLabel(); // physical tile size scales with downsample
            refreshBackground();
        });
        // min length default: ~15 um if calibrated, else 30 px
        double minLenDefault = calibrated ? 15.0 : 30.0;
        double minLenMax = calibrated ? 1000.0 : 400.0;
        minLenSpinner = spinnerD(1.0, minLenMax, minLenDefault, calibrated ? 1.0 : 5.0);
        // Seed spacing in microns (calibrated) / full-res px: converted to analysed px at
        // run time so the physical spacing is unaffected by the downsample. Default 16 µm
        // ≈ the old 8-analysed-px default at downsample 4 and ~0.5 µm/px.
        seedSpacingSpinner = spinnerD(1.0, 200.0, 16.0, 1.0);
        fiberModeBox = new ChoiceBox<>();
        fiberModeBox.getItems().addAll("Merged fibers", "Segments");
        fiberModeBox.getSelectionModel().select(FiberSocketPreferences.getFiberMode() == 1 ? 1 : 0);
        fiberModeBox.setTooltip(ttip(
                "'Merged fibers' joins traced pieces into whole continuous fibers — best for long "
                        + "collagen bundles and most analyses. 'Segments' keeps each traced piece "
                        + "separate — use when short straight segments are your unit of measurement."));

        tacsCheck = new CheckBox("Classify TACS relative to a boundary");
        tacsCheck.setWrapText(true);
        tacsCheck.setSelected(tacsDefault);
        tacsCheck.setTooltip(ttip(
                "Classify each fiber by its Tumour-Associated Collagen Signature: TACS-2 (straightened "
                        + "fibers running parallel to the boundary) vs TACS-3 (fibers running perpendicular, "
                        + "radiating away). Needs a Boundary annotation (below) sitting inside the analysis "
                        + "region, with stroma around it."));
        boundaryBox = new ChoiceBox<>();
        boundaryBox.getItems().addAll(boundaryLabels());
        if (!boundaryBox.getItems().isEmpty()) {
            boundaryBox.getSelectionModel().select(0);
        }
        boundaryBox.setMaxWidth(Double.MAX_VALUE);
        boundaryBox.setTooltip(ttip(
                "The tumour-stroma boundary outline (a separate annotation inside the analysis region). "
                        + "Fibers are classified by their angle and distance relative to this outline."));
        boundaryBox.disableProperty().bind(tacsCheck.selectedProperty().not());
        double zoneDefault = calibrated ? 100.0 : 100.0;
        double zoneMax = calibrated ? 2000.0 : 1000.0;
        tacsZoneSpinner = spinnerD(1.0, zoneMax, zoneDefault, calibrated ? 5.0 : 5.0);
        tacsZoneSpinner.disableProperty().bind(tacsCheck.selectedProperty().not());

        detectionsCheck = new CheckBox("Create detections (not annotations)");
        detectionsCheck.setWrapText(true);
        // Always start on DETECTIONS. Annotation output is a deliberate per-session choice (e.g. for
        // curating ground truth on a small region) and is intentionally NOT remembered: a sticky
        // "annotations" setting silently turns the next large run into thousands of annotations.
        detectionsCheck.setSelected(true);
        detectionsCheck.setTooltip(ttip(
                "Recommended ON (the default every time the dialog opens). As DETECTIONS, fibres stay "
                        + "lightweight even in the tens of thousands. As ANNOTATIONS (this box OFF) they "
                        + "become editable polylines, which is what you want for curating ground truth — "
                        + "but QuPath handles annotations far less efficiently: more than a few hundred "
                        + "(~" + ANNOTATION_WARN_COUNT + ") can make the hierarchy and viewer very slow or "
                        + "crash QuPath. Use annotations only on a small region. You'll be asked to "
                        + "confirm before more than " + ANNOTATION_WARN_COUNT + " are created."));
        annotationWarnLabel = new Label("⚠ Annotations: keep to a small region — more than ~"
                + ANNOTATION_WARN_COUNT + " can slow down or crash QuPath. You'll be asked to confirm "
                + "above that.");
        annotationWarnLabel.setWrapText(true);
        annotationWarnLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #c0392b;");
        annotationWarnLabel.visibleProperty().bind(detectionsCheck.selectedProperty().not());
        annotationWarnLabel.managedProperty().bind(annotationWarnLabel.visibleProperty());

        saveLogCheck = new CheckBox("Save run log to project");
        saveLogCheck.setWrapText(true);
        saveLogCheck.setSelected(FiberSocketPreferences.getLogToProject());
        saveLogCheck.setTooltip(ttip(
                "When a project is open, write a per-run log into "
                        + "<project>/logs/fiber-analysis/: a client-side progress file plus the FIRE "
                        + "server's FULL console transcript (foreground %, density-guard raises, "
                        + "retries) — so a long run's detail survives the server console scrolling "
                        + "away or a crash. No effect without a project."));

        wholeImageCheck = new CheckBox("Use whole image (ignore selection)");
        wholeImageCheck.setWrapText(true);
        wholeImageCheck.setTooltip(ttip(
                "Preview and analyze the ENTIRE image with the current parameters, instead of the "
                        + "selected annotation. Handy after tuning on a small representative region: "
                        + "tune on the selection, then tick this to run the tuned parameters on the "
                        + "whole image. While ticked, the dialog ignores the QuPath selection."));
        wholeImageCheck.selectedProperty().addListener((o, ov, nv) -> {
            if (busy) {
                return;
            }
            if (nv) {
                setWholeImageRegion();
            } else {
                retargetRegionToSelection();
            }
            previewTileIdx = -1;
            previewZoom = 1.0;
            updateRegionStatus();
            refreshBackground();
        });

        clearExistingCheck = new CheckBox("Remove existing Fiber/TACS objects in the region first");
        clearExistingCheck.setWrapText(true);
        clearExistingCheck.setSelected(FiberSocketPreferences.getClearExisting());
        clearExistingCheck.setTooltip(ttip(
                "Before adding, delete any previously-created Fiber and TACS-1/2/3 objects whose "
                        + "location falls inside the analysis region, so repeated runs don't stack "
                        + "overlapping objects. Objects elsewhere in the image are kept."));

        maskToObjectCheck = new CheckBox("Restrict to selected object (mask + clip)");
        maskToObjectCheck.setWrapText(true);
        maskToObjectCheck.setSelected(FiberSocketPreferences.getMaskToObject());
        maskToObjectCheck.setTooltip(ttip(
                "Restrict analysis to the selected object (e.g. a pixel-classifier collagen "
                        + "annotation): pixels outside it are zeroed before FIRE runs, and traced "
                        + "fibres are clipped to its boundary (clipped remnants shorter than the Min "
                        + "fiber length are dropped). Needs an area object selected; ignored otherwise."));
        // Enabling masking changes what FIRE sees, so refresh the preview mask when toggled.
        maskToObjectCheck.selectedProperty().addListener((o, ov, nv) -> refreshBackground());

        // Smoothing is part of stage ① (it changes what FIRE thresholds): live mask.
        // sigma_im is created below; wire its listener after creation.

        // Advanced FIRE knobs (analysed-pixel / fractional / degree units).
        smoothingSpinner = spinnerD(0.0, 5.0, 0.0, 0.5); // sigma_im
        distSmoothSpinner = spinnerD(0.0, 5.0, 0.3, 0.1); // sigma_d
        seedSensSpinner = spinnerD(0.0, 1.0, 0.2, 0.05); // thresh_LMP
        // Cross-link search box in microns (calibrated) / full-res px — downsample-independent.
        // Default 6 µm ≈ the old 3-analysed-px default at downsample 4 and ~0.5 µm/px.
        xlinkBoxSpinner = spinnerD(0.5, 50.0, 6.0, 0.5); // s_xlinkbox
        bendAngleSpinner = spinnerD(5.0, 89.0, 60.0, 5.0); // thresh_ext = cos(deg); fit-tuned default
        angleExtendSpinner = spinnerD(0.0, 89.0, 10.0, 5.0); // thresh_dang_aextend = cos(deg)
        // Distances/lengths/widths are in microns (full-res px when uncalibrated), converted to
        // analysed px at run time — so they're downsample-independent. µm defaults ≈ the old
        // analysed-px defaults at downsample 4 and ~0.5 µm/px.
        linkDistSpinner = spinnerD(0.0, 120.0, 30.0, 1.0); // thresh_linkd
        linkAngleSpinner = spinnerD(0.0, 89.0, 30.0, 5.0); // thresh_linka = -cos(deg)
        maxWidthSpinner = spinnerD(0.0, 120.0, 40.0, 1.0); // widMAX (0 = off)
        // Advanced parity knobs — pipeline defaults so adding them changes nothing until touched.
        dxlinkSpinner = spinnerD(0.0, 10.0, 1.0, 0.1); // thresh_Dxlink
        dirDecaySpinner = spinnerD(0.0, 1.0, 0.3, 0.05); // lam_dirdecay; fit-tuned default (was 0.5)
        fiberDirSpinner = spinnerD(2.0, 40.0, 6.0, 1.0); // s_fiberdir (µm)
        fireFlenSpinner = spinnerD(0.0, 200.0, 30.0, 1.0); // thresh_flen (µm)
        maxSpaceSpinner = spinnerD(1.0, 60.0, 10.0, 1.0); // s_maxspace (µm)
        angIntervalSpinner = spinnerD(1.0, 30.0, 5.0, 1.0); // ang_interval (deg)
        lambdaSpinner = spinnerD(0.0, 1.0, 0.01, 0.01); // lambda
        dangLenSpinner = spinnerD(0.0, 200.0, 30.0, 1.0); // thresh_dang_L (µm)
        dxlinkSpinner.setTooltip(ttip(
                "Nucleation distance threshold (thresh_Dxlink): how strong a distance-map peak must "
                        + "be to seed a fiber. HIGHER = only thick/bright ridge cores seed → fewer "
                        + "seeds, cleaner; LOWER = thin/faint ridges seed too → more seeds and more "
                        + "fibers (and more noise). Pipeline default 1.0 (CT-FIRE GUI ships 1.5)."));
        dirDecaySpinner.setTooltip(ttip(
                "Direction decay (lam_dirdecay, 0–1): how quickly the tracer's heading turns toward "
                        + "each new local step. HIGHER (→0.8) lets the heading swing to follow the "
                        + "ridge — traces bend and follow curves; LOWER (→0) holds the running heading "
                        + "almost fixed — traces stay straight and resist turning. Default 0.5. For "
                        + "wavy/curly fibers raise this AND lower Seed spacing (the vertices must be "
                        + "close enough to bend between)."));
        fiberDirSpinner.setTooltip(ttip(
                "Window used to estimate a fiber's local direction (s_fiberdir), in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + " — converted to analysis pixels at run time. LARGER = smoother, steadier "
                        + "heading (averages more past vertices); SMALLER = heading reacts to local bends."));
        fireFlenSpinner.setTooltip(ttip(
                "FIRE's own short-fiber removal length (thresh_flen), in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + ", applied inside the backend during linking. HIGHER removes more short "
                        + "vertex-chains (fewer short fibers); lower keeps them. Distinct from 'Min "
                        + "fiber length' (the post-trace LL1 filter)."));
        maxSpaceSpinner.setTooltip(ttip(
                "Vertex spacing when resampling each fiber centerline (s_maxspace), in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + ". LARGER = coarser centerline / bridges bigger gaps; SMALLER = finer, more "
                        + "vertices. Geometry only — doesn't change how many fibers are found."));
        angIntervalSpinner.setTooltip(ttip(
                "Angle sampling interval (ang_interval, degrees) for per-fiber angle/orientation. "
                        + "SMALLER = finer sampling (higher orientation/TACS resolution); LARGER = "
                        + "coarser. Default 5."));
        lambdaSpinner.setTooltip(ttip(
                "Beam-fit regularisation / spline tension (lambda) in fiber interpolation. HIGHER = "
                        + "stiffer, straighter centerline; LOWER = follows every wiggle. Geometry only "
                        + "— doesn't change the fiber count. Default 0.01."));
        dangLenSpinner.setTooltip(ttip(
                "Dangling-end pruning length (thresh_dang_L), in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + ": short end-spurs up to this length would be trimmed. NOTE: currently "
                        + "INACTIVE — this build runs dangler removal in MATLAB-faithful mode (a no-op), "
                        + "so this value has no effect."));

        // Suggest/tuner options.
        countTolSpinner = spinnerD(0.0, 50.0, 15.0, 5.0); // count tolerance band (%)
        maxRoundsSpinner = spinnerI(1, 6, 3, 1); // iterative refinement rounds
        pieceWeightBox = new ChoiceBox<>();
        pieceWeightBox.getItems().addAll("Flat union", "Area-weighted");
        pieceWeightBox.getSelectionModel().select(0);
        pieceWeightBox.setMaxWidth(Double.MAX_VALUE);
        countTolSpinner.setTooltip(ttip(
                "How close the detected count must be to your annotated count to score perfectly "
                        + "(± this %). Human annotation is imperfect, so a band beats an exact target. "
                        + "Default ±15%."));
        maxRoundsSpinner.setTooltip(ttip(
                "Iterative zoom: after a coarse grid, the search re-centres a finer grid on the best "
                        + "detection settings and repeats, up to this many rounds (stops early once "
                        + "the best stops improving). Default 3."));
        pieceWeightBox.setTooltip(ttip(
                "For a disjoint (multi-part) annotation: how to combine the pieces. 'Flat union' pools "
                        + "all detected/ground-truth fibres into one total (recommended). 'Area-weighted' "
                        + "weights each piece's quality terms by its area."));
        // Smoothing belongs to stage ①: FIRE blurs the image BEFORE thresholding, so
        // the live mask must reflect it. Update the mask as it changes.
        smoothingSpinner.valueProperty().addListener((o, ov, nv) -> redrawMask());
        smoothingSpinner.setTooltip(ttip(
                "Gaussian blur (sigma_im, in analysis px at the current downsample — NOT microns) applied before "
                        + "thresholding AND tracing. Part of stage ①: the live mask reflects it.\n"
                        + "CAUTION: raising this ADDS red to the mask (blur spreads bright collagen) "
                        + "but also ERASES the thin ridges FIRE traces — too much and detection finds "
                        + "NOTHING even though the mask looks full. Keep it low (0–1, up to ~2 for "
                        + "very noisy images); if Suggest finds 0 fibers, lower this first."));
        distSmoothSpinner.setTooltip(ttip(
                "Extra smoothing of the internal distance map before seeds are placed (sigma_d, in "
                        + "analysis px at the current downsample — NOT microns). "
                        + "Raise (0.5–2) on noisy/faint collagen to merge broken fiber ridges into "
                        + "continuous traces; the companion to 'Smoothing'."));
        seedSensSpinner.setTooltip(ttip(
                "How easily a point becomes a fiber starting-point (thresh_LMP). LOWER accepts weaker "
                        + "spots → MORE seeds → more fibers; raise to seed only the brightest cores. "
                        + "(Different from 'Seed spacing', which sets how far apart seeds must be.)"));
        xlinkBoxSpinner.setTooltip(ttip(
                "Search-box radius (s_xlinkbox) used when finding nucleation points / cross-links, in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + " — converted to analysis pixels at run time, so it stays fixed when you "
                        + "change the downsample. Larger merges nearby maxima into one seed; smaller "
                        + "keeps them separate. Default 6 µm ≈ the pipeline default 3 px at downsample 4."));
        angleExtendSpinner.setTooltip(ttip(
                "Angle-extend threshold (thresh_dang_aextend), in degrees — the largest turn a fiber "
                        + "end could take while being extended through a junction. NOTE: currently "
                        + "INACTIVE — this build runs dangler handling in MATLAB-faithful mode (a "
                        + "no-op), so this value has no effect. Default 10°."));
        bendAngleSpinner.setTooltip(ttip(
                "Largest turn the tracer may make at each step, in degrees (maps to thresh_ext). "
                        + "RAISE to follow curvier/wavier collagen; lower to keep only straighter fibers. "
                        + "Default 70°."));
        linkDistSpinner.setTooltip(ttip(
                "Largest gap (thresh_linkd) that can be bridged to join two fiber pieces into one, in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + ". Raise to connect fragmented bundles; lower if separate fibers merge."));
        linkAngleSpinner.setTooltip(ttip(
                "How far from straight two pieces may bend and still be joined, in degrees (maps to "
                        + "thresh_linka). Raise to link curvier fibers across gaps; lower for near-straight "
                        + "joins only. Pairs with 'Link distance'. Default 30°."));
        // widMAX is applied SERVER-SIDE as a max-width filter (the compiled FIRE-only
        // backend doesn't read it), mirroring tme-quant's extraction.py: fibers wider
        // than this are dropped. 0 disables the filter.
        maxWidthSpinner.setTooltip(ttip(
                "Drops detected fibers whose estimated width exceeds this value (widMAX), in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + " — useful for rejecting thick blob/vessel detections that aren't true fibers. "
                        + "Lower it if wide artefacts slip through; set high (or to the max) to keep all. "
                        + "0 disables it. Applied after tracing, on the server."));

        extraParamsArea = new javafx.scene.control.TextArea();
        extraParamsArea.setPromptText(
                "Extra params as JSON, e.g.  {\"thresh_Dxlink\": 1.5, \"lam_dirdecay\": 0.5}");
        extraParamsArea.setPrefRowCount(3);
        extraParamsArea.setWrapText(true);
        extraParamsArea.setTooltip(ttip(
                "Override any FIRE 'value'-dict parameter; these win over the controls above. "
                        + "Live keys include thresh_Dxlink, lam_dirdecay, s_fiberdir, thresh_flen, "
                        + "s_maxspace, ang_interval, lambda, thresh_dang_L. LL1 (min length) and widMAX "
                        + "(max width) are applied server-side. NO effect in this FIRE-only build: "
                        + "num_scales, coefficient_percentile (curvelet), s_minstep, s_maxstep, "
                        + "thresh_short_L, thresh_numv. Unknown keys are ignored. See the Settings "
                        + "reference (Help links at the bottom of this dialog)."));

        thresholdSpinner.setTooltip(ttip(
                "Background cutoff in the image's NATIVE intensity units (the values you see "
                        + "when mousing over the image). Pixels at/below it are background. The "
                        + "label below shows how much of the region is above it."));
        updateThresholdFeedback();
        wirePreferences();
        minLenSpinner.setTooltip(ttip(
                "Fibers shorter than this are discarded after tracing (LL1), in "
                        + (calibrated ? "microns" : "pixels")
                        + ". Raise to drop noise specks/fragments; lower to keep short fibers."));
        seedSpacingSpinner.setTooltip(ttip(
                "IMPACT ON RESULTS: LOWER = more fibres detected and traces follow CURVES closely; "
                        + "HIGHER = fibres are MISSED entirely and curves flatten into straight chords "
                        + "that can cut across gaps. (Example: on one tile, 58→8 tripled the fibres "
                        + "found, 134→391.)\n"
                        + "This is the spacing of FIRE's nucleation points across the WHOLE image "
                        + "(thresh_LMPdist) — the granularity at which fibres are seeded AND the spacing "
                        + "of the vertices along each traced fibre. It is NOT a minimum separation "
                        + "between two distinct fibres. Too high and FIRE places too few points to land "
                        + "on every fibre or to bend around its curves.\n"
                        + "In " + (calibrated ? "microns" : "full-resolution pixels")
                        + " (converted to analysis pixels at run time, so it stays fixed when you change "
                        + "downsample). Lower costs more memory/time. Tip: set it near your fibre width "
                        + "or a fraction of your shortest fibre."));
        downsampleSpinner.setTooltip(ttip(
                "Resolution reduction before analysis: 2 = half resolution (~4× faster, coarser). "
                        + "Use 1 for small/high-mag regions; 2–8 for large regions. The engine is tuned "
                        + "for ~500 px images, so full-res whole slides need a higher value here. "
                        + "Preview and the committed result both use this value."));
        tacsZoneSpinner.setTooltip(ttip(
                "Fibers whose centre is within this distance of the boundary are TACS-classified; "
                        + "those farther away are left unclassified (" + (calibrated ? "microns" : "pixels")
                        + "). Widen to include more stroma; narrow to focus on the peri-tumoural rim."));

        // ===================================================================
        // Stage ① — Thresholding (what FIRE sees). All LIVE: changing any of
        // these re-renders the mask instantly, with NO server call / NO FIRE.
        // ===================================================================
        GridPane t1 = new GridPane();
        t1.setHgap(10);
        t1.setVgap(6);
        t1.setPadding(new Insets(8));
        t1.getColumnConstraints().addAll(labelCol(), fieldCol());
        for (var ctl : new javafx.scene.control.Control[] {
            channelBox, downsampleSpinner, smoothingSpinner, thresholdSpinner
        }) {
            ctl.setMaxWidth(Double.MAX_VALUE);
        }
        // Objective/NA preset: a one-click set of magnification-appropriate STARTING values
        // (derived from the image's pixel size). Selecting one fills in the size-dependent knobs.
        ChoiceBox<String> objectivePresetBox = new ChoiceBox<>();
        objectivePresetBox.getItems().addAll("Custom", "10× / NA 0.45", "20× / NA 0.75",
                "40× / NA 0.95", "63× / NA 1.4 (oil)");
        objectivePresetBox.getSelectionModel().select(0);
        objectivePresetBox.setMaxWidth(Double.MAX_VALUE);
        objectivePresetBox.setTooltip(ttip(
                "Sets Min fiber length, Seed spacing, Max fiber width and Link distance to sensible "
                + "starting points for the objective. Does NOT change Downsample (set that by eye "
                + "with the ① mask). A STARTING point only — preview and refine. Needs a calibrated "
                + "pixel size."));
        objectivePresetBox.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> {
            if (nv.intValue() >= 1) {
                applyObjectivePreset(nv.intValue());
            }
        });
        int r1 = 0;
        t1.addRow(r1++, lbl("Objective preset"), objectivePresetBox);
        t1.addRow(r1++, new Label("Collagen channel"), channelBox);
        t1.addRow(r1++, new Label("Downsample"), downsampleSpinner);
        t1.addRow(r1++, new Label("Tile size"), tileSizeLabel);
        t1.addRow(r1++, new Label("Smoothing (sigma, px)"), smoothingSpinner);
        t1.addRow(r1++, new Label("Background threshold"), thresholdSpinner);
        thresholdFeedback.setStyle("-fx-font-size: 11;");
        thresholdFeedback.setWrapText(true); // wrap instead of truncating with "..."
        t1.add(thresholdFeedback, 1, r1++);
        t1.add(twoPassCheck, 1, r1++);
        t1.addRow(r1++, new Label("2nd-pass threshold"), secondThresholdSpinner);
        // NOTE: ridgeCheck is intentionally NOT here — ridge replaces the image FIRE traces on
        // (a server-side fibre-ness map) and does NOT change this ① intensity mask, so it belongs
        // with the fibre-detection controls, not the thresholding section. Added to previewBar below.
        // Threshold-only "show in viewer": inspect the mask at full res WITHOUT running fibre
        // detection (instant), right here in the thresholding section where the eye already is.
        Button showThreshTileBtn = new Button("Show tile in viewer");
        showThreshTileBtn.setTooltip(ttip(
                "Show the THRESHOLD mask for the selected tile on the actual image at full resolution "
                        + "— instant, no fibre detection. When 'Two-pass' is on this previews the "
                        + "2ND-PASS threshold (the ① mask above already shows the main one), so you can "
                        + "raise it until a thick bundle breaks into its internal striations. "
                        + "Double-click a tile in the mask above to pick it; 'Clear overlay' "
                        + "clears it. (The image must be open in the viewer.)"));
        showThreshTileBtn.setOnAction(e -> showTileInViewer(false));
        showThreshTileBtn.setStyle(TILE_VIEW_AURA);
        t1.add(showThreshTileBtn, 1, r1++);
        t1.setMinWidth(360);
        mirrorTooltipsToLabels(t1);

        double previewCanvasH = PREVIEW_MAX * (double) rh / Math.max(1, rw);
        double previewViewportH = Math.min(PREVIEW_MAX, previewCanvasH) + 16;
        maskCanvas = new Canvas(PREVIEW_MAX, previewCanvasH);
        maskScroll = new ScrollPane(maskCanvas);
        maskScroll.setPrefSize(PREVIEW_MAX + 16, previewViewportH);
        Label maskHdr = new Label("Threshold mask — updates live (no analysis)");
        maskHdr.setStyle("-fx-font-weight: bold;");
        Label maskNote = new Label("Violet = the pixels FIRE will treat as collagen, given this "
                + "channel, downsample, smoothing and threshold.");
        maskNote.setWrapText(true);
        maskNote.setStyle("-fx-font-size: 11;");
        // Tile grid, selection and export live with the fiber preview (stage ②), where the
        // 'One tile (fast)' preview actually runs — created here, added to fiberBox below.
        maskTileLabel = new Label();
        maskTileLabel.setWrapText(true);
        maskTileLabel.setStyle("-fx-font-size: 11; -fx-font-weight: bold;");
        Button exportTileBtn = new Button("Export tile…");
        exportTileBtn.setTooltip(ttip(
                "Save the EXACT pixels of the selected tile (the prescaled gray image FIRE analyses) "
                        + "as a PNG. The filename includes its x,y position in the image — useful for "
                        + "reproducing a crash or sharing a problem region."));
        exportTileBtn.setOnAction(e -> exportTile());
        Button viewTileBtn = new Button("Show tile in viewer");
        viewTileBtn.setTooltip(ttip(
                "Detect the selected tile and show its fibres on the ACTUAL image in the QuPath "
                        + "viewer, zoomed to that tile — full resolution, native zoom/pan, so you can "
                        + "actually judge the collagen. 'Clear overlay' clears the overlay. "
                        + "(The image must be open in the active viewer.)"));
        viewTileBtn.setOnAction(e -> showTileInViewer(true));
        viewTileBtn.setStyle(TILE_VIEW_AURA);
        thicknessWidthCheck = new CheckBox("Line width = fiber thickness");
        thicknessWidthCheck.setWrapText(true);
        thicknessWidthCheck.setTooltip(ttip(
                "In the 'Show tile in viewer' overlay (and the live 'Apply to chosen area' preview), draw each "
                        + "fibre at its ESTIMATED thickness — twice FIRE's distance-map radius at the "
                        + "fibre's vertices — instead of a constant thin line. Thick collagen bundles then "
                        + "render as thick lines and fine fibres as thin ones; zoom in to see it. Purely a "
                        + "display option — it does not change detection or the saved objects."));
        thicknessWidthCheck.selectedProperty().addListener((o, ov, nv) -> {
            FiberPreviewOverlay lo = liveOverlay;
            if (lo != null) {
                lo.setWidthByThickness(nv);
                QuPathViewer vw = liveOverlayViewer;
                if (vw != null) {
                    vw.repaint();
                }
            }
        });
        clearOverlayThreshBtn = makeClearOverlayButton();
        VBox maskBox = new VBox(4, maskHdr, maskScroll, maskNote, maskTileLabel, clearOverlayThreshBtn);
        maskBox.setFillWidth(false);
        HBox sec1 = new HBox(14, t1, maskBox);
        sec1.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(t1, Priority.ALWAYS);
        HBox.setHgrow(maskBox, Priority.NEVER);
        javafx.scene.control.TitledPane tp1 =
                new javafx.scene.control.TitledPane(
                        "①  Thresholding  (what FIRE sees — collapse once it's set)", sec1);
        tp1.setCollapsible(true);
        tp1.setExpanded(true);

        // ===================================================================
        // Stage ② — Fiber detection. The expensive step: clicking "Preview
        // fibers" runs FIRE on the region (server round-trip, seconds).
        // ===================================================================
        GridPane t2 = new GridPane();
        t2.setHgap(10);
        t2.setVgap(6);
        t2.setPadding(new Insets(8));
        t2.getColumnConstraints().addAll(labelCol(), fieldCol());
        for (var ctl : new javafx.scene.control.Control[] {
            previewScopeBox, seedSpacingSpinner, seedSensSpinner, xlinkBoxSpinner,
            bendAngleSpinner, angleExtendSpinner, linkAngleSpinner,
            minLenSpinner, fiberModeBox, tacsZoneSpinner
        }) {
            ctl.setMaxWidth(Double.MAX_VALUE);
        }
        Label depNote = new Label("Detection also uses the ① settings above (downsample, smoothing, "
                + "threshold) — those define the pixels it runs on.");
        depNote.setWrapText(true);
        depNote.setStyle("-fx-font-size: 11; -fx-font-style: italic;");
        // Visible note (not a tooltip): our Max link angle uses a 0–90° deviation, which
        // differs from CT-FIRE's thresh_linka convention (180−θ).
        Label linkAngleNote = new Label("Max link angle = deviation from straight (0–90°). "
                + "Differs from CT-FIRE thresh_linka (180−θ): 30° here ≡ CT-FIRE 150.");
        linkAngleNote.setWrapText(true);
        linkAngleNote.setStyle("-fx-font-size: 10; -fx-font-style: italic;");
        // Preview scope moved to the Preview action bar (it controls the Preview button, not
        // detection). The commit options (whole-image / detections / clear-existing) moved to the
        // Output options group near "Apply to chosen area". What stays here are the detection knobs.
        int r2 = 0;
        // Tracer: how fibres are found. FIRE seeds and extends (CT-FIRE's method, comparable to the
        // literature); Centerline takes the medial axis of the thresholded collagen, which keeps
        // crimp/waviness and sharp kinks and cannot produce nucleation "stars".
        tracerCombo = new javafx.scene.control.ComboBox<>();
        tracerCombo.getItems().addAll(TRACER_FIRE, TRACER_CENTERLINE, TRACER_FLOW);
        flowParallelSpinner = spinnerD(0.5, 50.0, 3.0, 0.5);
        flowParallelSpinner.setTooltip(ttip(
                "Flow tracer only. The minimum sideways spacing between traced fibres running "
                + "through the SAME bundle. A thick bundle really does contain many fibres, so "
                + "tracing dozens through one is not an error \u2014 but it would also sink QuPath, "
                + "so this sets how densely the bundle is sampled. LOWER traces more fibres per "
                + "bundle (3 \u00b5m \u2248 1300 fibres on a dense tile, 1 \u00b5m \u2248 4300); "
                + "HIGHER traces fewer, longer-spaced ones. NOTE fibre COUNT and DENSITY scale with "
                + "this, so they sample the bundle rather than measure it; per-fibre length, angle "
                + "and waviness are unaffected."));
        tracerCombo.setValue(TRACER_FIRE);
        tracerCombo.setMaxWidth(Double.MAX_VALUE);
        tracerCombo.setPrefWidth(150);
        tracerCombo.setTooltip(ttip("How fibres are traced.\n\n"
                + "FIRE (seed & extend) — CT-FIRE's own method: seeds nucleation points and extends them "
                + "along the distance map. Keep this when you need results comparable to CT-FIRE.\n\n"
                + "Centerline (medial axis) — skeletonises the thresholded collagen and follows each "
                + "bundle's centre line, so crimp/waviness and sharp kinks are preserved and it cannot "
                + "make radial 'star' artifacts. Better for waviness measurements; NOT the CT-FIRE "
                + "algorithm, so numbers are not directly comparable to CT-FIRE literature.\n\n"
                + "Both produce the same measurements (length, angle, straightness, width mean/max/SD) "
                + "and both work with Preview."));
        rememberCombo(tracerCombo, FiberSocketPreferences.tracerProperty());
        rememberSpinner(flowParallelSpinner, FiberSocketPreferences.flowParallelProperty());
        t2.addRow(r2++, lbl("Tracer"), tracerCombo);
        javafx.scene.control.Label flowParLbl = lbl("Parallel fibre distance");
        t2.addRow(r2++, flowParLbl, flowParallelSpinner);
        // Only meaningful for the flow tracer; grey it out otherwise rather than hide it, so the
        // control does not jump around when the tracer changes.
        Runnable syncFlowPar = () -> {
            boolean on = TRACER_FLOW.equals(tracerCombo.getValue());
            flowParallelSpinner.setDisable(!on);
            flowParLbl.setDisable(!on);
        };
        tracerCombo.valueProperty().addListener((o, a2, b2) -> syncFlowPar.run());
        syncFlowPar.run();
        // Detection presets: one pick sets several tracing knobs at once (a tuned starting point the
        // user then refines). "Custom" is the leave-as-is state; editing any preset-controlled knob
        // returns the box to "Custom". Named user-saved presets can slot into this list later.
        presetCombo = new javafx.scene.control.ComboBox<>();
        presetCombo.getItems().addAll(PRESET_CUSTOM, "Straight fibers", "Balanced", "Wavy / crimped collagen");
        presetCombo.setValue(PRESET_CUSTOM);
        presetCombo.setMaxWidth(Double.MAX_VALUE);
        // Pin a modest preferred width so the longest item ("Wavy / crimped collagen") can't inflate
        // the form column's preferred width on the first layout pass (it fills via maxWidth anyway).
        presetCombo.setPrefWidth(150);
        presetCombo.setTooltip(ttip("Preset starting points for tracing. Picking one changes SEVERAL "
                + "settings below at once (Direction decay, Max bend angle, Seed spacing), then you can "
                + "fine-tune. 'Custom' leaves everything as-is; editing any of those settings switches "
                + "back to 'Custom'. Straight = clean straight fibres; Wavy = follows crimp (denser "
                + "seeding, more curvature — pair with the flow filter to keep it clean)."));
        Label presetWarn = new Label("⚠ Selecting a preset changes several settings below.");
        presetWarn.setWrapText(true);
        presetWarn.setStyle("-fx-font-size: 11; -fx-text-fill: -fx-accent;");
        presetCombo.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null && !PRESET_CUSTOM.equals(nv)) applyDetectionPreset(nv);
        });
        Runnable toCustom = () -> { if (!applyingPreset) presetCombo.setValue(PRESET_CUSTOM); };
        dirDecaySpinner.valueProperty().addListener((o, a, b) -> toCustom.run());
        bendAngleSpinner.valueProperty().addListener((o, a, b) -> toCustom.run());
        seedSpacingSpinner.valueProperty().addListener((o, a, b) -> toCustom.run());
        t2.addRow(r2++, lbl("Detection preset"), presetCombo);
        t2.add(presetWarn, 0, r2++, 2, 1);
        t2.add(depNote, 0, r2++, 2, 1);
        t2.addRow(r2++, lbl("Seed spacing (" + unitLabel() + ", lower = denser seeding)"), seedSpacingSpinner);
        t2.addRow(r2++, lbl("Seed sensitivity (lower = more fibers)"), seedSensSpinner);
        t2.addRow(r2++, lbl("Cross-link search box (" + unitLabel() + ")"), xlinkBoxSpinner);
        t2.addRow(r2++, lbl("Max bend angle (deg, higher = curvier fibers)"), bendAngleSpinner);
        t2.addRow(r2++, lbl("Angle-extend threshold (deg)"), angleExtendSpinner);
        t2.addRow(r2++, lbl("Max link angle (deg, higher = links sharper turns)"), linkAngleSpinner);
        t2.add(linkAngleNote, 0, r2++, 2, 1);
        t2.addRow(r2++, lbl("Min fiber length (" + unitLabel() + ")"), minLenSpinner);
        t2.addRow(r2++, lbl("Fiber mode"), fiberModeBox);
        t2.add(sep(), 0, r2++, 2, 1);
        t2.add(tacsCheck, 0, r2++, 2, 1);
        t2.addRow(r2++, lbl("Boundary annotation"), boundaryBox);
        t2.addRow(r2++, lbl("TACS zone width (" + unitLabel() + ")"), tacsZoneSpinner);

        // Advanced (collapsible) — the rest of the detection knobs.
        GridPane adv = new GridPane();
        adv.setHgap(10);
        adv.setVgap(6);
        adv.setPadding(new Insets(6, 0, 0, 0));
        adv.getColumnConstraints().addAll(labelCol(), fieldCol());
        for (var ctl : new javafx.scene.control.Control[] {
            distSmoothSpinner, linkDistSpinner, maxWidthSpinner,
            dxlinkSpinner, dirDecaySpinner, fiberDirSpinner, fireFlenSpinner,
            maxSpaceSpinner, angIntervalSpinner, lambdaSpinner, dangLenSpinner
        }) {
            ctl.setMaxWidth(Double.MAX_VALUE);
        }
        int ar = 0;
        String u = unitLabel();
        adv.addRow(ar++, new Label("Distance smoothing (sigma, px)"), distSmoothSpinner);
        adv.addRow(ar++, new Label("Link distance (" + u + ")"), linkDistSpinner);
        adv.addRow(ar++, new Label("Max fiber width (" + u + ")"), maxWidthSpinner);
        adv.addRow(ar++, new Label("Nucleation threshold"), dxlinkSpinner);
        adv.addRow(ar++, new Label("Direction decay"), dirDecaySpinner);
        adv.addRow(ar++, new Label("Direction window (" + u + ")"), fiberDirSpinner);
        adv.addRow(ar++, new Label("FIRE min length (" + u + ")"), fireFlenSpinner);
        adv.addRow(ar++, new Label("Interp. spacing (" + u + ")"), maxSpaceSpinner);
        adv.addRow(ar++, new Label("Angle interval (deg)"), angIntervalSpinner);
        adv.addRow(ar++, new Label("Beam regularisation"), lambdaSpinner);
        adv.addRow(ar++, new Label("Dangling prune (" + u + ")"), dangLenSpinner);
        adv.add(ridgeCheck, 0, ar++, 2, 1); // experimental detection MODE — tucked in Advanced
        Label extraLabel = new Label("Extra params (JSON)");
        extraLabel.setWrapText(true);
        adv.add(extraLabel, 0, ar);
        adv.add(extraParamsArea, 1, ar++);
        // Advanced settings live in their OWN full-width collapsible row below stage ② (added to
        // rootBox), not nested in the left column — so expanding it grows the dialog downward
        // across the full width instead of leaving a tall empty gap beside the preview.
        mirrorTooltipsToLabels(adv);
        javafx.scene.control.TitledPane advPane =
                new javafx.scene.control.TitledPane("Advanced FIRE settings (rarely needed)", adv);
        advPane.setExpanded(false);
        advPane.setMaxWidth(Double.MAX_VALUE);

        // Advanced SERVER settings: the guard/cleanup knobs added recently (density guard, seed-count
        // guard, per-tile timeout, jump-cut + edge-trim). Sent in the ANALYZE meta; the server falls
        // back to its env/defaults when a value isn't sent.
        densityCapSpinner = spinnerD(1.0, 90.0, 35.0, 1.0);
        seedCapSpinner = spinnerD(500.0, 100000.0, 15000.0, 500.0);
        webCapSpinner = spinnerD(0.0, 2000000.0, 200000.0, 25000.0);
        antiFlowCheck = new CheckBox("Anti-flow check (adds a per-fibre score)");
        antiFlowCheck.setWrapText(true);
        antiFlowCheck.setTooltip(ttip(
                "Score every fibre against an INDEPENDENT estimate of the local collagen direction "
                + "and add it as an \"Anti-flow (deg)\" measurement: ~0 means the fibre runs along "
                + "the collagen, toward 90 means across it. Nothing is removed \u2014 sort by this "
                + "column to review the fibres the pipeline is least sure about. Independent because "
                + "it uses a different operator from the one the tracer followed; checking a tracer "
                + "against its own field only ever agrees with it, which is how a boundary artifact "
                + "went unnoticed. Costs a second pass over each tile."));
        fireTimeoutSpinner = spinnerD(10.0, 600.0, 180.0, 10.0);
        splitJumpMultSpinner = spinnerD(1.5, 20.0, 4.0, 0.5);
        splitGapFracSpinner = spinnerD(0.1, 0.9, 0.4, 0.05);
        edgeTrimSpinner = spinnerD(0.0, 20.0, 3.0, 1.0);
        flowFilterCheck = new CheckBox("Remove cross-flow fibres");
        flowFilterCheck.setSelected(true);
        flowFilterCheck.setTooltip(ttip("Flow filter. Where the collagen is strongly oriented, a real "
                + "fibre runs ALONG the flow; this removes fibres that run ACROSS it (wide-bundle crossers, "
                + "asterisk spokes, perpendicular crossbars) regardless of length. A WAVY fibre follows its "
                + "local curving flow, so it is KEPT. Use the coherence gate below to control how strongly "
                + "oriented the tissue must be before this acts."));
        flowCohSpinner = spinnerD(0.35, 0.85, 0.55, 0.05);
        flowCohSpinner.setTooltip(ttip("Flow filter safety dial (structure-tensor coherence). Only act "
                + "where local orientation is at least this strong. HIGHER = only very aligned bundles are "
                + "cleaned, mixed/random tissue is left alone (safer, keeps more). LOWER = cleans more "
                + "aggressively but may cut real fibres in weakly-oriented tissue. Default 0.55."));
        densityCapSpinner.setTooltip(ttip("Density guard: if more than this % of a tile is above the "
                + "threshold, the server raises the threshold BEFORE tracing to avoid a dense-tile "
                + "crash — but that DROPS fibres on dense collagen (they fall below the raised "
                + "threshold). RAISE this for collagen-heavy samples so all of it is traced (worker "
                + "isolation contains any crash — the tile is skipped, the server survives); LOWER it "
                + "only if the server is crashing. Default 35%."));
        seedCapSpinner.setTooltip(ttip("Seed-count guard: if a tile would seed more than this many "
                + "nucleation points, the server raises Seed spacing before tracing so the extension "
                + "step stays tractable — but that COARSENS dense tiles (fewer/straighter fibres, and it "
                + "undoes the Wavy preset's dense seeding). RAISE it for dense/crimped collagen; LOWER "
                + "only if dense tiles hang. Default 15000."));
        webCapSpinner.setTooltip(ttip("Connected-web guard: when a tile's collagen forms ONE "
                + "connected network bigger than this (measured as the skeleton length of the largest "
                + "connected piece, in px), FIRE can walk the whole tile from any seed and its linking "
                + "step has to hold all of it — the tile then runs to the timeout and returns nothing. "
                + "The server raises the threshold BEFORE tracing until the web breaks apart. Tiles that "
                + "trace normally measure 8k–105k here; tiles that time out measure 340k+. RAISE it to "
                + "intervene less (and risk timeouts); LOWER it to intervene sooner, at the cost of "
                + "dropping fibres below the raised threshold. 0 disables. Default 200000. Note the "
                + "Centerline tracer has no linking step, so this guard never applies to it."));
        fireTimeoutSpinner.setTooltip(ttip("Per-tile FIRE time limit (seconds). A tile that exceeds it "
                + "is skipped (the worker respawns, the server stays up). Default 180."));
        splitJumpMultSpinner.setTooltip(ttip("Jump-cut sensitivity: a fibre edge longer than this "
                + "multiple of the fibre's median step is cut as a spurious jump. Lower = cuts more "
                + "aggressively. Default 4."));
        splitGapFracSpinner.setTooltip(ttip("Jump-cut over background: an edge is cut when at least "
                + "this fraction of it lies below threshold. Lower = cuts more readily. Default 0.4."));
        edgeTrimSpinner.setTooltip(ttip("Tile-seam cleanup. The image is analysed one tile at a time. "
                + "Where a real fibre is cut by a tile's edge, the tracer often adds a short false segment "
                + "that runs straight ALONG that cut (a stub square to the true fibre). This removes any "
                + "fibre segment lying within N pixels of a tile edge; the rest of the fibre is kept. "
                + "0 = off. Default 3."));
        GridPane srv = new GridPane();
        srv.setHgap(10);
        srv.setVgap(6);
        srv.setPadding(new Insets(6, 0, 0, 0));
        srv.getColumnConstraints().addAll(labelCol(), fieldCol());
        for (var ctl : new javafx.scene.control.Control[] {densityCapSpinner, seedCapSpinner,
                webCapSpinner, fireTimeoutSpinner, splitJumpMultSpinner, splitGapFracSpinner,
                edgeTrimSpinner, flowCohSpinner}) {
            ctl.setMaxWidth(Double.MAX_VALUE);
        }
        rawModeCheck = new CheckBox("Raw FIRE — bypass all cleanup/guards");
        rawModeCheck.setWrapText(true);
        rawModeCheck.setTooltip(ttip("Run FIRE / tme-quant EXACTLY as a plain Python run would, with none "
                + "of this extension's own guards, retries or cleanup: no density guard, no seed-count "
                + "guard, no dense-tile retry, no ridge pre-filter, no bridge-split, no tile-seam trim, no "
                + "cross-bar removal. Only tme-quant's own steps stay (min length, max width) plus the "
                + "transport basics (16-bit prescale, square-padding for the FIRE stride bug). If a tile "
                + "crashes or times out it simply yields NO fibres for that tile (no retry, nothing "
                + "changed). Use this to reproduce/validate what a specific tile does in raw FIRE. The "
                + "settings below have NO effect while this is on."));
        int sr = 0;
        srv.add(rawModeCheck, 0, sr++, 2, 1);
        srv.addRow(sr++, new Label("Density guard cap (% fg)"), densityCapSpinner);
        srv.addRow(sr++, new Label("Seed-count cap"), seedCapSpinner);
        srv.addRow(sr++, new Label("Connected-web cap (skel px)"), webCapSpinner);
        rememberCheck(antiFlowCheck, FiberSocketPreferences.antiFlowProperty());
        rememberSpinner(webCapSpinner, FiberSocketPreferences.webCapProperty());
        srv.add(antiFlowCheck, 0, sr++, 2, 1);
        srv.addRow(sr++, new Label("Tile timeout (s)"), fireTimeoutSpinner);
        srv.addRow(sr++, new Label("Jump-cut length multiple"), splitJumpMultSpinner);
        srv.addRow(sr++, new Label("Jump-cut gap fraction"), splitGapFracSpinner);
        srv.addRow(sr++, new Label("Tile-seam trim (px)"), edgeTrimSpinner);
        srv.add(flowFilterCheck, 0, sr++, 2, 1);
        srv.addRow(sr++, new Label("Flow filter coherence gate"), flowCohSpinner);
        mirrorTooltipsToLabels(srv);
        // The guard/cleanup spinners do nothing in Raw mode -- grey them out so that is obvious.
        Runnable syncRawMode = () -> {
            boolean raw = rawModeCheck.isSelected();
            for (var ctl : new javafx.scene.control.Control[] {densityCapSpinner, seedCapSpinner,
                    webCapSpinner, splitJumpMultSpinner, splitGapFracSpinner, edgeTrimSpinner,
                    flowFilterCheck, flowCohSpinner}) {
                ctl.setDisable(raw);
            }
            // Tile timeout still applies (it is the crash-containment that yields an empty tile).
            if (ridgeCheck != null) {
                ridgeCheck.setDisable(raw); // ridge pre-filter is skipped in raw mode
            }
        };
        rawModeCheck.selectedProperty().addListener((o, ov, nv) -> syncRawMode.run());
        syncRawMode.run();
        javafx.scene.control.TitledPane serverPane =
                new javafx.scene.control.TitledPane("Advanced server settings (guards & cleanup)", srv);
        serverPane.setExpanded(false);
        serverPane.setMaxWidth(Double.MAX_VALUE);

        t2.setMinWidth(360);
        mirrorTooltipsToLabels(t2);

        canvas = new Canvas(PREVIEW_MAX, previewCanvasH);
        canvasScroll = new ScrollPane(canvas);
        // Both previews are linked: scroll-wheel zooms, click-drag pans, double-click picks
        // the tile "One tile (fast)" runs — so you don't bounce up to the threshold mask.
        wirePreviewMouse(maskCanvas, maskScroll);
        wirePreviewMouse(canvas, canvasScroll);
        canvasScroll.setPrefSize(PREVIEW_MAX + 16, previewViewportH);
        Label fiberHdr = new Label("Fiber detection preview");
        fiberHdr.setStyle("-fx-font-weight: bold;");
        previewBtn = new Button("Preview fibers  ▶");
        previewBtn.setOnAction(e -> runPreview());
        // Shown only when the region is too large to threshold whole at the selected downsample:
        // clears the picked-tile faithful mask so the canvas shows just the (overlay-free) thumbnail.
        backToFullBtn = makeClearOverlayButton();
        // Preview action bar on TWO rows so the full button labels fit at the normal dialog width:
        // row 1 = run the preview; row 2 = view the selected tile / export.
        HBox previewRow1 = new HBox(8, new Label("Preview:"), previewScopeBox, previewBtn);
        previewRow1.setAlignment(Pos.CENTER_LEFT);
        HBox previewRow2 = new HBox(8, viewTileBtn, backToFullBtn, exportTileBtn);
        previewRow2.setAlignment(Pos.CENTER_LEFT);
        HBox previewRow3 = new HBox(8, thicknessWidthCheck);
        previewRow3.setAlignment(Pos.CENTER_LEFT);
        VBox previewBar = new VBox(6, previewRow1, previewRow2, previewRow3);
        previewBar.setPadding(new Insets(6));
        previewBar.setStyle("-fx-border-color: -fx-box-border; -fx-border-radius: 4; "
                + "-fx-background-radius: 4;");
        Label runNote = new Label("Runs FIRE on the region (a few seconds). "
                + "'One tile (fast)' does the highlighted tile (double-click the preview above to "
                + "pick one); 'Whole region' does all tiles + stitch + TACS. "
                + "Scroll to zoom either preview, drag to pan — both stay in sync.");
        runNote.setWrapText(true);
        runNote.setStyle("-fx-font-size: 11;");
        statusLabel = new Label(String.format(
                "Region %d x %d px at (%d, %d)%s. Click 'Preview fibers' to run detection.",
                rw, rh, rx, ry,
                calibrated ? String.format(" - pixel size %.4f um", pixelSizeMicrons) : " - uncalibrated"));
        statusLabel.setWrapText(true);
        VBox fiberBox = new VBox(6, fiberHdr, canvasScroll, previewBar,
                runNote, statusLabel);
        fiberBox.setFillWidth(false);
        HBox sec2 = new HBox(14, t2, fiberBox);
        sec2.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(t2, Priority.ALWAYS);
        HBox.setHgrow(fiberBox, Priority.NEVER);
        javafx.scene.control.TitledPane tp2 =
                new javafx.scene.control.TitledPane("②  Fiber detection", sec2);
        tp2.setCollapsible(false);

        Label hint = new Label(
                "Region = bounding box of your selection. For TACS, select a LARGER region, "
                        + "then pick the tumour annotation (inside it) as the boundary.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11;");

        // --- Parameter tuning (Suggest): the Suggest button lives WITH its options here, not in
        // the footer, so the trigger and the settings that govern it are one unit. ---
        Label tuneInstr = new Label(
                "Suggest searches the detection knobs you can't preview live (seed spacing, seed "
                + "sensitivity, link distance, nucleation, then bend angle, link angle, cross-link "
                + "box) in two stages — count first, geometry second; threshold, smoothing and "
                + "downsample stay fixed at your ① values. Steps: 1) select one or more AREA "
                + "annotations (pool several sparse regions instead of annotating densely); "
                + "2) trace the real fibers inside them as LINE annotations (≥ ~8 total); "
                + "3) click 'Suggest parameters…' — the search matches that pooled traced count.");
        tuneInstr.setWrapText(true);
        tuneInstr.setStyle("-fx-font-size: 11;");
        countTolSpinner.setMaxWidth(Double.MAX_VALUE);
        maxRoundsSpinner.setMaxWidth(Double.MAX_VALUE);
        GridPane tuneGrid = new GridPane();
        tuneGrid.setHgap(10);
        tuneGrid.setVgap(6);
        tuneGrid.getColumnConstraints().addAll(labelCol(), fieldCol());
        int tr = 0;
        tuneGrid.addRow(tr++, lbl("Count tolerance (±%)"), countTolSpinner);
        tuneGrid.addRow(tr++, lbl("Refinement rounds"), maxRoundsSpinner);
        tuneGrid.addRow(tr++, lbl("Disjoint pieces"), pieceWeightBox);
        mirrorTooltipsToLabels(tuneGrid);
        suggestBtn = new Button("Suggest parameters…");
        suggestBtn.setTooltip(ttip(
                "Two-stage search of the FIBER-DETECTION parameters — stage A: seed spacing, seed "
                + "sensitivity, link distance, nucleation (count); stage B: bend angle, link angle, "
                + "cross-link box (geometry) — ranked. Threshold, smoothing and downsample are held "
                + "fixed at your current values — set those by eye with the ① mask first.\n"
                + "For best results, trace ALL fibers inside the annotation as LINE annotations "
                + "(the search matches that count). With no lines it falls back to coverage + "
                + "straightness.\nTip: keep the region small — every combo is a real FIRE run."));
        suggestBtn.setOnAction(e -> runSuggest());
        Button calibBtn = new Button("Calibrate from traced lines");
        calibBtn.setTooltip(ttip(
                "Measure the LINE annotations you traced inside the AREA and set Min fiber length "
                + "and Seed spacing from their actual lengths — the fastest way to stop real fibers "
                + "being dropped. Run this first, preview to check, then 'Suggest parameters…' to refine."));
        calibBtn.setOnAction(e -> calibrateFromTracedLines());
        Button projectTuneBtn = new Button("Tune across project…");
        projectTuneBtn.setTooltip(ttip(
                "Pool ground truth from area annotations of a training class (e.g. 'Training') across "
                + "MANY project images, under one consistent intensity scale, and find a single "
                + "parameter set. Encouraged for automation: annotate + trace fibers in several "
                + "regions across the project, then tune once."));
        projectTuneBtn.setOnAction(e -> openProjectTuningDialog());
        Button testExportBtn = new Button("FULL TEST-EXPORT (tile)");
        testExportBtn.setTooltip(ttip(
                "DEBUG: run FIRE on the currently selected tile and dump every stage we can reach into "
                + "<project>/test/export_tile#_<datetime>/ — the exact grey image FIRE received, its "
                + "threshold mask (missed fibres show here first), the distance map that drives the "
                + "tracer's jumps, the traced-fibre overlay, plus params.json and a fibres CSV/JSON. "
                + "Needs a project. For diagnosing missed fibres / stray jumps on one tile."));
        testExportBtn.setOnAction(e -> runFullTestExport());
        HBox suggestRow = new HBox(8, calibBtn, suggestBtn, projectTuneBtn);
        suggestRow.setAlignment(Pos.CENTER_LEFT);
        HBox testExportRow = new HBox(8, testExportBtn);
        testExportRow.setAlignment(Pos.CENTER_LEFT);
        // Local result line: Calibrate/Suggest also drive the main status up in ② (where the
        // changed spinners are), but that's far from these buttons — so echo the outcome HERE,
        // right under the button you clicked, so it never looks like nothing happened.
        tuneFeedback = new Label();
        tuneFeedback.setWrapText(true);
        tuneFeedback.setStyle("-fx-font-size: 11;");
        tuneFeedback.setManaged(false);
        tuneFeedback.setVisible(false);
        VBox tuneBox = new VBox(8, tuneInstr, tuneGrid, suggestRow, testExportRow, tuneFeedback);
        tuneBox.setPadding(new Insets(6));
        javafx.scene.control.TitledPane tunePane =
                new javafx.scene.control.TitledPane("Parameter tuning (Suggest)", tuneBox);
        tunePane.setExpanded(false);

        // --- Output options: everything that changes what "Apply to chosen area" does, grouped with the
        // Add button so the commit-time switches aren't scattered through the detection knobs. ---
        VBox outputBox = borderedGroup("Output options", 6,
                wholeImageCheck, detectionsCheck, annotationWarnLabel, clearExistingCheck, maskToObjectCheck,
                saveLogCheck, hint);

        addBtn = new Button("Apply to chosen area");
        addBtn.setOnAction(e -> commit(stage));
        // Deliberately NOT a default button: pressing Enter (e.g. after typing a threshold) must
        // never start the analysis — it should only apply the value the user typed.
        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.close());
        // Keep the dialog above QuPath's main window. JavaFX's alwaysOnTop is the only
        // portable hook and it floats above all windows (not just QuPath), so make it an
        // explicit, off-by-default toggle.
        ToggleButton pinBtn = new ToggleButton("Pin on top");
        pinBtn.setTooltip(ttip(
                "Keep this dialog floating on top so it isn't hidden behind QuPath while you "
                        + "select regions. (Floats above all windows, not only QuPath.)"));
        pinBtn.selectedProperty().addListener((o, ov, nv) -> stage.setAlwaysOnTop(nv));
        // OS-correct ordering and natural button widths: Pin is a utility toggle (leading edge);
        // Add is the primary commit (trailing, default); Close cancels.
        ButtonBar bottom = new ButtonBar();
        ButtonBar.setButtonData(pinBtn, ButtonBar.ButtonData.LEFT);
        ButtonBar.setButtonData(addBtn, ButtonBar.ButtonData.APPLY);
        ButtonBar.setButtonData(closeBtn, ButtonBar.ButtonData.CANCEL_CLOSE);
        bottom.getButtons().addAll(pinBtn, addBtn, closeBtn);

        // Prominent run status/ETA, right where the eye is when clicking Apply to chosen area. Hidden
        // until a run starts; mirrors the per-tile status shown under the preview.
        runStatusBar = new Label();
        runStatusBar.setWrapText(true);
        runStatusBar.setVisible(false);
        runStatusBar.setManaged(false);
        runStatusBar.setStyle("-fx-text-fill: #b35900; -fx-font-weight: bold;");

        // Documentation links: the full guide (README) and the per-setting reference.
        Hyperlink docLink = new Hyperlink("Guide & install (README)");
        docLink.setOnAction(e -> QuPathGUI.openInBrowser(DOC_README));
        Hyperlink settingsLink = new Hyperlink("Collagen-detection settings reference");
        settingsLink.setOnAction(e -> QuPathGUI.openInBrowser(DOC_SETTINGS));
        Label docPrefix = new Label("Documentation:");
        docPrefix.setStyle("-fx-font-size: 11;");
        // Export/import the full detection config to a shareable .json (reproducibility, and a
        // safety net so a preset / Suggest doesn't permanently lose a hand-tuned configuration).
        Button saveSettingsBtn = new Button("Save settings…");
        saveSettingsBtn.setTooltip(ttip(
                "Save every detection setting to a .json file you can re-load later or share with "
                + "collaborators. Distances are stored in the current unit (µm/px)."));
        saveSettingsBtn.setOnAction(e -> saveSettings(stage));
        Button loadSettingsBtn = new Button("Load settings…");
        loadSettingsBtn.setTooltip(ttip(
                "Load detection settings from a .json file saved earlier. The ① mask refreshes so "
                + "you can confirm. Threshold/channel are image-specific — re-check them."));
        loadSettingsBtn.setOnAction(e -> loadSettings(stage));
        HBox docBar = new HBox(4, docPrefix, docLink, new Label("·"), settingsLink,
                hSpacer(), saveSettingsBtn, loadSettingsBtn);
        docBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // Project-wide row: apply the current params across many images, and reload the open image
        // after a batch wrote results to its data file.
        Button runProjectBtn = new Button("Run across project…");
        runProjectBtn.setTooltip(ttip(
                "Apply the CURRENT parameters to whole images or class annotations across selected "
                + "project images, under one consistent scale, writing Fiber objects into each "
                + "image's data file. Tune first."));
        runProjectBtn.setOnAction(e -> openBatchAnalysisDialog());
        Button reloadBtn = new Button("Reload data");
        reloadBtn.setTooltip(ttip(
                "Re-read the currently-open image's data file into the viewer, to show results a "
                + "batch wrote to it."));
        reloadBtn.setOnAction(e -> reloadCurrentImage());
        Label projLbl = new Label("Project:");
        projLbl.setStyle("-fx-font-size: 11;");
        HBox projectBar = new HBox(8, projLbl, runProjectBtn, reloadBtn);
        projectBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // Stage ⓪ — regions to leave alone. Sits ABOVE thresholding because it describes the INPUT
        // (what is not collagen at all), not a detection setting. Collapsed by default: most runs
        // have nothing to exclude, and an expanded empty section would just push stage ① down.
        exclusionSection = new ExclusionSection(stage, this::projectDirFile);
        exclusionSection.setImageData(imageData);
        javafx.scene.control.TitledPane tp0 = new javafx.scene.control.TitledPane(
                "⓪  Exclude regions  (optional — nuclei, blobs, anything not collagen)",
                exclusionSection);
        tp0.setCollapsible(true);
        tp0.setExpanded(false);

        // Order follows the task flow: exclude → threshold → detect → (advanced) → tune → commit.
        // advPane is full-width here (not nested in stage ②) so expanding it grows the dialog
        // downward, never beside the preview.
        VBox rootBox = new VBox(10, tp0, tp1, tp2, advPane, serverPane, tunePane, outputBox, runStatusBar,
                bottom, projectBar, docBar);
        rootBox.setPadding(new Insets(10));
        ScrollPane rootScroll = new ScrollPane(rootBox);
        rootScroll.setFitToWidth(true);
        stage.setScene(new Scene(rootScroll));
        // Fixed-comfortable width (the fixed-size previews set the natural width; stretching buys
        // nothing); cap height to the screen and rely on the root scroll for tall expansions.
        stage.setMinWidth(820);
        stage.setWidth(900);
        double screenH = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
        stage.setHeight(Math.min(960, screenH - 80));
        stage.setMaxHeight(screenH);

        // Stay in sync with QuPath: follow selection (region) and hierarchy (boundary list)
        // changes live, and detach the listeners when the dialog closes.
        hierarchyListener = event -> {
            if (event != null && event.isChanging()) {
                return; // wait for the final event in a batch
            }
            Platform.runLater(this::onHierarchyChanged);
        };
        selectionListener = (sel, prev, all) -> Platform.runLater(this::onSelectionChanged);
        hierarchy.addListener(hierarchyListener);
        hierarchy.getSelectionModel().addPathObjectSelectionListener(selectionListener);
        // Follow QuPath's active image: when the user switches images, rebind the whole dialog
        // (region, calibration, channels, boundary list, stats, mask) to the new one.
        imageChangeListener = (obs, oldImg, newImg) -> Platform.runLater(() -> rebindToImage(newImg));
        qupath.imageDataProperty().addListener(imageChangeListener);
        // Keep the read-only "Tile size" display in sync when the max-tile-size Preference
        // is edited while the dialog is open.
        maxTileDimListener = (obs, ov, nv) -> Platform.runLater(this::updateTileSizeLabel);
        FiberSocketPreferences.maxTileDimProperty().addListener(maxTileDimListener);
        stage.setOnHidden(e -> {
            saveLastSettings(); // remember the final settings for this image, whatever the user did
            if (hierarchy != null) {
                hierarchy.removeListener(hierarchyListener);
                hierarchy.getSelectionModel().removePathObjectSelectionListener(selectionListener);
            }
            qupath.imageDataProperty().removeListener(imageChangeListener);
            FiberSocketPreferences.maxTileDimProperty().removeListener(maxTileDimListener);
            clearLiveOverlay();
        });

        // If the image being opened is the one whose settings we saved last, restore them (downsample,
        // smoothing, both thresholds, two-pass + ridge) over the fresh, stats-derived defaults.
        restoreLastSettingsIfMatch();
        stage.show();
        // The ② form (t2) has HGrow ALWAYS and the preview column is fixed-width; on the very first
        // layout pass a control's not-yet-skinned preferred width can over-claim the form column,
        // squishing the preview until the first interaction forces a re-layout. Force that re-layout
        // once now, after the scene is realized, so the split is correct from the start.
        Platform.runLater(() -> {
            rootScroll.applyCss();
            rootScroll.layout();
        });
        updateTileSizeLabel(); // seed the read-only tile-size display
        refreshBackground(); // populate the live mask straight away
    }

    private String unitLabel() {
        return calibrated ? "um" : "px";
    }

    private static Label sep() {
        Label l = new Label();
        l.setPrefHeight(4);
        return l;
    }

    /** Left (label) column for the two-stage form grids. */
    private static ColumnConstraints labelCol() {
        ColumnConstraints c = new ColumnConstraints();
        c.setMinWidth(Region.USE_PREF_SIZE); // shrink for short labels
        c.setPrefWidth(175);
        c.setMaxWidth(230);                  // cap so it can't crowd the field
        c.setHalignment(HPos.LEFT);
        c.setHgrow(Priority.NEVER);
        return c;
    }

    /** A wrapping form label, so long captions wrap to two lines instead of clipping. */
    private static Label lbl(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    /** A tooltip that WRAPS long text (bounded width) instead of running off the screen edge. */
    private static Tooltip ttip(String text) {
        Tooltip t = new Tooltip(text);
        t.setWrapText(true);
        t.setMaxWidth(420);
        // These tooltips are long and informative; the JavaFX default hides them after ~5 s (too fast
        // to finish reading). Appear promptly and stay up long enough to read.
        t.setShowDelay(Duration.millis(400));
        t.setShowDuration(Duration.seconds(60));
        t.setHideDelay(Duration.millis(400));
        return t;
    }

    /**
     * Give each row's descriptive label the same tooltip as its input control, so hovering the TEXT
     * shows the same help as hovering the field. Copies the control's (column-1) tooltip text onto
     * the label (column-0) wherever the label has none.
     */
    private static void mirrorTooltipsToLabels(GridPane grid) {
        java.util.Map<Integer, javafx.scene.Node> col0 = new java.util.HashMap<>();
        java.util.Map<Integer, javafx.scene.Node> col1 = new java.util.HashMap<>();
        for (javafx.scene.Node n : grid.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            Integer c = GridPane.getColumnIndex(n);
            int row = r == null ? 0 : r;
            if ((c == null ? 0 : c) == 0) {
                col0.put(row, n);
            } else if (c == 1) {
                col1.put(row, n);
            }
        }
        for (var e : col0.entrySet()) {
            if (!(e.getValue() instanceof Label label) || label.getTooltip() != null) {
                continue;
            }
            javafx.scene.Node ctrl = col1.get(e.getKey());
            if (ctrl instanceof javafx.scene.control.Control c && c.getTooltip() != null) {
                label.setTooltip(ttip(c.getTooltip().getText()));
            }
        }
    }

    /** A horizontal spacer that pushes following nodes to the trailing edge of an HBox. */
    private static Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Wrap a group of controls in a light titled border, used for visually distinct clusters. */
    private static VBox borderedGroup(String title, double spacing, javafx.scene.Node... children) {
        VBox box = new VBox(spacing);
        if (title != null) {
            Label h = new Label(title);
            h.setStyle("-fx-font-weight: bold; -fx-font-size: 11;");
            box.getChildren().add(h);
        }
        box.getChildren().addAll(children);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-border-color: -fx-box-border; -fx-border-radius: 4; -fx-background-radius: 4;");
        return box;
    }

    /** Right (field) column: grows to fill. */
    private static ColumnConstraints fieldCol() {
        ColumnConstraints c = new ColumnConstraints();
        c.setHgrow(Priority.ALWAYS);
        c.setFillWidth(true);
        return c;
    }

    private static Spinner<Integer> spinnerI(int min, int max, int init, int step) {
        Spinner<Integer> s = new Spinner<>(min, max, init, step);
        s.setEditable(true);
        return s;
    }

    private static Spinner<Double> spinnerD(double min, double max, double init, double step) {
        Spinner<Double> s = new Spinner<>(min, max, init, step);
        s.setEditable(true);
        // Commit a typed value on Enter and on focus-loss so it actually applies (e.g. updates the
        // live threshold preview). Crucially, the Enter handler CONSUMES the key so it can never
        // bubble up and trigger a default button / start the analysis.
        s.getEditor().setOnAction(e -> {
            commitSpinner(s);
            e.consume();
        });
        s.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commitSpinner(s);
            }
        });
        return s;
    }

    /** Parse and apply an editable spinner's typed text (clamped), reverting on a bad value. */
    private static void commitSpinner(Spinner<Double> s) {
        var vf = s.getValueFactory();
        if (vf == null || vf.getConverter() == null) {
            return;
        }
        String text = s.getEditor().getText();
        if (text == null || text.isBlank()) {
            s.getEditor().setText(vf.getConverter().toString(vf.getValue()));
            return;
        }
        try {
            Double v = vf.getConverter().fromString(text.trim());
            if (v == null || v.isNaN()) {
                throw new NumberFormatException(text);
            }
            if (vf instanceof javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory dvf) {
                v = Math.max(dvf.getMin(), Math.min(dvf.getMax(), v));
            }
            vf.setValue(v); // fires valueProperty listeners (threshold preview, etc.)
            s.getEditor().setText(vf.getConverter().toString(v)); // normalise display
        } catch (RuntimeException ex) {
            s.getEditor().setText(vf.getConverter().toString(vf.getValue())); // revert
        }
    }

    private List<String> channelLabels() {
        List<String> labels = new ArrayList<>();
        try {
            var channels = server.getMetadata().getChannels();
            for (int i = 0; i < channels.size(); i++) {
                labels.add(i + ": " + channels.get(i).getName());
            }
        } catch (Exception ex) {
            for (int i = 0; i < server.nChannels(); i++) {
                labels.add("Channel " + i);
            }
        }
        if (labels.isEmpty()) {
            labels.add("0");
        }
        return labels;
    }

    private List<String> boundaryLabels() {
        boundaryOptions.clear();
        List<String> labels = new ArrayList<>();
        int i = 0;
        for (PathObject a : hierarchy.getAnnotationObjects()) {
            if (!a.hasROI()) {
                continue;
            }
            boundaryOptions.add(a);
            String name = a.getName() != null ? a.getName()
                    : (a.getPathClass() != null ? a.getPathClass().toString() : "Annotation " + i);
            if (a == selected) {
                name += " [region]";
            }
            labels.add(i + ": " + name);
            i++;
        }
        if (labels.isEmpty()) {
            labels.add("(no annotations)");
        }
        return labels;
    }

    private int chosenChannel() {
        return Math.max(0, channelBox.getSelectionModel().getSelectedIndex());
    }

    private int fiberMode() {
        return fiberModeBox.getSelectionModel().getSelectedIndex() == 1 ? 1 : 2;
    }

    // ---- persistent preferences: remember image-independent field values ----
    private void wirePreferences() {
        rememberD(downsampleSpinner, FiberSocketPreferences.downsampleProperty());
        rememberD(minLenSpinner, FiberSocketPreferences.minLengthProperty());
        rememberD(seedSpacingSpinner, FiberSocketPreferences.seedSpacingProperty());
        rememberD(smoothingSpinner, FiberSocketPreferences.smoothingProperty());
        rememberD(distSmoothSpinner, FiberSocketPreferences.distSmoothingProperty());
        rememberD(seedSensSpinner, FiberSocketPreferences.seedSensitivityProperty());
        rememberD(xlinkBoxSpinner, FiberSocketPreferences.xlinkBoxProperty());
        rememberD(bendAngleSpinner, FiberSocketPreferences.bendAngleProperty());
        rememberD(angleExtendSpinner, FiberSocketPreferences.angleExtendProperty());
        rememberD(linkDistSpinner, FiberSocketPreferences.linkDistanceProperty());
        rememberD(linkAngleSpinner, FiberSocketPreferences.linkAngleProperty());
        rememberD(maxWidthSpinner, FiberSocketPreferences.maxWidthProperty());
        rememberD(dxlinkSpinner, FiberSocketPreferences.dxlinkProperty());
        rememberD(dirDecaySpinner, FiberSocketPreferences.dirDecayProperty());
        rememberD(fiberDirSpinner, FiberSocketPreferences.fiberDirProperty());
        rememberD(fireFlenSpinner, FiberSocketPreferences.fireFlenProperty());
        rememberD(maxSpaceSpinner, FiberSocketPreferences.maxSpaceProperty());
        rememberD(angIntervalSpinner, FiberSocketPreferences.angIntervalProperty());
        rememberD(lambdaSpinner, FiberSocketPreferences.lambdaProperty());
        rememberD(dangLenSpinner, FiberSocketPreferences.dangLenProperty());
        rememberD(countTolSpinner, FiberSocketPreferences.countTolProperty());
        rememberI(maxRoundsSpinner, FiberSocketPreferences.maxRoundsProperty());
        rememberChoice(pieceWeightBox, FiberSocketPreferences.pieceWeightProperty());
        rememberD(tacsZoneSpinner, FiberSocketPreferences.tacsZoneProperty());
        rememberChoice(previewScopeBox, FiberSocketPreferences.previewScopeProperty());
        rememberText(extraParamsArea, FiberSocketPreferences.extraParamsProperty());
        // fiberMode pref stores the FIRE mode (1/2), not the box index (0=Merged,1=Segments).
        fiberModeBox.getSelectionModel().selectedIndexProperty().addListener(
                (o, ov, nv) -> FiberSocketPreferences.fiberModeProperty().set(nv.intValue() == 1 ? 1 : 2));
        // detectionsCheck deliberately NOT remembered — see its construction.
        rememberCheck(clearExistingCheck, FiberSocketPreferences.clearExistingProperty());
        rememberCheck(maskToObjectCheck, FiberSocketPreferences.maskToObjectProperty());
        rememberCheck(saveLogCheck, FiberSocketPreferences.logToProjectProperty());
        channelBox.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> {
            if (nv.intValue() >= 0) {
                FiberSocketPreferences.setCollagenChannel(nv.intValue());
            }
        });
    }

    private static void rememberD(Spinner<Double> sp, javafx.beans.property.DoubleProperty pref) {
        try {
            sp.getValueFactory().setValue(pref.get());
        } catch (Exception ignored) {
            // keep the spinner's own default if the saved value is out of range
        }
        sp.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                pref.set(nv);
            }
        });
    }

    /** Persist a free-form text area (e.g. the Extra params JSON) across sessions like the spinners. */
    private static void rememberText(javafx.scene.control.TextArea ta,
            javafx.beans.property.StringProperty pref) {
        String saved = pref.get();
        if (saved != null && !saved.isEmpty()) {
            ta.setText(saved);
        }
        ta.textProperty().addListener((o, ov, nv) -> pref.set(nv == null ? "" : nv));
    }

    private static void rememberI(Spinner<Integer> sp, javafx.beans.property.IntegerProperty pref) {
        try {
            sp.getValueFactory().setValue(pref.get());
        } catch (Exception ignored) {
        }
        sp.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                pref.set(nv);
            }
        });
    }

    private static void rememberChoice(ChoiceBox<String> cb, javafx.beans.property.IntegerProperty pref) {
        int idx = Math.max(0, Math.min(cb.getItems().size() - 1, pref.get()));
        cb.getSelectionModel().select(idx);
        cb.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> {
            if (nv.intValue() >= 0) {
                pref.set(nv.intValue());
            }
        });
    }

    private static void rememberCheck(CheckBox cb, javafx.beans.property.BooleanProperty pref) {
        cb.setSelected(pref.get());
        cb.selectedProperty().addListener((o, ov, nv) -> pref.set(nv));
    }

    /** Restore a spinner from a persistent preference and keep writing changes back to it. */
    static void rememberSpinner(Spinner<Double> sp, javafx.beans.property.DoubleProperty pref) {
        if (sp == null) {
            return;
        }
        double v = pref.get();
        try {
            sp.getValueFactory().setValue(v);
        } catch (RuntimeException ex) {
            return; // out of this spinner's range — keep its own default
        }
        sp.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                pref.set(nv);
            }
        });
    }

    /** Restore a combo from a persistent preference (ignoring a stale value) and write changes back. */
    static void rememberCombo(javafx.scene.control.ComboBox<String> cb,
                              javafx.beans.property.StringProperty pref) {
        if (cb == null) {
            return;
        }
        String v = pref.get();
        if (v != null && !v.isEmpty() && cb.getItems().contains(v)) {
            cb.setValue(v);
        }
        cb.valueProperty().addListener((o, ov, nv) -> pref.set(nv == null ? "" : nv));
    }

    // ---- global intensity stats (native units) for consistent scaling + threshold ----

    /** Read a whole-region sample of the channel and compute its stats. */
    private void computeStats(int channel) {
        try {
            // Sample near the mask's own resolution (~1024 px). A coarser sample averages thin
            // bright collagen into its dark surroundings, which (a) makes "% above" read far lower
            // than the mask actually shows and (b) pulls the 99.5th-percentile white point (normHi)
            // too low, washing out the grayscale image FIRE traces. Matching the mask keeps the
            // reported fraction, the threshold, and the FIRE input consistent.
            // Sample at the SELECTED downsample when the region is small enough to preview faithfully
            // (so normHi matches exactly what FIRE sees, and preview == analysis); for a large region
            // fall back to a coarser, bounded sample -- the same resolution as the too-large thumbnail.
            double d = downsampleSpinner != null ? downsampleSpinner.getValue() : 1.0;
            int statDown;
            int srx = rx, sry = ry, srw = rw, srh = rh;
            if (regionFitsFaithfulPreview(d)) {
                statDown = Math.max(1, (int) Math.ceil(d)); // whole region at the analysis resolution
            } else {
                // Region too large to read whole at the analysis downsample. Coarsening the WHOLE
                // region (the old fallback) averages thin bright collagen into its dark surroundings,
                // so "% above", the white point, and the threshold all read far too low vs what FIRE
                // actually sees at this downsample. Instead read a CENTRAL PATCH at the analysis
                // downsample (~1600 analysed px) so the stats match FIRE's resolution.
                statDown = Math.max(1, (int) Math.ceil(d));
                int patchFull = Math.max(1, (int) Math.round(1600.0 * d));
                srw = Math.min(rw, patchFull);
                srh = Math.min(rh, patchFull);
                srx = rx + (rw - srw) / 2;
                sry = ry + (rh - srh) / 2;
            }
            RegionRequest req =
                    RegionRequest.createInstance(server.getPath(), statDown, srx, sry, srw, srh);
            BufferedImage cr = server.readRegion(req);
            if (cr == null) {
                return;
            }
            double[] s = ChannelExtractor.channelSamples(cr, channel);
            double[] f = java.util.Arrays.stream(s).filter(Double::isFinite).toArray();
            if (f.length == 0) {
                return;
            }
            statSample = f;
            double[] sorted = f.clone();
            java.util.Arrays.sort(sorted);
            statMin = sorted[0];
            statMax = sorted[sorted.length - 1];
            normLo = statMin;
            normHi = percentile(sorted, 0.995); // robust white point (ignore hot pixels)
            if (normHi <= normLo) {
                normHi = statMax > normLo ? statMax : normLo + 1.0;
            }
            logger.info("TME Quant stats: channel={} min={} max={} normHi(99.5%)={}",
                    channel, statMin, statMax, normHi);
        } catch (Exception e) {
            logger.warn("Could not compute channel stats", e);
        }
    }

    private static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private double defaultThreshold() {
        if (statSample.length == 0) {
            return normLo;
        }
        double[] sorted = statSample.clone();
        java.util.Arrays.sort(sorted);
        return percentile(sorted, 0.80); // keep ~ brightest 20% as foreground by default
    }

    private double fractionAbove(double t) {
        return fractionAbove(statSample, t);
    }

    private static double fractionAbove(double[] sample, double t) {
        if (sample == null || sample.length == 0) {
            return 0;
        }
        int c = 0;
        for (double v : sample) {
            if (v > t) {
                c++;
            }
        }
        return (double) c / sample.length;
    }

    private void updateThresholdFeedback() {
        if (thresholdFeedback == null || thresholdSpinner == null) {
            return;
        }
        double t = thresholdSpinner.getValue();
        // Region average (a central patch at the analysis resolution) AND the SELECTED tile — a hot
        // tile can far exceed the region average and be the one that trips the server density guard.
        String txt = String.format("%.1f%% of region above", fractionAbove(statSample, t) * 100.0);
        if (tileSample != null && tileSample.length > 0) {
            txt += String.format("   •   %.1f%% of this tile", fractionAbove(tileSample, t) * 100.0);
        }
        thresholdFeedback.setText(txt);
    }

    /**
     * Read the SELECTED preview tile at the analysis downsample and cache its native samples, so the
     * threshold readout can show that tile's OWN foreground % — a hot tile can far exceed the region
     * average and be the one that trips the server density guard. Runs off the FX thread.
     */
    private void refreshTileSampleAsync() {
        if (server == null) {
            return;
        }
        final int channel = chosenChannel();
        final double d = downsampleSpinner != null ? downsampleSpinner.getValue() : 1.0;
        new Thread(() -> {
            double[] ts = new double[0];
            try {
                int rwA = (int) Math.ceil(rw / d);
                int rhA = (int) Math.ceil(rh / d);
                List<Tile> tls = computeTiles(rwA, rhA, d);
                if (!tls.isEmpty()) {
                    Tile stl = tls.get(effectivePreviewTile(tls.size()));
                    BufferedImage treg = server.readRegion(RegionRequest.createInstance(
                            server.getPath(), d, stl.fullX, stl.fullY, stl.fullW, stl.fullH));
                    if (treg != null) {
                        ts = ChannelExtractor.channelSamples(treg, channel);
                    }
                }
            } catch (Exception ignore) {
                // leave tile sample empty -> readout shows region only
            }
            final double[] tsf = ts;
            Platform.runLater(() -> {
                tileSample = tsf;
                updateThresholdFeedback();
            });
        }, "tmequant-tilesample").start();
    }

    private void applyStatsToThresholdSpinner() {
        applyStatsToThresholdSpinner(true);
    }

    /**
     * Rebuild the threshold spinner's range/step from the current stats. With
     * {@code resetToDefault} true (new image / channel), set the auto default; with false (only the
     * region moved), KEEP the user's current threshold value (re-clamped) so moving the analysis
     * area doesn't wipe a hand-set threshold.
     */
    private void applyStatsToThresholdSpinner(boolean resetToDefault) {
        double step = Math.max((statMax - statMin) / 100.0, 1e-6);
        double value = resetToDefault
                ? defaultThreshold()
                : Math.max(statMin, Math.min(statMax, thresholdSpinner.getValue()));
        thresholdSpinner.setValueFactory(
                new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(
                        statMin, statMax, value, step));
        updateThresholdFeedback();
    }

    /** Max bend angle (deg) -> thresh_ext cosine. Larger angle = more curvature allowed. */
    private static double extToCos(double degrees) {
        return Math.cos(Math.toRadians(Math.max(0, Math.min(89.0, degrees))));
    }

    /** Max link deviation (deg) -> thresh_linka. 0deg=collinear(-1); larger=looser links. */
    private static double linkaToCos(double degrees) {
        return -Math.cos(Math.toRadians(Math.max(0, Math.min(89.0, degrees))));
    }

    /** Inverse of {@link #extToCos}: thresh_ext cosine back to a bend angle in degrees. */
    private static double cosToExt(double cos) {
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
    }

    /** Inverse of {@link #linkaToCos}: thresh_linka back to a link deviation in degrees. */
    private static double cosToLinka(double negCos) {
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, -negCos))));
    }

    // ---- Suggest search axes (the no-instant-feedback fiber-detection params) ----
    // The grid sweeps seed spacing, seed sensitivity, bend angle and link angle. Threshold,
    // smoothing and downsample are NOT searched — they have a live preview, so the user sets
    // them by eye and they are held fixed as base overrides.

    private static int[] uniqInts(int... v) {
        java.util.LinkedHashSet<Integer> s = new java.util.LinkedHashSet<>();
        for (int x : v) {
            s.add(x);
        }
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double[] uniqDoubles(double... v) {
        java.util.LinkedHashSet<Double> s = new java.util.LinkedHashSet<>();
        for (double x : v) {
            s.add(Math.round(x * 1000.0) / 1000.0);
        }
        return s.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Linear-interpolated percentile of a SORTED list (pct in 0..100). */
    private static double percentile(java.util.List<Double> sorted, double pct) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        double idx = (pct / 100.0) * (sorted.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sorted.get(lo);
        }
        double frac = idx - lo;
        return sorted.get(lo) * (1 - frac) + sorted.get(hi) * frac;
    }

    /** Clamp v to a Double spinner's [min,max] so calibrated values never exceed its range. */
    private static double clampToSpinner(Spinner<Double> sp, double v) {
        if (sp.getValueFactory() instanceof
                javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory df) {
            return Math.max(df.getMin(), Math.min(df.getMax(), v));
        }
        return v;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Set a Double spinner's value AND force its editor text to refresh. Editable spinners do not
     * reliably repaint the editor on a programmatic {@code valueFactory.setValue(...)}, so applying
     * a suggested/calibrated value would change the value but leave the field LOOKING unchanged.
     */
    private static void setSpinnerValue(Spinner<Double> sp, double v) {
        sp.getValueFactory().setValue(v);
        var conv = sp.getValueFactory().getConverter();
        sp.getEditor().setText(conv != null ? conv.toString(v) : Double.toString(v));
    }

    private static final String PRESET_CUSTOM = "Custom";
    private static final String TRACER_FIRE = "FIRE (seed & extend)";
    private static final String TRACER_CENTERLINE = "Centerline (medial axis)";
    private static final String TRACER_FLOW = "Flow (orientation field)";

    /**
     * Apply a named detection preset: set the tracing knobs it controls (Direction decay, Max bend,
     * Seed spacing) in one shot. Guarded by {@code applyingPreset} so the spinner listeners that
     * return the selector to "Custom" don't fire while we set them. Seed spacing is in the spinner's
     * own units (µm calibrated / full-res px); lower = denser seeding (needed to follow crimp).
     */
    private void applyDetectionPreset(String name) {
        applyingPreset = true;
        try {
            switch (name) {
                case "Straight fibers":
                    setSpinnerValue(dirDecaySpinner, 0.3);
                    setSpinnerValue(bendAngleSpinner, 60.0);
                    setSpinnerValue(seedSpacingSpinner, 18.0);
                    break;
                case "Balanced":
                    setSpinnerValue(dirDecaySpinner, 0.45);
                    setSpinnerValue(bendAngleSpinner, 70.0);
                    setSpinnerValue(seedSpacingSpinner, 14.0);
                    break;
                case "Wavy / crimped collagen":
                    // 0.65/78 not 0.7/82: the more aggressive pair made FIRE spin (>300 s) on some
                    // tiles (290, 916) — the tracer loops when near-90 deg turns meet high momentum.
                    // These values trace the crimp just as well and finish in seconds.
                    setSpinnerValue(dirDecaySpinner, 0.65);
                    setSpinnerValue(bendAngleSpinner, 78.0);
                    setSpinnerValue(seedSpacingSpinner, 8.0);
                    break;
                default:
                    break;
            }
        } finally {
            applyingPreset = false;
        }
    }

    /** Robust length statistics (in the user's units: µm if calibrated, else full-res px). */
    private static final class LineStats {
        final int n;
        final double minLen;
        final double p10;
        final double median;
        LineStats(int n, double minLen, double p10, double median) {
            this.n = n;
            this.minLen = minLen;
            this.p10 = p10;
            this.median = median;
        }
    }

    /**
     * Measure the LINE annotations the user traced inside the given pieces and return robust
     * length statistics in the user's units. Returns null if too few lines to be meaningful.
     * Reused by 'Calibrate from traced lines' and by Suggest (to set Min length before the grid).
     */
    private LineStats measureTracedLines(List<ROI> pieceRois) {
        java.util.List<Double> lensUser = new ArrayList<>();
        for (ROI r : pieceRois) {
            int px = (int) Math.floor(r.getBoundsX());
            int py = (int) Math.floor(r.getBoundsY());
            int pw = (int) Math.ceil(r.getBoundsWidth());
            int ph = (int) Math.ceil(r.getBoundsHeight());
            double sd = capSearchDown(pw, ph);
            JsonArray gt = collectGroundTruthLines(r, px, py, sd);
            for (var el : gt) {
                JsonArray line = el.getAsJsonArray();
                double arcPx = 0.0; // analysed px at this piece's searchDown
                for (int i = 1; i < line.size(); i++) {
                    JsonArray a = line.get(i - 1).getAsJsonArray();
                    JsonArray b = line.get(i).getAsJsonArray();
                    arcPx += Math.hypot(b.get(0).getAsDouble() - a.get(0).getAsDouble(),
                            b.get(1).getAsDouble() - a.get(1).getAsDouble());
                }
                double fullResPx = arcPx * sd; // downsample-independent
                double userLen = calibrated ? fullResPx * pixelSizeMicrons : fullResPx;
                if (userLen > 0) {
                    lensUser.add(userLen);
                }
            }
        }
        if (lensUser.size() < 3) {
            return null;
        }
        java.util.Collections.sort(lensUser);
        return new LineStats(lensUser.size(), lensUser.get(0),
                percentile(lensUser, 10), percentile(lensUser, 50));
    }

    /**
     * Set Min fiber length and Seed spacing from traced-line statistics: keep everything down to
     * ~70% of the shortest real fibre, and seed densely enough (~4 seeds along the shortest) to
     * trace it. Returns a human-readable summary of what changed. Must run on the FX thread.
     */
    private String applyLineCalibration(LineStats s) {
        double minLenSet = clampToSpinner(minLenSpinner, 0.7 * Math.min(s.minLen, s.p10));
        double seedSet = clampToSpinner(seedSpacingSpinner, Math.min(s.minLen / 4.0, s.median / 6.0));
        setSpinnerValue(minLenSpinner, round1(minLenSet));
        setSpinnerValue(seedSpacingSpinner, round1(seedSet));
        String u = unitLabel();
        return String.format(
                "Calibrated from %d traced line(s) (median %.1f %s, shortest %.1f %s): "
                + "Min fiber length → %.1f %s, Seed spacing → %.1f %s.",
                s.n, round1(s.median), u, round1(s.minLen), u,
                round1(minLenSet), u, round1(seedSet), u);
    }

    /** 'Calibrate from traced lines' button action. */
    private void calibrateFromTracedLines() {
        retargetRegionToSelection();
        List<ROI> pieceRois = collectSelectedAreaRois();
        if (pieceRois.isEmpty()) {
            tuneSay("No area selected — select one or more AREA annotations you traced fibers "
                    + "inside, then Calibrate again.", "warn");
            return;
        }
        LineStats s = measureTracedLines(pieceRois);
        if (s == null) {
            tuneSay("Found fewer than 3 traced LINE annotations inside the area. Trace your fibers "
                    + "as lines (ideally ≥ ~8) inside the selected annotation, then Calibrate.", "warn");
            return;
        }
        String msg = applyLineCalibration(s);
        logger.info("CALIBRATE: {}", msg);
        // Echo locally (the spinners it changed are up in ② — point the eye there).
        tuneSay(msg + " ↑ Updated Min length & Seed spacing in ② Fiber detection — Preview to check.",
                "ok");
        setBusy(false, msg + " Now click 'Preview fibers' to check, or 'Suggest parameters…' to refine.");
    }

    /** Show a result line right under the Calibrate/Suggest buttons. kind: "ok" | "warn" | "info". */
    private void tuneSay(String msg, String kind) {
        if (tuneFeedback == null) {
            return;
        }
        String color = "ok".equals(kind) ? "#0a7d2c" : ("warn".equals(kind) ? "#b35900" : "#1565c0");
        Runnable r = () -> {
            tuneFeedback.setText(msg);
            tuneFeedback.setStyle("-fx-font-size: 11; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
            tuneFeedback.setManaged(true);
            tuneFeedback.setVisible(true);
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /**
     * Apply a magnification/NA objective preset (idx 1..4). Fills only the size-dependent knobs
     * that have NO live feedback (Min length, Seed spacing, Max width, Link distance) with sensible
     * µm starting points. Downsample is deliberately NOT touched — it has a live mask preview and is
     * set by eye, so a preset shouldn't override it. Starting point only — the user previews/refines.
     */
    private void applyObjectivePreset(int idx) {
        if (!calibrated) {
            Dialogs.showWarningNotification(TITLE,
                    "Objective presets need a calibrated pixel size — set the image pixel size in "
                    + "QuPath, or enter the detection settings manually.");
            return;
        }
        // {minLenUm, seedUm, maxWidUm, linkUm} — finer fibers + tighter geometry at higher mag/NA.
        double[][] table = {
            {15, 2.0, 6.0, 5.0},   // 10× / NA 0.45
            {10, 2.0, 4.0, 4.0},   // 20× / NA 0.75
            {8,  1.5, 3.0, 3.0},   // 40× / NA 0.95
            {6,  1.0, 2.5, 2.5},   // 63× / NA 1.4 (oil)
        };
        double[] v = table[idx - 1];
        setSpinnerValue(minLenSpinner, round1(clampToSpinner(minLenSpinner, v[0])));
        setSpinnerValue(seedSpacingSpinner, round1(clampToSpinner(seedSpacingSpinner, v[1])));
        setSpinnerValue(maxWidthSpinner, round1(clampToSpinner(maxWidthSpinner, v[2])));
        setSpinnerValue(linkDistSpinner, round1(clampToSpinner(linkDistSpinner, v[3])));
        String mag = new String[] {"10×", "20×", "40×", "63×"}[idx - 1];
        String msg = String.format(
                "Applied %s preset: Min length %.0f, Seed spacing %.1f, Max width %.1f, Link %.1f %s. "
                + "Downsample unchanged — set it by eye with the ① mask. Starting point — refine.",
                mag, v[0], v[1], v[2], v[3], unitLabel());
        logger.info("PRESET: {}", msg);
        setBusy(false, msg);
    }

    /** Serialize the current detection settings to a JSON object (display units). */
    private JsonObject settingsToJson() {
        JsonObject p = new JsonObject();
        p.addProperty("channel", chosenChannel());
        p.addProperty("downsample", downsampleSpinner.getValue());
        p.addProperty("smoothing", smoothingSpinner.getValue());
        p.addProperty("threshold", thresholdSpinner.getValue());
        p.addProperty("seedSpacing", seedSpacingSpinner.getValue());
        p.addProperty("seedSens", seedSensSpinner.getValue());
        p.addProperty("xlinkBox", xlinkBoxSpinner.getValue());
        p.addProperty("bendAngle", bendAngleSpinner.getValue());
        p.addProperty("angleExtend", angleExtendSpinner.getValue());
        p.addProperty("linkAngle", linkAngleSpinner.getValue());
        p.addProperty("minLen", minLenSpinner.getValue());
        p.addProperty("fiberMode", fiberModeBox.getSelectionModel().getSelectedIndex());
        p.addProperty("tacs", tacsCheck.isSelected());
        p.addProperty("tacsZone", tacsZoneSpinner.getValue());
        p.addProperty("distSmooth", distSmoothSpinner.getValue());
        p.addProperty("linkDist", linkDistSpinner.getValue());
        p.addProperty("maxWidth", maxWidthSpinner.getValue());
        p.addProperty("dxlink", dxlinkSpinner.getValue());
        p.addProperty("dirDecay", dirDecaySpinner.getValue());
        p.addProperty("fiberDir", fiberDirSpinner.getValue());
        p.addProperty("fireFlen", fireFlenSpinner.getValue());
        p.addProperty("maxSpace", maxSpaceSpinner.getValue());
        p.addProperty("angInterval", angIntervalSpinner.getValue());
        p.addProperty("lambda", lambdaSpinner.getValue());
        p.addProperty("dangLen", dangLenSpinner.getValue());
        p.addProperty("countTol", countTolSpinner.getValue());
        p.addProperty("maxRounds", maxRoundsSpinner.getValue());
        p.addProperty("pieceWeight", pieceWeightBox.getSelectionModel().getSelectedIndex());
        p.addProperty("createDetections", detectionsCheck.isSelected());
        p.addProperty("clearExisting", clearExistingCheck.isSelected());
        p.addProperty("extraParams", extraParamsArea.getText());
        return p;
    }

    /** Export current settings to a user-chosen .json file (shareable / reproducible). */
    /** Directory the settings file choosers should open in: last used, else the project. */
    private java.io.File lastSettingsDir;

    /**
     * Start the chooser in the project folder rather than wherever the OS last left it. Settings
     * belong with the project they were tuned for, and the default landed users at the top of the
     * whole machine even when a project was open. Falls back to the last directory used this
     * session, then to the home directory.
     */
    /** The open project's folder, or null — the sensible starting point for any file chooser here. */
    private java.io.File projectDirFile() {
        try {
            Project<BufferedImage> proj = qupath == null ? null : qupath.getProject();
            if (proj != null && proj.getPath() != null && proj.getPath().getParent() != null) {
                java.io.File d = proj.getPath().getParent().toFile();
                return d.isDirectory() ? d : null;
            }
        } catch (RuntimeException ex) {
            logger.debug("No project directory available: {}", ex.getMessage());
        }
        return null;
    }

    private void applySettingsDir(FileChooser fc) {
        java.io.File dir = lastSettingsDir;
        if (dir == null || !dir.isDirectory()) {
            dir = projectDirFile();
        }
        if (dir == null || !dir.isDirectory()) {
            java.io.File home = new java.io.File(System.getProperty("user.home", "."));
            dir = home.isDirectory() ? home : null;
        }
        if (dir != null) {
            fc.setInitialDirectory(dir);
        }
    }

    private void saveSettings(Stage owner) {
        JsonObject root = new JsonObject();
        root.addProperty("tmequant_settings_version", 1);
        root.addProperty("unit", unitLabel()); // µm vs px — distances are interpreted in this unit
        root.add("params", settingsToJson());
        FileChooser fc = new FileChooser();
        fc.setTitle("Save TME Quant settings");
        fc.setInitialFileName("tmequant-settings.json");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        applySettingsDir(fc);
        File f = fc.showSaveDialog(owner);
        if (f == null) {
            return;
        }
        lastSettingsDir = f.getParentFile();
        if (!f.getName().toLowerCase().endsWith(".json")) {
            f = new File(f.getParentFile(), f.getName() + ".json");
        }
        try (java.io.Writer w = new java.io.FileWriter(f)) {
            GSON.toJson(root, w);
            setBusy(false, "Saved settings to " + f.getName());
            logger.info("Saved settings to {}", f.getAbsolutePath());
        } catch (Exception ex) {
            Dialogs.showErrorMessage(TITLE, "Could not save settings:\n" + ex.getMessage());
        }
    }

    /** Load settings from a user-chosen .json file and apply them to the dialog. */
    private void loadSettings(Stage owner) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Load TME Quant settings");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        applySettingsDir(fc);
        File f = fc.showOpenDialog(owner);
        if (f == null) {
            return;
        }
        lastSettingsDir = f.getParentFile();
        try (java.io.Reader r = new java.io.FileReader(f)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonObject p = root.has("params") ? root.getAsJsonObject("params") : root;
            String savedUnit = root.has("unit") ? root.get("unit").getAsString() : unitLabel();
            if (!savedUnit.equals(unitLabel())) {
                Dialogs.showWarningNotification(TITLE, String.format(
                        "These settings were saved in '%s' but this image uses '%s'. Distance values "
                        + "(seed spacing, link distance, lengths…) may need adjusting.",
                        savedUnit, unitLabel()));
            }
            applySettingsJson(p);
            refreshBackground();
            setBusy(false, "Loaded settings from " + f.getName()
                    + ". Check the ① mask and Preview to confirm.");
            logger.info("Loaded settings from {}", f.getAbsolutePath());
        } catch (Exception ex) {
            Dialogs.showErrorMessage(TITLE, "Could not load settings:\n" + ex.getMessage());
        }
    }

    /** Apply a settings JSON object to the dialog controls (only keys that are present). */
    private void applySettingsJson(JsonObject p) {
        if (p.has("channel")) {
            int n = channelBox.getItems().size();
            channelBox.getSelectionModel().select(Math.max(0, Math.min(n - 1, p.get("channel").getAsInt())));
        }
        setIfD(p, "downsample", downsampleSpinner);
        setIfD(p, "smoothing", smoothingSpinner);
        setIfD(p, "threshold", thresholdSpinner);
        setIfD(p, "seedSpacing", seedSpacingSpinner);
        setIfD(p, "seedSens", seedSensSpinner);
        setIfD(p, "xlinkBox", xlinkBoxSpinner);
        setIfD(p, "bendAngle", bendAngleSpinner);
        setIfD(p, "angleExtend", angleExtendSpinner);
        setIfD(p, "linkAngle", linkAngleSpinner);
        setIfD(p, "minLen", minLenSpinner);
        if (p.has("fiberMode")) {
            selectIdx(fiberModeBox, p.get("fiberMode").getAsInt());
        }
        if (p.has("tacs")) {
            tacsCheck.setSelected(p.get("tacs").getAsBoolean());
        }
        setIfD(p, "tacsZone", tacsZoneSpinner);
        setIfD(p, "distSmooth", distSmoothSpinner);
        setIfD(p, "linkDist", linkDistSpinner);
        setIfD(p, "maxWidth", maxWidthSpinner);
        setIfD(p, "dxlink", dxlinkSpinner);
        setIfD(p, "dirDecay", dirDecaySpinner);
        setIfD(p, "fiberDir", fiberDirSpinner);
        setIfD(p, "fireFlen", fireFlenSpinner);
        setIfD(p, "maxSpace", maxSpaceSpinner);
        setIfD(p, "angInterval", angIntervalSpinner);
        setIfD(p, "lambda", lambdaSpinner);
        setIfD(p, "dangLen", dangLenSpinner);
        setIfD(p, "countTol", countTolSpinner);
        if (p.has("maxRounds")) {
            int v = p.get("maxRounds").getAsInt();
            maxRoundsSpinner.getValueFactory().setValue(v);
            maxRoundsSpinner.getEditor().setText(Integer.toString(v));
        }
        if (p.has("pieceWeight")) {
            selectIdx(pieceWeightBox, p.get("pieceWeight").getAsInt());
        }
        // "createDetections" in saved settings is ignored on load: output type always starts on
        // detections so a remembered annotation choice can't flood QuPath on the next big run.
        if (p.has("clearExisting")) {
            clearExistingCheck.setSelected(p.get("clearExisting").getAsBoolean());
        }
        if (p.has("extraParams")) {
            extraParamsArea.setText(p.get("extraParams").getAsString());
        }
    }

    /** Set a Double spinner from a JSON key if present, clamped to the spinner's range. */
    private void setIfD(JsonObject p, String key, Spinner<Double> sp) {
        if (p.has(key) && !p.get(key).isJsonNull()) {
            setSpinnerValue(sp, clampToSpinner(sp, p.get(key).getAsDouble()));
        }
    }

    private static void selectIdx(ChoiceBox<String> cb, int idx) {
        cb.getSelectionModel().select(Math.max(0, Math.min(cb.getItems().size() - 1, idx)));
    }

    /** Convert a value the user typed in real-world units to analysed pixels. */
    private double toAnalysedPx(double userValue, double downsample) {
        double fullResPx = calibrated ? userValue / pixelSizeMicrons : userValue;
        return fullResPx / downsample;
    }

    /** Inverse of {@link #toAnalysedPx}: analysed pixels back to the user's real-world unit. */
    private double fromAnalysedPx(double analysedPx, double downsample) {
        double fullResPx = analysedPx * downsample;
        return calibrated ? fullResPx * pixelSizeMicrons : fullResPx;
    }

    /** Seed spacing (thresh_LMPdist) in analysed px for the given downsample (min 2). */
    private int seedSpacingAnalysedPx(double downsample) {
        return Math.max(2, (int) Math.round(toAnalysedPx(seedSpacingSpinner.getValue(), downsample)));
    }

    /** Cross-link search box (s_xlinkbox) in analysed px for the given downsample (min 1). */
    private int xlinkBoxAnalysedPx(double downsample) {
        return Math.max(1, (int) Math.round(toAnalysedPx(xlinkBoxSpinner.getValue(), downsample)));
    }

    // The advanced distance/length/width knobs are entered in microns and converted to
    // analysed px at run time, so they stay fixed when the downsample changes.
    private double linkDistAnalysedPx(double ds) {
        return toAnalysedPx(linkDistSpinner.getValue(), ds);
    }

    private double maxWidthAnalysedPx(double ds) {
        return toAnalysedPx(maxWidthSpinner.getValue(), ds); // 0 stays 0 (= off)
    }

    /**
     * Hessian scales (σ, in analysed px) for the ridge/vesselness pre-filter. Derived from the
     * Max-width knob so they track fibre size and the downsample: σ ranges from 1 px up to about a
     * fibre half-width (capped at 8 to keep the multiscale Hessian cheap). Falls back to a small
     * default spread when Max width is off (0).
     */
    private com.google.gson.JsonArray ridgeScales(double ds) {
        double hw = maxWidthAnalysedPx(ds) / 2.0;              // ~fibre half-width in analysed px
        double top = (hw >= 1.5) ? Math.min(8.0, hw) : 4.0;   // Max width off -> default 4
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        int n = 4;
        double prev = -1;
        for (int i = 0; i < n; i++) {
            double s = 1.0 + (top - 1.0) * i / (n - 1);        // linspace(1, top, 4)
            s = Math.round(s * 10.0) / 10.0;                   // one decimal
            if (s >= 1.0 && s > prev) {                        // strictly increasing, >=1
                a.add(s);
                prev = s;
            }
        }
        if (a.size() == 0) {
            a.add(1.0);
        }
        return a;
    }

    private double fireFlenAnalysedPx(double ds) {
        return toAnalysedPx(fireFlenSpinner.getValue(), ds);
    }

    private double maxSpaceAnalysedPx(double ds) {
        return Math.max(1.0, toAnalysedPx(maxSpaceSpinner.getValue(), ds));
    }

    private double dangLenAnalysedPx(double ds) {
        return toAnalysedPx(dangLenSpinner.getValue(), ds);
    }

    private int fiberDirAnalysedPx(double ds) {
        return Math.max(2, (int) Math.round(toAnalysedPx(fiberDirSpinner.getValue(), ds)));
    }

    /** Parse the free-form JSON box; returns an empty object if blank. Throws on bad JSON. */
    private JsonObject parseExtra() {
        String text = extraParamsArea.getText() == null ? "" : extraParamsArea.getText().trim();
        if (text.isEmpty()) {
            return new JsonObject();
        }
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** The background threshold in native units — the 2nd-pass override if one is active, else the spinner. */
    private double currentThreshold() {
        Double ov = thresholdOverride;
        return ov != null ? ov : thresholdSpinner.getValue();
    }

    /** Map a native-units threshold onto the global 0-255 mask scale. */
    private int threshIm2For(double nativeThreshold) {
        double range = normHi > normLo ? normHi - normLo : 1.0;
        return clamp((int) Math.round((nativeThreshold - normLo) / range * 255.0), 0, 255);
    }

    /** Map the native-units threshold onto the global 0-255 scale used by FIRE (MASK display). */
    private int threshIm2() {
        return threshIm2For(currentThreshold());
    }

    // Ceiling for the FIRE 0-255 threshold in the image SENT to the server. If thresh_im2
    // approaches 255, the bright collagen has been prescaled into a flat, clipped plateau
    // (no intensity gradient), so FIRE's distance-map nucleation finds no ridges and traces
    // ZERO fibers -- exactly the failure seen when the white point (normHi) is sampled from a
    // mostly-dark region (e.g. the whole image), where the 99.5th percentile sits right at the
    // fibre intensity. Keeping thresh_im2 <= this leaves headroom so fibres keep their gradient.
    private static final double MAX_SERVER_IM2 = 155.0;

    /**
     * White point for the image SENT to FIRE. Floors {@code hi} so the native threshold cannot
     * map above {@link #MAX_SERVER_IM2}: with a dark-dominated sample this raises the white point
     * above the collagen intensity so the bright fibres keep an intensity gradient instead of
     * clipping to a flat 255. Threshold-aware and self-consistent with {@link #serverThreshIm2}.
     * The live mask deliberately keeps the plain {@code normHi} -- its red region (native &gt;
     * threshold) is unaffected by the white point, so threshold-setting stays correct.
     */
    private double serverNormHi(double lo, double hi) {
        // Headroom must cover the BRIGHTEST COLLAGEN, which is what the fixed ratio got wrong. The
        // old floor only ever allowed threshold x 255/155 (~1.6x the threshold), but real collagen
        // runs several times the threshold -- measured 4.5x on one field -- so the top of every
        // thick bundle clipped to a flat 255 and its internal striations were destroyed before
        // tracing. 9% of that tile, and 63% of one band, was flat white.
        //
        // So ask the data: take a high percentile of the pixels ABOVE the threshold (the collagen
        // itself, not the dark background that dominates normHi) and keep that below 255. The
        // fixed ratio stays as a floor for the case where the sample says nothing useful.
        double ratioFloor = lo + (currentThreshold() - lo) * (255.0 / MAX_SERVER_IM2);
        double collagenFloor = brightCollagenLevel();
        return Math.max(hi, Math.max(ratioFloor, collagenFloor));
    }

    /**
     * A high percentile of the ABOVE-THRESHOLD sample — i.e. how bright the collagen actually gets
     * in this region. Returns 0 when there is no usable sample, so the caller falls back to the
     * fixed ratio. The percentile (not the max) keeps a few hot pixels from inflating the range and
     * washing everything else out.
     */
    private double brightCollagenLevel() {
        double[] sample = statSample;
        if (sample == null || sample.length < 64) {
            return 0.0;
        }
        double thr = currentThreshold();
        double[] above = java.util.Arrays.stream(sample).filter(v -> v > thr).sorted().toArray();
        if (above.length < 32) {
            return 0.0; // almost nothing above the threshold — nothing to protect from clipping
        }
        return percentile(above, 0.999);
    }

    private double serverNormHi() {
        return serverNormHi(normLo, normHi);
    }

    /** thresh_im2 for the SENT image, using the floored white point (so it never saturates). */
    private int serverThreshIm2(double lo, double hi) {
        double eh = serverNormHi(lo, hi);
        double range = eh > lo ? eh - lo : 1.0;
        return clamp((int) Math.round((currentThreshold() - lo) / range * 255.0), 0, 255);
    }

    private int serverThreshIm2() {
        return serverThreshIm2(normLo, normHi);
    }

    /** Pull the "overrides" object out of a built meta JSON, for logging what FIRE receives. */
    private static String overridesOf(String metaJson) {
        try {
            JsonObject m = JsonParser.parseString(metaJson).getAsJsonObject();
            return m.has("overrides") ? m.get("overrides").toString() : metaJson;
        } catch (Exception e) {
            return metaJson;
        }
    }

    private String buildMeta(double downsample, boolean hasMask, JsonObject extra) {
        int threshIm2 = serverThreshIm2(); // floored so bright collagen doesn't clip to flat 255
        int ll1 = Math.max(1, (int) Math.round(toAnalysedPx(minLenSpinner.getValue(), downsample)));
        double distPx = toAnalysedPx(tacsZoneSpinner.getValue(), downsample);

        JsonObject overrides = new JsonObject();
        overrides.addProperty("thresh_im2", threshIm2);
        overrides.addProperty("LL1", ll1);
        overrides.addProperty("thresh_LMPdist", seedSpacingAnalysedPx(downsample));
        overrides.addProperty("sigma_im", smoothingSpinner.getValue());
        overrides.addProperty("sigma_d", distSmoothSpinner.getValue());
        overrides.addProperty("thresh_LMP", seedSensSpinner.getValue());
        overrides.addProperty("s_xlinkbox", xlinkBoxAnalysedPx(downsample));
        overrides.addProperty("thresh_linkd", linkDistAnalysedPx(downsample));
        // Angle knobs are entered in degrees and mapped to FIRE's cosine thresholds:
        // thresh_ext = cos(maxBend);  thresh_linka = -cos(maxLinkDeviation);
        // thresh_dang_aextend = cos(angleExtend).
        overrides.addProperty("thresh_ext", extToCos(bendAngleSpinner.getValue()));
        overrides.addProperty("thresh_dang_aextend", extToCos(angleExtendSpinner.getValue()));
        overrides.addProperty("thresh_linka", linkaToCos(linkAngleSpinner.getValue()));
        overrides.addProperty("widMAX", maxWidthAnalysedPx(downsample));
        // Advanced parity knobs (sent as raw FIRE 'value'-dict params).
        overrides.addProperty("thresh_Dxlink", dxlinkSpinner.getValue());
        overrides.addProperty("lam_dirdecay", dirDecaySpinner.getValue());
        overrides.addProperty("s_fiberdir", fiberDirAnalysedPx(downsample));
        overrides.addProperty("thresh_flen", fireFlenAnalysedPx(downsample));
        overrides.addProperty("s_maxspace", maxSpaceAnalysedPx(downsample));
        overrides.addProperty("ang_interval", angIntervalSpinner.getValue());
        overrides.addProperty("lambda", lambdaSpinner.getValue());
        overrides.addProperty("thresh_dang_L", dangLenAnalysedPx(downsample));
        // Free-form extras win over the controls above.
        if (extra != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : extra.entrySet()) {
                overrides.add(e.getKey(), e.getValue());
            }
        }

        JsonObject region = new JsonObject();
        region.addProperty("x", rx);
        region.addProperty("y", ry);
        region.addProperty("width", rw);
        region.addProperty("height", rh);
        region.addProperty("downsample", downsample);

        JsonObject meta = new JsonObject();
        meta.add("region", region);
        meta.addProperty("fiber_mode", fiberMode());
        meta.addProperty("use_ct_reconstruction", false);
        meta.addProperty("has_mask", hasMask);
        meta.addProperty("distance_threshold", distPx);
        // Images are pre-scaled to 0-255 with a GLOBAL reference, so the server must
        // NOT re-normalise per tile (that is what amplified noise in black borders).
        meta.addProperty("prescaled", true);
        // Experimental grayscale ridge/vesselness pre-filter (server-side). Sends the
        // scales in ANALYSED px so they track the current downsample.
        if (ridgeCheck != null && ridgeCheck.isSelected()) {
            meta.addProperty("ridge_enable", true);
            meta.addProperty("ridge_method", "frangi");
            meta.add("ridge_scales", ridgeScales(downsample));
        }
        // Advanced server settings (guards & cleanup) — the server falls back to its env/defaults
        // for any key not sent, so only send when the spinners exist.
        if (densityCapSpinner != null) {
            meta.addProperty("density_cap", densityCapSpinner.getValue() / 100.0); // % -> fraction
            meta.addProperty("seed_cap", seedCapSpinner.getValue());
            meta.addProperty("web_cap", webCapSpinner.getValue());
            meta.addProperty("anti_flow_check", antiFlowCheck.isSelected() ? 1.0 : 0.0);
            meta.addProperty("fire_timeout", fireTimeoutSpinner.getValue());
            meta.addProperty("split_jump_mult", splitJumpMultSpinner.getValue());
            meta.addProperty("split_gap_frac", splitGapFracSpinner.getValue());
            meta.addProperty("edge_trim_margin", edgeTrimSpinner.getValue());
            // Flow filter: off_deg=0 disables it server-side; the coherence gate is the safety dial.
            meta.addProperty("flow_off_deg", flowFilterCheck.isSelected() ? 52.0 : 0.0);
            meta.addProperty("flow_coh", flowCohSpinner.getValue());
        }
        // Tracer choice (FIRE seed-and-extend vs centerline/medial-axis). Same measurements either way.
        if (tracerCombo != null && TRACER_CENTERLINE.equals(tracerCombo.getValue())) {
            meta.addProperty("tracer", "centerline");
        } else if (tracerCombo != null && TRACER_FLOW.equals(tracerCombo.getValue())) {
            meta.addProperty("tracer", "flow");
            // "Parallel fibre distance" in the active unit -> analysed px for the server.
            meta.addProperty("flow_parallel_px",
                    toAnalysedPx(flowParallelSpinner.getValue(), downsample));
        }
        // Raw mode: bypass every bridge guard/cleanup on the server (plain FIRE output).
        if (rawModeCheck != null && rawModeCheck.isSelected()) {
            meta.addProperty("raw_mode", true);
        }
        // Exclusion regions ("don't trace here"). The mask itself travels per tile (see detectTile);
        // these are the rules the server applies to it. has_exclude_mask gates the extra wire block,
        // so it is only true when this tile actually carries one.
        if (exclusionSection != null && exclusionSection.isActive()) {
            meta.addProperty("has_exclude_mask", true);
            meta.addProperty("exclude_max_frac", exclusionSection.maxFrac());
            meta.addProperty("exclude_trim", exclusionSection.trimEnds() ? 1.0 : 0.0);
            meta.addProperty("exclude_erase", exclusionSection.erase() ? 1.0 : 0.0);
        }
        meta.add("overrides", overrides);
        // When a run set a log target, tell the server to tee its full transcript there.
        if (runServerLogDir != null && runServerLogFile != null) {
            meta.addProperty("log_dir", runServerLogDir);
            meta.addProperty("log_file", runServerLogFile);
        }
        return GSON.toJson(meta);
    }

    /**
     * Every control that changes what a run produces, as one text block for the log header and the
     * runs.json index. Pair it with the "Run ID" measurement on each object: given a detection you
     * can read its run id, find that line in runs.json, and see exactly how it was made. The
     * header previously recorded region and downsample but not the tracer, two-pass or ridge --
     * so "was Two-pass on for these objects?" had no answer after the fact.
     */
    /**
     * Everything needed to REPRODUCE this run, written into the run log and {@code runs.json}.
     *
     * <p>The first lines are the readable summary. The rest is deliberately terse -- raw keys and
     * values rather than friendly labels -- because the point is reproducibility, not prose.</p>
     *
     * <p>The {@code meta=} line is the ACTUAL JSON sent to the server, built by the same
     * {@link #buildMeta} the run uses, so it cannot drift from what was really applied; every FIRE
     * parameter, guard, tracer choice and exclusion rule is in there under its server key.</p>
     *
     * <p>{@code norm=} carries what the meta alone does not: the global intensity transform. The
     * server is sent a pre-scaled 0-255 image, so {@code thresh_im2} on its own is meaningless
     * without the {@code lo}/{@code hi} that produced it -- recovering a past run's actual
     * threshold previously meant solving backwards from a tile's foreground percentage. Native
     * threshold, white point and pixel size are all recorded here instead.</p>
     *
     * <p>{@code client=} is the settings that never reach the server (output type, clipping,
     * logging), which decide what ends up in the hierarchy.</p>
     */
    private String runSettingsBlock() {
        double d = downsampleSpinner == null ? 1.0 : downsampleSpinner.getValue();
        StringBuilder sb = new StringBuilder();

        // ---- readable summary -------------------------------------------------------------
        sb.append("Tracer: ").append(tracerCombo == null ? "?" : tracerCombo.getValue());
        if (presetCombo != null) {
            sb.append("   Preset: ").append(presetCombo.getValue());
        }
        if (flowParallelSpinner != null && tracerCombo != null
                && TRACER_FLOW.equals(tracerCombo.getValue())) {
            sb.append("   Parallel fibre distance: ")
              .append(trimNum(flowParallelSpinner.getValue())).append(" ").append(unitLabel());
        }
        sb.append("\nTwo-pass: ").append(twoPassCheck != null && twoPassCheck.isSelected());
        if (twoPassCheck != null && twoPassCheck.isSelected() && secondThresholdSpinner != null) {
            sb.append(" (2nd threshold ").append(trimNum(secondThresholdSpinner.getValue())).append(")");
        }
        sb.append("   Ridge: ").append(ridgeCheck != null && ridgeCheck.isSelected());
        sb.append("   Raw mode: ").append(rawModeCheck != null && rawModeCheck.isSelected());
        if (exclusionSection != null) {
            sb.append("\nExclusion regions: ").append(exclusionSection.isActive()
                    ? "ON (" + exclusionSection.maxFrac() * 100 + "% drop, trim="
                        + exclusionSection.trimEnds() + ", erase=" + exclusionSection.erase() + ")"
                    : "off");
        }
        if (flowFilterCheck != null) {
            sb.append("\nFlow filter: ").append(flowFilterCheck.isSelected());
        }
        if (densityCapSpinner != null) {
            sb.append("   Anti-flow check: ").append(antiFlowCheck != null && antiFlowCheck.isSelected());
            sb.append("   Guards: density ").append(trimNum(densityCapSpinner.getValue()))
              .append("%  seed ").append(trimNum(seedCapSpinner.getValue()))
              .append("  web ").append(trimNum(webCapSpinner.getValue()))
              .append("  timeout ").append(trimNum(fireTimeoutSpinner.getValue())).append("s");
        }

        // ---- reproducible detail ----------------------------------------------------------
        sb.append("\n--- reproduce ---");
        sb.append("\nnorm=").append(normBlock(d));
        sb.append("\nclient=").append(clientBlock());
        try {
            sb.append("\nmeta=").append(buildMeta(d, tacsCheck != null && tacsCheck.isSelected(),
                    parseExtraQuietly()));
        } catch (RuntimeException ex) {
            sb.append("\nmeta=<unavailable: ").append(ex.getMessage()).append(">");
        }
        return sb.toString();
    }

    /**
     * The global intensity transform and the scale it is applied at: without these a recorded
     * {@code thresh_im2} cannot be turned back into a real intensity.
     */
    private String normBlock(double d) {
        StringBuilder b = new StringBuilder();
        b.append("ch=").append(chosenChannel());
        b.append(" down=").append(trimNum(d));
        b.append(" region=").append(rx).append(',').append(ry).append(',').append(rw).append(',').append(rh);
        b.append(" px_um=").append(pixelSizeMicrons > 0 ? trimNum(pixelSizeMicrons) : "none");
        b.append(" unit=").append(unitLabel());
        b.append(" thr_native=").append(trimNum(currentThreshold()));
        b.append(" normLo=").append(trimNum(normLo));
        b.append(" normHi=").append(trimNum(normHi));
        b.append(" serverNormHi=").append(trimNum(serverNormHi()));
        b.append(" thresh_im2=").append(serverThreshIm2());
        if (twoPassCheck != null && twoPassCheck.isSelected() && secondThresholdSpinner != null) {
            b.append(" thr2_native=").append(trimNum(secondThresholdSpinner.getValue()));
        }
        b.append(" sigma_im=").append(trimNum(smoothingSpinner.getValue()));
        return b.toString();
    }

    /** Settings that shape the OUTPUT but are never sent to the server. */
    private String clientBlock() {
        StringBuilder b = new StringBuilder();
        b.append("out=").append(detectionsCheck != null && detectionsCheck.isSelected()
                ? "detections" : "annotations");
        b.append(" clearExisting=").append(clearExistingCheck != null && clearExistingCheck.isSelected());
        b.append(" wholeImage=").append(wholeImageCheck != null && wholeImageCheck.isSelected());
        b.append(" restrictToObject=").append(maskToObjectCheck != null && maskToObjectCheck.isSelected());
        b.append(" saveLog=").append(saveLogCheck != null && saveLogCheck.isSelected());
        b.append(" widthByThickness=").append(thicknessWidthCheck != null && thicknessWidthCheck.isSelected());
        b.append(" previewScope=").append(previewScopeBox == null ? "?" : previewScopeBox.getValue());
        b.append(" maxTileDim=").append(maxTileDim());
        b.append(" tacs=").append(tacsCheck != null && tacsCheck.isSelected());
        if (tacsCheck != null && tacsCheck.isSelected()) {
            b.append(" boundary=").append(boundaryBox == null ? "?" : boundaryBox.getValue());
            b.append(" tacsZone=").append(trimNum(tacsZoneSpinner.getValue()));
        }
        if (exclusionSection != null && exclusionSection.isActive()) {
            b.append(" excl=").append(exclusionSection.describeSource());
        }
        String extras = extraParamsArea == null ? "" : extraParamsArea.getText();
        if (extras != null && !extras.isBlank()) {
            b.append(" extraParams=").append(extras.replace("\n", " ").trim());
        }
        return b.toString();
    }

    /** Parse the Extra params box, or null when it is empty/invalid (the log must never fail a run). */
    private JsonObject parseExtraQuietly() {
        try {
            return parseExtra();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ROI boundaryRoi() {
        int idx = boundaryBox.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= boundaryOptions.size()) {
            return null;
        }
        return boundaryOptions.get(idx).getROI();
    }

    private void runPreview() {
        final int channel = chosenChannel();
        final double downsample = downsampleSpinner.getValue();
        final boolean tacs = tacsCheck.isSelected();
        final ROI boundary = tacs ? boundaryRoi() : null;
        if (tacs && boundary == null) {
            Dialogs.showErrorMessage(TITLE, "TACS is on but no boundary annotation is selected.");
            return;
        }
        // Validate the free-form JSON box before doing any work.
        final JsonObject extra;
        try {
            extra = parseExtra();
        } catch (JsonSyntaxException | IllegalStateException ex) {
            Dialogs.showErrorMessage(
                    TITLE, "Extra params is not valid JSON object:\n" + ex.getMessage());
            return;
        }
        FiberSocketPreferences.setCollagenChannel(channel);
        saveLastSettings(); // remember these settings for THIS image (restored if it is reopened)
        final boolean wholeRegion = previewScopeBox.getSelectionModel().getSelectedIndex() == 1;
        setBusy(true, "Reading region + running FIRE...");
        runRestrictRoi = interactiveRestrictRoi();

        // Live pop-in: for a whole-region preview, paint each tile's fibers as it finishes (the
        // final stitched set replaces them when analyzeFull returns) so you can watch progress.
        final List<FiberSocketClient.Fiber> liveAcc =
                java.util.Collections.synchronizedList(new ArrayList<>());
        final TileSink previewSink = !wholeRegion ? null : (idx, total, coreFibers, tile) -> {
            if (coreFibers == null || coreFibers.isEmpty()) {
                return;
            }
            Platform.runLater(() -> {
                liveAcc.addAll(coreFibers);
                lastFibers = new ArrayList<>(liveAcc);
                redrawFibers();
            });
        };
        final RunLog previewLog = beginPreviewLog(
                wholeRegion ? "Preview (whole region)" : "Preview (one tile)");
        final java.util.function.Consumer<String> status = s -> {
            statusAsync(s);
            previewLog.line(s);
        };
        new Thread(() -> {
            try {
                if (!guardHeavyRun(channel, downsample,
                        wholeRegion ? "Whole-region preview" : "Tile preview", wholeRegion)) {
                    setBusy(false, "Cancelled — settings unchanged.");
                    return;
                }
                AnalyzeResult res = wholeRegion
                        ? analyzeRegion(channel, downsample, tacs, boundary, extra, status, previewSink)
                        : analyzeOneTile(channel, downsample, extra, status);
                previewLog.line(diagnostics(res).trim());
                lastFibers = res.fibers;
                lastDownsample = downsample;
                lastWasFull = wholeRegion;
                lastSignature = settingsSignature();

                int rwA = (int) Math.ceil(rw / downsample);
                int rhA = (int) Math.ceil(rh / downsample);
                int bgFactor = Math.max(1, (int) Math.ceil(Math.max(rwA, rhA) / 1600.0));
                double bgDown = downsample * bgFactor;
                BufferedImage bgRegion = server.readRegion(
                        RegionRequest.createInstance(server.getPath(), bgDown, rx, ry, rw, rh));
                BufferedImage bgGray = ChannelExtractor.toGray(bgRegion, channel, normLo, normHi);
                final var fxImg = ChannelExtractor.toFxImage(bgGray);
                final int gw = bgGray.getWidth();
                final int gh = bgGray.getHeight();
                final double bgRatio = 1.0 / bgFactor;
                final String fb = res.backend;
                final int n = res.fibers.size();
                final int ntiles = res.tiles;
                final boolean whole = wholeRegion;
                final String diag = diagnostics(res);
                Platform.runLater(() -> {
                    lastBgGray = bgGray;
                    lastBgFx = fxImg;
                    lastBgW = gw;
                    lastBgH = gh;
                    lastBgRatio = bgRatio;
                    redrawPreview();
                    if ("synthetic".equals(fb)) {
                        setBusy(false, String.format(
                                "WARNING: SYNTHETIC fallback - FIRE settings have NO effect (%d lines).", n));
                    } else if (whole) {
                        setBusy(false, String.format(
                                "%d fibers, %d tile(s) at downsample %.0f (stitched%s).%s",
                                n, ntiles, downsample, tacs ? " + TACS" : "", diag));
                    } else {
                        setBusy(false, String.format(
                                "%d fibers in 1 preview tile (downsample %.0f).%s",
                                n, downsample, diag));
                    }
                });
            } catch (Exception ex) {
                logger.error("Preview failed", ex);
                previewLog.line("Preview FAILED: " + ex.getMessage());
                fail("Preview failed: " + ex.getMessage());
            } finally {
                endPreviewLog(previewLog);
            }
        }, "tmequant-preview").start();
    }

    private void statusAsync(String msg) {
        setBusy(true, msg);
    }

    /**
     * Start a rolling PREVIEW log (client + server) for interactive Preview / "Show tile in viewer"
     * runs. Unlike a full "Apply to chosen area" run — which gets its own timestamped file — every preview
     * that day APPENDS into one pair of files ({@code fiber-preview-<yyyyMMdd>-client.txt} /
     * {@code -server.log}) so tuning failures are captured without a folder-per-preview. Sets
     * {@link #runServerLogDir}/{@link #runServerLogFile} so the server tees its transcript too.
     * Returns the shared no-op when there is no project or "Save run log to project" is off.
     * Pair every call with {@link #endPreviewLog}.
     */
    private RunLog beginPreviewLog(String kind) {
        Project<BufferedImage> proj = qupath.getProject();
        java.nio.file.Path dir = saveLogCheck.isSelected() ? RunLog.projectLogDir(proj) : null;
        String day = RunLog.dayStamp();
        if (dir != null) {
            runServerLogDir = dir.toString();
            runServerLogFile = "fiber-preview-" + day + "-server.log";
        } else {
            runServerLogDir = null;
            runServerLogFile = null;
        }
        String header = String.join("\n",
                "Region (full-res px): x=" + rx + " y=" + ry + " w=" + rw + " h=" + rh,
                "Downsample: " + downsampleSpinner.getValue()
                        + "   Threshold: " + trimNum(thresholdSpinner.getValue())
                        + "   Two-pass: " + (twoPassCheck != null && twoPassCheck.isSelected())
                        + "   Ridge: " + (ridgeCheck != null && ridgeCheck.isSelected()),
                runServerLogFile != null ? "Server transcript: " + runServerLogFile : "");
        return RunLog.open(dir, "fiber-preview-" + day + "-client.txt", kind, header);
    }

    /** Close a preview log and clear the server-log target so later runs set their own. */
    private void endPreviewLog(RunLog log) {
        if (log != null) {
            log.close();
        }
        runServerLogDir = null;
        runServerLogFile = null;
    }

    /**
     * Turn the run's diagnostics into an actionable sentence explaining a low/zero
     * fiber count: how many FIRE traced, how many the Min-length / Max-width filters
     * removed, whether dense tiles were auto-coarsened or skipped.
     */
    private String diagnostics(AnalyzeResult res) {
        StringBuilder sb = new StringBuilder();
        int traced = res.nTraced;
        // A FAILED tile (FIRE's linking step ran out of memory) also reports 0 traced, but the cause
        // is the opposite of "nothing to seed" — the tile was too big/dense. Diagnose that FIRST so
        // we don't tell the user to lower the threshold (which makes it worse).
        if (traced <= 0 && res.failedTiles > 0) {
            int total = Math.max(res.failedTiles, res.tiles);
            sb.append(String.format(
                    " %d of %d tile(s) FAILED — too many fibres for FIRE's linking step (not empty). "
                    + "Lower 'Max tile size' in Preferences, raise 'Seed spacing', or enable 'Ridge "
                    + "enhancement'. In a full run a failed tile is skipped and the rest still process.",
                    res.failedTiles, total));
            return sb.toString();
        }
        if (traced <= 0) {
            // FIRE traced nothing at all — a pure seeding/threshold problem, never a filter.
            sb.append(" FIRE traced 0 fibers: the seeding step found nothing. Lower Seed "
                    + "sensitivity, reduce Seed spacing, or lower the Background threshold.");
            return sb.toString();
        }
        // Show the WHOLE funnel so it's obvious WHERE fibers are lost — this is the answer to
        // "I can't tell which setting is the issue".
        int afterLen = Math.max(0, traced - res.nLengthDropped);
        int afterWid = Math.max(0, afterLen - res.nWidthDropped);
        sb.append(String.format(" Funnel: %d traced → %d after Min length → %d after Max width.",
                traced, afterLen, afterWid));
        // res.minLengthPx is in ANALYSED px; show Min length in the user's units.
        String minLenMsg = calibrated
                ? String.format("%.0f µm", res.minLengthPx * lastDownsample * pixelSizeMicrons)
                : String.format("%.0f px", res.minLengthPx * lastDownsample);
        // Name the dominant loss stage and give ONE targeted fix.
        if (res.nLengthDropped >= Math.max(1, traced) * 0.3
                && res.nLengthDropped >= res.nWidthDropped) {
            sb.append(String.format(" Min length (%s) removed %d — lower it to keep more.",
                    minLenMsg, res.nLengthDropped));
        } else if (res.nWidthDropped >= Math.max(1, traced) * 0.3) {
            sb.append(String.format(" Max fiber width removed %d — raise it (0 = off).",
                    res.nWidthDropped));
        } else if (traced < 5) {
            // Few traced AND filters barely touched them ⇒ the seeding stage is the bottleneck.
            // This is the "stuck at 2 fibers" case the funnel above otherwise looks silent on.
            sb.append(" Few fibers were traced before any filtering — the seeding step is the "
                    + "limit, not the filters: lower Seed sensitivity, reduce Seed spacing, or "
                    + "lower the Background threshold to seed more fibers. "
                    + "(Tip: trace your fibers as lines and use 'Calibrate from traced lines'.)");
        }
        if (res.autoRaisedTiles > 0) {
            sb.append(String.format(" %d dense tile(s) auto-coarsened (raise Background threshold "
                    + "or Seed spacing for cleaner results).", res.autoRaisedTiles));
        }
        if (res.failedTiles > 0) {
            sb.append(String.format(" %d tile(s) too dense to analyse — raise Background threshold "
                    + "/ Seed spacing / Downsample.", res.failedTiles));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Parameter suggestion (grid search)
    // ------------------------------------------------------------------

    /** Grid-search FIRE params on this region (supervised if line annotations exist). */
    /** One contiguous tuning region: bounding box + masked gray + ground-truth lines. */
    private static final class Piece {
        final ROI roi;
        final int px, py, pw, ph;
        final double searchDown;
        final BufferedImage maskedGray;
        final JsonArray gt;
        final java.util.List<GtInfo> gtInfo; // parallel to gt: name + centroid of each GT line
        final int inRoiPx;
        Piece(ROI roi, int px, int py, int pw, int ph, double searchDown,
                BufferedImage maskedGray, JsonArray gt, java.util.List<GtInfo> gtInfo, int inRoiPx) {
            this.roi = roi; this.px = px; this.py = py; this.pw = pw; this.ph = ph;
            this.searchDown = searchDown; this.maskedGray = maskedGray; this.gt = gt;
            this.gtInfo = gtInfo; this.inRoiPx = inRoiPx;
        }
    }

    /** Identity of one traced GT line (for reporting which fibers were/weren't detected). */
    private static final class GtInfo {
        final String name;       // annotation name, or null/empty
        final double cx, cy;     // centroid in full-res image coordinates
        GtInfo(String name, double cx, double cy) {
            this.name = name; this.cx = cx; this.cy = cy;
        }
        String label(int globalIdx) {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return String.format("#%d (%.0f, %.0f)", globalIdx + 1, cx, cy);
        }
    }

    /** One parameter combo's score, aggregated across all pieces (the union). */
    private static final class AggResult {
        java.util.Map<String, Double> overrides;
        double score;
        int nDetected, nGt, matched;
        double recall, countRatio, orientErr, lenFactor;
        double coverage, straightness; // unsupervised display only
        boolean inBand;
        boolean supervised;
        // Global GT indices this combo detected (union across pieces) — drives the diversity view.
        java.util.Set<Integer> matchedGt = new java.util.LinkedHashSet<>();
    }

    /**
     * Grid-search FIRE params on the selected annotation. Supervised (count-primary,
     * iterative) when line annotations are present inside it; otherwise a single
     * coverage/straightness pass. Respects the annotation GEOMETRY (out-of-ROI pixels
     * are zeroed) and splits disjoint annotations into contiguous pieces that are
     * scored as one pooled union.
     */
    private void runSuggest() {
        final int channel = chosenChannel();
        FiberSocketPreferences.setCollagenChannel(channel);
        // Tune inside whatever annotation is selected in QuPath right now (not just the one
        // selected when the dialog opened), so you can draw/pick a region after opening.
        final boolean regionChanged = retargetRegionToSelection();
        if (regionChanged) {
            previewTileIdx = -1;
            previewZoom = 1.0;
            refreshBackground();
        }
        final List<ROI> pieceRois = collectSelectedAreaRois();
        if (pieceRois.isEmpty()) {
            Dialogs.showErrorMessage(TITLE,
                    "Select one or more area annotations in QuPath to tune inside, then click "
                    + "Suggest again.");
            return;
        }
        // --- size guard (synchronous, before any server work) ---
        // Each FIRE run is fast on a small masked region, so the guard is mostly about region
        // size and piece count; the broad coarse round is up to ~4 levels on 4 axes.
        final int rounds = maxRoundsSpinner.getValue();
        // Two-stage per round: stage A (count) + stage B (geometry), not a 7-axis Cartesian product.
        final int combos = (5 * 4 * 3 * 2) + (4 * 4 * 3); // ~168/piece coarse round
        final long totalRuns = (long) combos * pieceRois.size() * Math.max(1, rounds);
        long maxPiecePx = 0;
        for (ROI r : pieceRois) {
            double sd = capSearchDown((int) Math.ceil(r.getBoundsWidth()),
                    (int) Math.ceil(r.getBoundsHeight()));
            long wpx = (long) Math.ceil(r.getBoundsWidth() / sd);
            long hpx = (long) Math.ceil(r.getBoundsHeight() / sd);
            maxPiecePx = Math.max(maxPiecePx, wpx * hpx);
        }
        if (pieceRois.size() > 16 || maxPiecePx > 1200L * 1200) {
            Dialogs.showErrorMessage(SUGGEST_TITLE, String.format(
                    "Tuning region is too large: %d piece(s), largest ~%d analysed px. Pick a "
                    + "smaller representative region (ideally one area ≤ ~1000×1000 px).",
                    pieceRois.size(), maxPiecePx));
            return;
        }
        if (totalRuns > 400 || pieceRois.size() > 6 || maxPiecePx > 700L * 700) {
            boolean ok = Dialogs.showConfirmDialog(SUGGEST_TITLE, String.format(
                    "Tuning will run FIRE up to ~%d times (%d piece(s) × ≤%d combos × %d rounds). "
                    + "Each run is quick on a small region, but this can take a minute or two. "
                    + "Continue?",
                    totalRuns, pieceRois.size(), combos, rounds));
            if (!ok) {
                return;
            }
        }
        logger.info("SUGGEST requested: region '{}' {}x{} @({},{}), {} piece(s), downsample={}",
                selectedObjectName(), rw, rh, rx, ry, pieceRois.size(), downsampleSpinner.getValue());
        // Auto-calibrate Min length / Seed spacing from the traced lines FIRST, so the grid isn't
        // fighting a too-high Min length that silently drops real fibers (Opt-2 feeding Opt-4).
        LineStats ls = measureTracedLines(pieceRois);
        if (ls != null) {
            logger.info("SUGGEST pre-calibration: {}", applyLineCalibration(ls));
        }
        tuneSay("Searching… live progress shows above in ② Fiber detection; ranked results open in "
                + "a new window when done.", "info");
        setBusy(true, "Preparing tuning region(s)…");
        new Thread(() -> {
            try {
                List<Piece> pieces = new ArrayList<>();
                int totalGt = 0;
                for (ROI r : pieceRois) {
                    Piece p = buildPiece(r, channel);
                    if (p != null) {
                        pieces.add(p);
                        totalGt += p.gt.size();
                    }
                }
                if (pieces.isEmpty()) {
                    fail("No analysable pixels in the selected annotation.");
                    return;
                }
                final boolean supervised = totalGt > 0;
                final int nGt = totalGt;
                List<AggResult> agg = iterativeSuggest(pieces, supervised);
                if (agg == null || agg.isEmpty()) {
                    fail("Grid search returned no results (every combo failed).");
                    return;
                }
                final int maxDet = agg.stream().mapToInt(a -> a.nDetected).max().orElse(0);
                Platform.runLater(() -> {
                    if (maxDet == 0) {
                        // EVERY combo detected 0 fibers: the search can't help — the ① settings
                        // (what FIRE actually runs on), not the detection knobs, are the problem.
                        // The commonest cause is over-smoothing: heavy blur adds red to the mask
                        // while erasing the thin ridges FIRE seeds on.
                        String hint = smoothingSpinner.getValue() >= 2.5
                                ? "Smoothing is high (" + round1(smoothingSpinner.getValue())
                                  + "): it thickens the red mask but erases the thin ridges FIRE "
                                  + "traces. Lower Smoothing to ~1 and re-run. "
                                : "Check the Background threshold isn't excluding real fibers, and "
                                  + "try a smaller Downsample. ";
                        setBusy(false, "Suggest: 0 fibers for EVERY setting — this is an ① setting, "
                                + "not something tuning can fix.");
                        tuneSay("0 fibers detected for all " + agg.size() + " sets — the search "
                                + "can't help. " + hint + "The red mask shows pixels ABOVE THRESHOLD "
                                + "(necessary, not sufficient): more smoothing adds red but makes "
                                + "fibers HARDER to trace.", "warn");
                        Dialogs.showWarningNotification(TITLE,
                                "Every parameter set detected 0 fibers — the problem is in the ① "
                                + "settings, not the detection knobs. " + hint);
                        return;
                    }
                    String warn = (supervised && nGt < 8)
                            ? String.format(" Only %d ground-truth fibre%s — count target is noisy; "
                                    + "annotate more for a reliable result.", nGt, nGt == 1 ? "" : "s")
                            : "";
                    setBusy(false, String.format(
                            "Tuning done: %d piece(s), %s scoring.%s Pick a result to apply.",
                            pieces.size(), supervised ? "count" : "coverage", warn));
                    showGridResults(agg, supervised, pieces);
                });
            } catch (Exception ex) {
                logger.error("Grid search failed", ex);
                fail("Grid search failed: " + ex.getMessage());
            }
        }, "tmequant-gridsearch").start();
    }

    /** Raise the downsample until the longest analysed side is within the search cap. */
    private double capSearchDown(int pw, int ph) {
        double sd = downsampleSpinner.getValue();
        final int CAP = 640;
        while (Math.max(Math.ceil(pw / sd), Math.ceil(ph / sd)) > CAP) {
            sd *= 1.5;
        }
        return sd;
    }

    // ==================================================================================
    // Project-wide tuning: pool ground truth from area annotations of a training class across
    // selected project images, under ONE global normalization (no per-image/per-annotation norm).
    // ==================================================================================

    /** True if this project entry is the image currently open in the viewer. */
    private boolean isLiveEntry(ProjectImageEntry<BufferedImage> entry) {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null || qupath.getImageData() == null) {
            return false;
        }
        return entry.equals(project.getEntry(qupath.getImageData()));
    }

    /**
     * Area annotations in a hierarchy, optionally filtered by class. {@code classes == null}
     * matches EVERY area annotation (any class, including unclassified); otherwise only those whose
     * class is in the set.
     */
    private static List<PathObject> areaAnnotations(PathObjectHierarchy h,
            java.util.Set<PathClass> classes) {
        List<PathObject> out = new ArrayList<>();
        for (PathObject o : h.getAnnotationObjects()) {
            ROI r = o.getROI();
            if (r != null && r.isArea() && (classes == null || classes.contains(o.getPathClass()))) {
                out.add(o);
            }
        }
        return out;
    }

    /** Area annotations in a hierarchy whose class is one of the chosen training classes. */
    private static List<PathObject> trainingAreaObjects(PathObjectHierarchy h,
            java.util.Set<PathClass> classes) {
        return areaAnnotations(h, classes);
    }

    /** The ROIs of the given objects (skips any without an ROI). */
    private static List<ROI> roisOf(List<PathObject> objs) {
        List<ROI> out = new ArrayList<>(objs.size());
        for (PathObject o : objs) {
            if (o.getROI() != null) {
                out.add(o.getROI());
            }
        }
        return out;
    }

    /** The area annotations a batch run would target on this image, for the chosen mode. */
    private static List<PathObject> batchTargetObjects(ImageData<BufferedImage> id,
            ScopeSection.RegionMode mode, java.util.Set<PathClass> classes) {
        if (mode == ScopeSection.RegionMode.WHOLE_IMAGE) {
            return new ArrayList<>();
        }
        return areaAnnotations(id.getHierarchy(),
                mode == ScopeSection.RegionMode.CLASS_ANNOTATIONS ? classes : null);
    }

    /**
     * Compute ONE batch-wide normalization by pooling a coarse intensity sample of every training
     * region across the selected images ("Collecting image statistics"). The same scale is then
     * applied to every region so a native threshold means the same thing on every image.
     */
    private double[] computeGlobalNorm(List<ProjectImageEntry<BufferedImage>> entries,
            java.util.function.Function<ImageData<BufferedImage>, List<ROI>> regionProvider,
            int channel, StepProgress prog, int step,
            double analysisDown, long[] tileCountOut) throws Exception {
        final int maxPool = 4_000_000;
        java.util.List<double[]> chunks = new ArrayList<>();
        long pooled = 0;
        double gMin = Double.POSITIVE_INFINITY;
        int i = 0;
        for (ProjectImageEntry<BufferedImage> entry : entries) {
            prog.detail(step, "image " + (++i) + " / " + entries.size());
            boolean live = isLiveEntry(entry);
            ImageData<BufferedImage> id = live ? qupath.getImageData() : entry.readImageData();
            ImageServer<BufferedImage> srv = id.getServer();
            try {
                for (ROI roi : regionProvider.apply(id)) {
                    int px = (int) Math.floor(roi.getBoundsX());
                    int py = (int) Math.floor(roi.getBoundsY());
                    int pw = (int) Math.ceil(roi.getBoundsWidth());
                    int ph = (int) Math.ceil(roi.getBoundsHeight());
                    if (pw <= 0 || ph <= 0) {
                        continue;
                    }
                    // Count the analysis tiles this region will produce (same tiling the run uses),
                    // so the batch can show a whole-workflow tile count + ETA from the start.
                    if (tileCountOut != null && analysisDown > 0) {
                        int rwA = (int) Math.ceil(pw / analysisDown);
                        int rhA = (int) Math.ceil(ph / analysisDown);
                        tileCountOut[0] += computeTiles(rwA, rhA, analysisDown).size();
                    }
                    int statDown = Math.max(1, (int) Math.ceil(Math.max(pw, ph) / 256.0));
                    BufferedImage cr = srv.readRegion(
                            RegionRequest.createInstance(srv.getPath(), statDown, px, py, pw, ph));
                    if (cr == null) {
                        continue;
                    }
                    double[] s = ChannelExtractor.channelSamples(cr, channel);
                    if (pooled < maxPool) {
                        chunks.add(s);
                        pooled += s.length;
                    }
                    for (double v : s) {
                        if (Double.isFinite(v) && v < gMin) {
                            gMin = v;
                        }
                    }
                }
            } finally {
                if (!live) {
                    srv.close();
                }
            }
        }
        double[] all = new double[(int) Math.min(pooled, maxPool)];
        int idx = 0;
        for (double[] c : chunks) {
            for (double v : c) {
                if (idx < all.length && Double.isFinite(v)) {
                    all[idx++] = v;
                }
            }
        }
        if (idx == 0) {
            return new double[] {0, 255};
        }
        double[] sorted = java.util.Arrays.copyOf(all, idx);
        java.util.Arrays.sort(sorted);
        double lo = Double.isFinite(gMin) ? gMin : sorted[0];
        double hi = percentile(sorted, 0.995);
        if (hi <= lo) {
            hi = sorted[sorted.length - 1] > lo ? sorted[sorted.length - 1] : lo + 1.0;
        }
        return new double[] {lo, hi};
    }

    /**
     * Gather tuning pieces (masked gray + ground-truth lines) from training-class area annotations
     * across the selected images, all under the given global normalization. Opens each non-live
     * image's server and closes it in a finally (the cohort handle-leak gotcha).
     */
    private List<Piece> gatherProjectTuningPieces(List<ProjectImageEntry<BufferedImage>> entries,
            java.util.Set<PathClass> classes, int channel, double gLo, double gHi,
            StepProgress prog, int step) throws Exception {
        List<Piece> pieces = new ArrayList<>();
        int i = 0;
        for (ProjectImageEntry<BufferedImage> entry : entries) {
            prog.detail(step, "image " + (++i) + " / " + entries.size()
                    + " — " + pieces.size() + " region(s)");
            boolean live = isLiveEntry(entry);
            ImageData<BufferedImage> id = live ? qupath.getImageData() : entry.readImageData();
            ImageServer<BufferedImage> srv = id.getServer();
            try {
                PathObjectHierarchy h = id.getHierarchy();
                for (PathObject a : trainingAreaObjects(h, classes)) {
                    for (ROI part : splitContiguous(a.getROI())) {
                        Piece p = buildPieceFrom(srv, h, part, channel, gLo, gHi);
                        if (p != null) {
                            pieces.add(p);
                        }
                    }
                }
            } finally {
                if (!live) {
                    srv.close();
                }
            }
        }
        return pieces;
    }

    /** Open the "Tune across project" dialog: pick images + training class(es), run pooled tuning. */
    private void openProjectTuningDialog() {
        if (qupath.getProject() == null) {
            Dialogs.showErrorMessage(TITLE, "Open a QuPath PROJECT to tune across multiple images. "
                    + "(For a single open image, use the Calibrate / Suggest buttons.)");
            return;
        }
        Stage dlg = new Stage();
        dlg.initModality(Modality.NONE);
        dlg.setTitle("Tune across project");
        Label instr = new Label("Encouraged workflow: across the project, draw AREA annotations of a "
                + "class like 'Training' and trace the real fibers inside each as LINE annotations. "
                + "Tuning pools ALL of them under one consistent intensity scale and finds a single "
                + "parameter set. Choose the images, set region mode to 'Inside area annotations of "
                + "class' and tick your training class, then Run.");
        instr.setWrapText(true);
        instr.setMaxWidth(470);
        ScopeSection scope = new ScopeSection(qupath, "Choose images to tune on");
        StepProgress prog = new StepProgress();
        Button run = new Button("Run tuning");
        run.setDefaultButton(true);
        // Lock the image/scope inputs while tuning runs (re-enabled when the run re-enables 'run').
        scope.disableProperty().bind(run.disableProperty());
        run.setOnAction(e -> {
            java.util.List<ProjectImageEntry<BufferedImage>> entries = scope.resolveEntries();
            if (scope.isSpecificButEmpty()) {
                Dialogs.showWarningNotification(TITLE, "Pick at least one image.");
                return;
            }
            if (entries == null) { // current-image scope
                entries = new ArrayList<>();
                ProjectImageEntry<BufferedImage> pe =
                        qupath.getProject().getEntry(qupath.getImageData());
                if (pe != null) {
                    entries.add(pe);
                }
            }
            if (entries.isEmpty()) {
                Dialogs.showWarningNotification(TITLE, "No images resolved (is the current image in "
                        + "the project?).");
                return;
            }
            java.util.Set<PathClass> classes = scope.getSelectedClasses();
            if (classes.isEmpty()) {
                Dialogs.showWarningNotification(TITLE, "Set region mode to 'Inside area annotations "
                        + "of class' and tick your training class.");
                return;
            }
            run.setDisable(true);
            runProjectTuning(entries, classes, prog, run);
        });
        Label progHdr = new Label("Progress");
        progHdr.setStyle("-fx-font-weight: bold;");
        HBox runRow = new HBox(8, run);
        runRow.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(12, instr, scope, new javafx.scene.control.Separator(), progHdr, prog,
                runRow);
        root.setPadding(new Insets(14));
        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        dlg.setScene(new Scene(sp));
        dlg.setMinWidth(520);
        dlg.show();
    }

    /** Run the pooled project tuning on a worker thread, driving the StepProgress panel. */
    private void runProjectTuning(List<ProjectImageEntry<BufferedImage>> entries,
            java.util.Set<PathClass> classes, StepProgress prog, Button runBtn) {
        final int channel = chosenChannel();
        new Thread(() -> {
            try {
                int s1 = prog.addStep("Collecting image statistics", "Sampling every training region "
                        + "across the selected images to compute ONE consistent intensity scale for "
                        + "the whole batch (no per-image normalization).");
                prog.running(s1, "");
                double[] norm = computeGlobalNorm(entries,
                        id -> trainingRois(id, classes), channel, prog, s1, 0, null);
                prog.done(s1, String.format("scale %.0f–%.0f", norm[0], norm[1]));
                // Freeze the dialog to the global scale so threshIm2 and the pieces agree.
                Platform.runLater(() -> {
                    normLo = norm[0];
                    normHi = norm[1];
                    applyStatsToThresholdSpinner(false);
                    redrawMask();
                });

                int s2 = prog.addStep("Gathering training regions", "Reading each training annotation "
                        + "and its traced fibers, all under the global scale.");
                prog.running(s2, "");
                List<Piece> pieces = gatherProjectTuningPieces(
                        entries, classes, channel, norm[0], norm[1], prog, s2);
                int totalGt = pieces.stream().mapToInt(p -> p.gt.size()).sum();
                if (pieces.isEmpty()) {
                    prog.error(s2, "no training regions found");
                    Platform.runLater(() -> {
                        runBtn.setDisable(false);
                        Dialogs.showWarningNotification(TITLE, "No area annotations of the chosen "
                                + "class were found in the selected images.");
                    });
                    return;
                }
                prog.done(s2, pieces.size() + " region(s), " + totalGt + " traced fibre(s)");

                final boolean supervised = totalGt > 0;
                int s3 = prog.addStep("Searching parameters", "Two-stage FIRE grid search matched to "
                        + "the pooled traced fibers.");
                prog.running(s3, "");
                List<AggResult> agg = iterativeSuggest(pieces, supervised);
                int maxDet = (agg == null) ? 0 : agg.stream().mapToInt(a -> a.nDetected).max().orElse(0);
                if (agg == null || agg.isEmpty() || maxDet == 0) {
                    prog.error(s3, "0 fibers detected");
                    Platform.runLater(() -> {
                        runBtn.setDisable(false);
                        Dialogs.showWarningNotification(TITLE, "Project tuning detected 0 fibers for "
                                + "every setting — the ① settings (smoothing/threshold) prevent "
                                + "tracing. Lower Smoothing and retry.");
                    });
                    return;
                }
                prog.done(s3, "best matches " + agg.get(0).nDetected + " / " + totalGt);
                Platform.runLater(() -> {
                    runBtn.setDisable(false);
                    showGridResults(agg, supervised, pieces);
                });
            } catch (Exception ex) {
                logger.error("Project tuning failed", ex);
                Platform.runLater(() -> {
                    runBtn.setDisable(false);
                    Dialogs.showErrorMessage(TITLE, "Project tuning failed: " + ex.getMessage());
                });
            }
        }, "tmequant-project-tuning").start();
    }

    // ==================================================================================
    // Project-wide ANALYSIS: apply the frozen params + global normalization to whole-image or
    // class-annotation regions across selected images; write Fiber objects into each image's file.
    // ==================================================================================

    private List<ROI> trainingRois(ImageData<BufferedImage> id, java.util.Set<PathClass> classes) {
        List<ROI> out = new ArrayList<>();
        for (PathObject a : trainingAreaObjects(id.getHierarchy(), classes)) {
            out.add(a.getROI());
        }
        return out;
    }

    private static ROI wholeImageRoi(ImageServer<BufferedImage> srv) {
        return ROIs.createRectangleROI(
                0, 0, srv.getWidth(), srv.getHeight(), ImagePlane.getDefaultPlane());
    }

    /** {calibratedFlag(1/0), pixelSizeMicrons} for a server. */
    private static double[] calibrationOf(ImageServer<BufferedImage> srv) {
        PixelCalibration cal = srv.getPixelCalibration();
        double avg = (cal != null && cal.hasPixelSizeMicrons())
                ? cal.getAveragedPixelSizeMicrons() : Double.NaN;
        boolean ok = Double.isFinite(avg) && avg > 0;
        return new double[] {ok ? 1 : 0, ok ? avg : Double.NaN};
    }

    private static String safeName(ProjectImageEntry<BufferedImage> e) {
        String n = e.getImageName();
        return n == null ? "(image)" : n;
    }

    /** Set the analysis region fields from a region ROI, clamped to a server's bounds. */
    private void setBatchRegion(ROI region, ImageServer<BufferedImage> srv) {
        int x = Math.max(0, (int) Math.floor(region.getBoundsX()));
        int y = Math.max(0, (int) Math.floor(region.getBoundsY()));
        int w = Math.min(srv.getWidth() - x, (int) Math.ceil(region.getBoundsWidth()));
        int h = Math.min(srv.getHeight() - y, (int) Math.ceil(region.getBoundsHeight()));
        rx = x;
        ry = y;
        rw = Math.max(1, w);
        rh = Math.max(1, h);
    }

    /**
     * Run FIRE across the selected project images with the CURRENT (frozen) parameters and a global
     * normalization, writing Fiber objects into each image's data file. Reuses the tested tiled
     * {@link #analyzeFull} pipeline by swapping the dialog's per-image context (server / region /
     * norm / calibration) around each call, restoring it in a finally so the dialog is untouched.
     */
    /**
     * Raised from the per-tile tick to unwind the image currently being analysed when the user
     * presses Stop. A plain flag check between images is not enough: one whole-slide image can be
     * hours of work on its own, so the stop has to be noticed inside it.
     */
    private static final class BatchCancelled extends RuntimeException {
        BatchCancelled() {
            super("Batch stopped by the user");
        }
    }

    /** Set by the batch dialog's Stop button; cleared at the start of each run. */
    private final java.util.concurrent.atomic.AtomicBoolean batchCancel =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void runBatchAnalysis(List<ProjectImageEntry<BufferedImage>> entries,
            ScopeSection.RegionMode mode, java.util.Set<PathClass> classes, boolean clearExisting,
            boolean asDetections, boolean maskToObject, StepProgress prog, Button runBtn,
            Button stopBtn, Label summary) {
        batchCancel.set(false);
        final int channel = chosenChannel();
        final double downsample = downsampleSpinner.getValue();
        final double minLenUnit = minLenSpinner.getValue();
        final JsonObject extra;
        try {
            extra = parseExtra();
        } catch (JsonSyntaxException | IllegalStateException ex) {
            Dialogs.showErrorMessage(TITLE, "Extra params is not valid JSON:\n" + ex.getMessage());
            runBtn.setDisable(false);
            return;
        }
        final java.util.function.Function<ImageData<BufferedImage>, List<ROI>> regions =
                switch (mode) {
                    case WHOLE_IMAGE -> id -> java.util.List.of(wholeImageRoi(id.getServer()));
                    case ANY_ANNOTATION -> id -> roisOf(areaAnnotations(id.getHierarchy(), null));
                    case CLASS_ANNOTATIONS -> id -> roisOf(areaAnnotations(id.getHierarchy(), classes));
                };
        // Snapshot the dialog's context so we can restore it after the (context-swapping) run.
        final ImageServer<BufferedImage> oServer = server;
        final int oRx = rx, oRy = ry, oRw = rw, oRh = rh;
        final double oNormLo = normLo, oNormHi = normHi, oPix = pixelSizeMicrons;
        final boolean oCal = calibrated;
        final Project<BufferedImage> logProject = qupath.getProject();
        final java.nio.file.Path logDir =
                saveLogCheck.isSelected() ? RunLog.projectLogDir(logProject) : null;
        final String logStamp = RunLog.stamp();
        if (logDir != null) {
            runServerLogDir = logDir.toString();
            runServerLogFile = "fiber-run-" + logStamp + "-server.log";
        } else {
            runServerLogDir = null;
            runServerLogFile = null;
        }
        final double batchRunId = FiberObjectBuilder.runIdFromStamp(logStamp);
        FiberObjectBuilder.setRunId(batchRunId);
        RunLog.appendRunIndex(logDir, logStamp, batchRunId, runSettingsBlock());
        final RunLog runLog = RunLog.open(logDir, "fiber-run-" + logStamp + "-client.txt", "Batch",
                String.join("\n", runSettingsBlock(),
                        "Batch run: " + entries.size() + " image(s)",
                        "Region mode: " + mode
                                + (mode == ScopeSection.RegionMode.CLASS_ANNOTATIONS
                                        ? "   classes=" + classes : ""),
                        "Downsample: " + downsample + "   Max tile size (px): " + maxTileDim(),
                        "Output: " + (asDetections ? "detections" : "annotations")
                                + (maskToObject ? "   Restrict-to-object: ON" : "")
                                + (runServerLogFile != null
                                        ? "\nServer transcript: " + runServerLogFile : "")));
        new Thread(() -> {
            int totalFibers = 0, imagesDone = 0;
            try {
                setBusy(true, "Batch analysis running…");
                // --- Pre-run target check (annotation modes): count matching area annotations per
                // image and CONFIRM before the long run, so we're sure the right objects are hit. ---
                if (mode != ScopeSection.RegionMode.WHOLE_IMAGE) {
                    int chk = prog.addStep("Checking targets",
                            "Counting the area annotations this run will analyze inside, per image.");
                    prog.running(chk, "");
                    long totalAnn = 0;
                    int noneCount = 0;
                    int ci = 0;
                    for (ProjectImageEntry<BufferedImage> entry : entries) {
                        prog.detail(chk, "image " + (++ci) + " / " + entries.size());
                        boolean live = isLiveEntry(entry);
                        ImageData<BufferedImage> cid = live ? qupath.getImageData() : entry.readImageData();
                        int cnt;
                        try {
                            cnt = batchTargetObjects(cid, mode, classes).size();
                        } finally {
                            if (!live && cid != null) {
                                cid.getServer().close();
                            }
                        }
                        totalAnn += cnt;
                        if (cnt == 0) {
                            noneCount++;
                        }
                        runLog.line("target " + safeName(entry) + ": " + cnt + " annotation(s)");
                    }
                    String modeDesc = mode == ScopeSection.RegionMode.CLASS_ANNOTATIONS
                            ? "class(es) " + classes : "any area annotation";
                    prog.done(chk, totalAnn + " annotation(s); " + noneCount + " image(s) with none");
                    runLog.line("Target check: " + totalAnn + " annotation(s) across " + entries.size()
                            + " image(s), " + noneCount + " with none (" + modeDesc + ").");
                    if (totalAnn == 0) {
                        runLog.line("Aborted: no matching annotations.");
                        Platform.runLater(() -> Dialogs.showWarningNotification(TITLE,
                                "No matching area annotations (" + modeDesc + ") in the selected "
                                        + "image(s). Nothing to analyze."));
                        return; // finally restores context + re-enables the Run button
                    }
                    final long totalAnnF = totalAnn;
                    final int noneF = noneCount;
                    String confirmMsg = String.format(
                            "Analyze inside %d area annotation(s) (%s) across %d image(s).%s%n%nProceed?",
                            totalAnnF, modeDesc, entries.size(),
                            noneF > 0 ? String.format("%n%d image(s) have none and will be skipped.",
                                    noneF) : "");
                    if (!confirmOnFx("Batch targets", confirmMsg)) {
                        runLog.line("Batch cancelled by user at the target check.");
                        return; // finally restores
                    }
                }
                // Whole-batch progress line (top of the list): total analysis tiles across ALL
                // images/regions, so the ETA reflects the entire workflow, not one image.
                final int overall = prog.addStep("Overall progress",
                        "Fibre-detection tiles across ALL selected images/regions.");
                prog.running(overall, "counting tiles…");
                int s1 = prog.addStep("Collecting image statistics", "One consistent intensity scale "
                        + "across ALL selected images (no per-image normalization).");
                prog.running(s1, "");
                final long[] tileTotal = {0};
                double[] norm = computeGlobalNorm(entries, regions, channel, prog, s1,
                        downsample, tileTotal);
                prog.done(s1, String.format("scale %.0f–%.0f", norm[0], norm[1]));
                final long totalTiles = Math.max(1, tileTotal[0]);
                // RUNTIME heads-up for the unattended batch: crashes are prevented structurally (tile
                // + interpolation caps), so this only warns before a genuinely LONG run — a large
                // total tile count. No density/resolution warnings (those are the working settings).
                if (totalTiles > 800) {
                    if (!confirmOnFx("Long batch — proceed?", String.format(
                            "This batch will process %,d fibre-detection tiles across %d image(s) — "
                            + "expect a long, unattended run.%n%nProceed?",
                            totalTiles, entries.size()))) {
                        runLog.line("Batch cancelled by user at the long-run check.");
                        return; // finally restores
                    }
                }
                final java.util.concurrent.atomic.AtomicInteger tilesDone =
                        new java.util.concurrent.atomic.AtomicInteger(0);
                final long tilesStart = System.currentTimeMillis();
                prog.detail(overall, "0 / " + totalTiles + " tiles");
                batchTileTick = () -> {
                    int done = tilesDone.incrementAndGet();
                    long el = System.currentTimeMillis() - tilesStart;
                    long remain = Math.max(0, totalTiles - done);
                    String eta = done > 0
                            ? " — ~" + humanTime((long) ((double) el / done * remain)) + " left" : "";
                    prog.detail(overall, done + " / " + Math.max(totalTiles, done) + " tiles" + eta);
                    if (batchCancel.get()) {
                        throw new BatchCancelled(); // unwind the image in progress, not just the loop
                    }
                };
                int i = 0;
                for (ProjectImageEntry<BufferedImage> entry : entries) {
                    if (batchCancel.get()) {
                        runLog.line("STOPPED by the user after " + imagesDone + " image(s).");
                        break;
                    }
                    String nm = safeName(entry);
                    int si = prog.addStep("Analyze " + nm, "Detect fibers and write them into this "
                            + "image's objects, then save.");
                    prog.running(si, "image " + (++i) + " / " + entries.size());
                    boolean live = isLiveEntry(entry);
                    ImageData<BufferedImage> id = null;
                    ImageServer<BufferedImage> srv = null;
                    try {
                        id = live ? qupath.getImageData() : entry.readImageData();
                        srv = id.getServer();
                        double[] cal = calibrationOf(srv);
                        List<ROI> regs = regions.apply(id);
                        if (regs.isEmpty()) {
                            prog.done(si, "no regions — skipped");
                            continue;
                        }
                        List<PathObject> objects = new ArrayList<>();
                        int removed = 0;
                        int ri = 0;
                        for (ROI region : regs) {
                            final int regionNum = ++ri;
                            prog.detail(si, "region " + regionNum + " / " + regs.size());
                            // --- context swap (restored in the outer finally) ---
                            server = srv;
                            normLo = norm[0];
                            normHi = norm[1];
                            calibrated = cal[0] != 0;
                            pixelSizeMicrons = cal[1];
                            setBatchRegion(region, srv);
                            runRestrictRoi = (maskToObject && region.isArea()) ? region : null;
                            final String rTag = nm + " r" + regionNum + "/" + regs.size();
                            runLog.line(rTag + ": starting (region px x=" + rx + " y=" + ry
                                    + " w=" + rw + " h=" + rh + ")");
                            AnalyzeResult res = analyzeFull(channel, downsample, false, null, extra,
                                    m -> {
                                        prog.detail(si, m);
                                        runLog.line(rTag + ": " + m);
                                    });
                            if (clearExisting) {
                                removed += removeExistingFiberObjects(id.getHierarchy(), rx, ry, rw, rh);
                            }
                            ImagePlane plane = region.getImagePlane();
                            PathClass fiberClass =
                                    PathClass.fromString("Fiber", ColorTools.packRGB(0, 220, 0));
                            objects.addAll(FiberObjectBuilder.build(res.fibers, rx, ry, downsample,
                                    pixelSizeMicrons, plane, asDetections, fiberClass,
                                    runRestrictRoi, minLenUnit));
                        }
                        if (!objects.isEmpty()) {
                            id.getHierarchy().addObjects(objects);
                        }
                        entry.saveImageData(id);
                        totalFibers += objects.size();
                        imagesDone++;
                        prog.done(si, objects.size() + " fibers"
                                + (removed > 0 ? " (" + removed + " prior removed)" : ""));
                        runLog.line(nm + ": DONE — " + objects.size() + " fibre(s)"
                                + (removed > 0 ? " (" + removed + " prior removed)" : ""));
                    } catch (BatchCancelled stop) {
                        // A stop is not a failure of this image -- mark it and leave the loop.
                        prog.done(si, "stopped");
                        runLog.line(nm + ": STOPPED mid-image by the user.");
                        if (!live && srv != null) {
                            srv.close();
                        }
                        break;
                    } catch (Exception ex) {
                        prog.error(si, ex.getMessage());
                        logger.error("Batch analyze failed for {}", nm, ex);
                        runLog.line(nm + ": FAILED — " + ex.getMessage());
                    } finally {
                        if (!live && srv != null) {
                            srv.close();
                        }
                    }
                }
                prog.done(overall, tilesDone.get() + " / " + Math.max(totalTiles, tilesDone.get())
                        + " tiles");
                final int fc = totalFibers, ic = imagesDone, n = entries.size();
                final boolean stopped = batchCancel.get();
                runLog.line((stopped ? "BATCH STOPPED: " : "BATCH DONE: ") + ic + " / " + n
                        + " image(s), " + fc + " fibre(s) written");
                Platform.runLater(() -> {
                    // The notification is transient; if the user looked away it is gone. Put the
                    // same result on the dialog, where it stays until the next run.
                    summary.setText((stopped ? "Stopped. " : "Finished. ") + ic + " / " + n
                            + " image(s), " + fc + " fibre(s) written."
                            + (logDir != null ? "  Log: fiber-run-" + logStamp + "-client.txt" : ""));
                    summary.setVisible(true);
                    Dialogs.showInfoNotification(TITLE, ic + " / " + n
                            + " image(s) processed, " + fc + " fibers written. Use 'Reload data' to "
                            + "refresh the open image if it was one of them.");
                });
            } catch (Exception ex) {
                logger.error("Batch analysis failed", ex);
                runLog.line("BATCH FAILED: " + ex.getMessage());
                Platform.runLater(() -> {
                    summary.setText("Failed: " + ex.getMessage());
                    summary.setVisible(true);
                    Dialogs.showErrorMessage(TITLE, "Batch analysis failed: " + ex.getMessage());
                });
            } finally {
                batchTileTick = null; // stop whole-batch tile counting
                runLog.close();
                runServerLogDir = null;
                runServerLogFile = null;
                // Restore the dialog's own context no matter what.
                server = oServer;
                rx = oRx;
                ry = oRy;
                rw = oRw;
                rh = oRh;
                normLo = oNormLo;
                normHi = oNormHi;
                pixelSizeMicrons = oPix;
                calibrated = oCal;
                final boolean wasStopped = batchCancel.get();
                Platform.runLater(() -> {
                    runBtn.setDisable(false);
                    stopBtn.setDisable(true);
                    setBusy(false, wasStopped ? "Batch stopped." : "Batch analysis done.");
                });
            }
        }, "tmequant-batch").start();
    }

    /** Open the "Run across project" dialog (apply current params to many images). */
    private void openBatchAnalysisDialog() {
        if (qupath.getProject() == null) {
            Dialogs.showErrorMessage(TITLE, "Open a QuPath PROJECT to run across images.");
            return;
        }
        Stage dlg = new Stage();
        dlg.initModality(Modality.NONE);
        dlg.setTitle("Run across project");
        Label instr = new Label("Applies the CURRENT parameters (tune them first) to the selected "
                + "images under one consistent intensity scale, and writes Fiber objects into each "
                + "image's data file. Then use 'Reload data' to refresh the open image.");
        instr.setWrapText(true);
        instr.setMaxWidth(470);
        ScopeSection scope = new ScopeSection(qupath, "Choose images to analyze");
        CheckBox clearExisting = new CheckBox("Remove prior Fiber/TACS objects first");
        clearExisting.setSelected(true);
        CheckBox asDet = new CheckBox("Create detections (not annotations)");
        asDet.setSelected(true);  // batch runs whole images: annotations here almost always overload QuPath
        asDet.setTooltip(ttip("Strongly recommended ON for batch runs. Whole-image runs typically produce "
                + "thousands of fibres per image; as ANNOTATIONS more than ~" + ANNOTATION_WARN_COUNT
                + " per image can make QuPath very slow or crash it. Detections keep every measurement."));
        CheckBox maskCb = new CheckBox("Restrict to each annotation (mask + clip)");
        maskCb.setSelected(maskToObjectCheck.isSelected());
        maskCb.setTooltip(ttip("Zero pixels outside each target annotation before FIRE, and "
                + "clip fibres to it. Requires an annotation region mode (by class, or any)."));
        StepProgress prog = new StepProgress();
        Button run = new Button("Run analysis");
        run.setDefaultButton(true);
        // A batch can run for hours across a project, so it needs a way out that is not "quit
        // QuPath": Stop finishes the tile in flight, then unwinds. Close is here because the
        // window otherwise offered no way to dismiss it except the title-bar X.
        Button stop = new Button("Stop");
        stop.setDisable(true);
        stop.setTooltip(ttip("Stop after the current tile. Images already finished keep their "
                + "fibres — they were saved as each image completed — and the image that was "
                + "part-way through is left untouched."));
        Button closeBatch = new Button("Close");
        closeBatch.setTooltip(ttip("Close this window. A run already under way keeps going; "
                + "press Stop first if you want it to end."));
        Label summary = new Label();
        summary.setWrapText(true);
        summary.setMaxWidth(470);
        summary.setVisible(false);
        summary.setStyle("-fx-font-weight: bold;");
        // Lock every input while a run is in progress: the run methods disable this button at the
        // start and re-enable it on finish/cancel, so binding the inputs to it locks them in step.
        scope.disableProperty().bind(run.disableProperty());
        clearExisting.disableProperty().bind(run.disableProperty());
        asDet.disableProperty().bind(run.disableProperty());
        maskCb.disableProperty().bind(run.disableProperty());
        run.setOnAction(e -> {
            java.util.List<ProjectImageEntry<BufferedImage>> entries = scope.resolveEntries();
            if (scope.isSpecificButEmpty()) {
                Dialogs.showWarningNotification(TITLE, "Pick at least one image.");
                return;
            }
            if (entries == null) {
                entries = new ArrayList<>();
                ProjectImageEntry<BufferedImage> pe =
                        qupath.getProject().getEntry(qupath.getImageData());
                if (pe != null) {
                    entries.add(pe);
                }
            }
            if (entries.isEmpty()) {
                Dialogs.showWarningNotification(TITLE, "No images resolved.");
                return;
            }
            if (scope.getRegionMode() == ScopeSection.RegionMode.CLASS_ANNOTATIONS
                    && scope.getSelectedClasses().isEmpty()) {
                Dialogs.showWarningNotification(TITLE, "Tick the annotation class(es), or choose "
                        + "'Whole image'.");
                return;
            }
            if (maskCb.isSelected()
                    && scope.getRegionMode() == ScopeSection.RegionMode.WHOLE_IMAGE) {
                Dialogs.showWarningNotification(TITLE, "'Restrict to each annotation' needs an "
                        + "annotation region mode (by class, or any) — not whole image.");
                return;
            }
            if (!asDet.isSelected() && !Dialogs.showConfirmDialog(TITLE,
                    "Create fibres as ANNOTATIONS for " + entries.size() + " image(s)?\n\n"
                            + "Batch runs usually produce thousands of fibres per image. More than ~"
                            + ANNOTATION_WARN_COUNT + " annotations can make QuPath very slow or crash "
                            + "it. Detections keep the same measurements.\n\n"
                            + "OK = annotations anyway, Cancel = go back.")) {
                return;
            }
            run.setDisable(true);
            stop.setDisable(false);
            summary.setVisible(false);
            runBatchAnalysis(entries, scope.getRegionMode(), scope.getSelectedClasses(),
                    clearExisting.isSelected(), asDet.isSelected(), maskCb.isSelected(), prog, run,
                    stop, summary);
        });
        stop.setOnAction(e -> {
            batchCancel.set(true);
            stop.setDisable(true);
            setBusy(true, "Stopping after the current tile…");
        });
        closeBatch.setOnAction(e -> dlg.close());
        Label progHdr = new Label("Progress");
        progHdr.setStyle("-fx-font-weight: bold;");
        ButtonBar batchBar = new ButtonBar();
        ButtonBar.setButtonData(run, ButtonBar.ButtonData.APPLY);
        ButtonBar.setButtonData(stop, ButtonBar.ButtonData.OTHER);
        ButtonBar.setButtonData(closeBatch, ButtonBar.ButtonData.CANCEL_CLOSE);
        batchBar.getButtons().addAll(run, stop, closeBatch);
        VBox root = new VBox(12, instr, scope, clearExisting, asDet, maskCb,
                new javafx.scene.control.Separator(), progHdr, prog, summary, batchBar);
        root.setPadding(new Insets(14));
        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        dlg.setScene(new Scene(sp));
        dlg.setMinWidth(520);
        dlg.show();
    }

    /** Re-read the currently-open image's data file into the viewer (after a batch wrote to it). */
    private void reloadCurrentImage() {
        var viewer = qupath.getViewer();
        ImageData<BufferedImage> id = qupath.getImageData();
        Project<BufferedImage> project = qupath.getProject();
        if (viewer == null || id == null) {
            Dialogs.showWarningNotification(TITLE, "No open image to reload.");
            return;
        }
        if (project == null) {
            Dialogs.showWarningNotification(TITLE, "No project — nothing to reload from disk.");
            return;
        }
        ProjectImageEntry<BufferedImage> entry = project.getEntry(id);
        if (entry == null) {
            Dialogs.showWarningNotification(TITLE, "The open image isn't a project entry.");
            return;
        }
        try {
            ImageData<BufferedImage> fresh = entry.readImageData();
            viewer.setImageData(fresh);
            Dialogs.showInfoNotification(TITLE, "Reloaded " + safeName(entry) + " from disk.");
        } catch (Exception ex) {
            Dialogs.showErrorMessage(TITLE, "Reload failed: " + ex.getMessage());
        }
    }

    /**
     * All currently-selected AREA annotations as tuning pieces (each split into contiguous parts),
     * so Suggest/Calibrate can pool several sparse regions of one image at once — no need to densely
     * annotate the whole thing. Falls back to the primary region if the selection has no usable area.
     * In whole-image mode, uses the pinned region only.
     */
    private List<ROI> collectSelectedAreaRois() {
        List<ROI> out = new ArrayList<>();
        if (wholeImageCheck != null && wholeImageCheck.isSelected()) {
            if (selectedRoi != null) {
                out.addAll(splitContiguous(selectedRoi));
            }
            return out;
        }
        java.util.LinkedHashSet<ROI> areaRois = new java.util.LinkedHashSet<>();
        var sel = hierarchy.getSelectionModel().getSelectedObjects();
        if (sel != null) {
            for (PathObject o : sel) {
                if (o != null && o.isAnnotation() && o.hasROI() && o.getROI().isArea()) {
                    areaRois.add(o.getROI());
                }
            }
        }
        if (areaRois.isEmpty() && selectedRoi != null && selectedRoi.isArea()) {
            areaRois.add(selectedRoi); // fall back to the primary selection
        }
        for (ROI r : areaRois) {
            out.addAll(splitContiguous(r));
        }
        return out;
    }

    /** Split a (possibly disjoint) ROI into its contiguous parts; [roi] if single. */
    private List<ROI> splitContiguous(ROI roi) {
        List<ROI> out = new ArrayList<>();
        if (roi == null) {
            return out;
        }
        try {
            org.locationtech.jts.geom.Geometry g = roi.getGeometry();
            int n = g.getNumGeometries();
            if (n <= 1) {
                out.add(roi);
                return out;
            }
            ImagePlane plane = roi.getImagePlane();
            for (int i = 0; i < n; i++) {
                org.locationtech.jts.geom.Geometry gi = g.getGeometryN(i);
                if (gi.isEmpty() || gi.getArea() <= 0) {
                    continue;
                }
                out.add(GeometryTools.geometryToROI(gi, plane));
            }
            if (out.isEmpty()) {
                out.add(roi);
            }
        } catch (Exception ex) {
            logger.warn("splitContiguous failed ({}); using the whole ROI", ex.getMessage());
            out.clear();
            out.add(roi);
        }
        return out;
    }

    /** Read one piece's masked gray (out-of-ROI zeroed) + its ground-truth lines. */
    private Piece buildPiece(ROI roi, int channel) throws Exception {
        return buildPieceFrom(server, hierarchy, roi, channel, normLo, normHi);
    }

    /**
     * Build one tuning piece (masked gray + ground-truth lines) from an ARBITRARY image server +
     * hierarchy with a FIXED normalization. Parameterizing server/hierarchy/norm lets the SAME
     * masked-gray + GT-line extraction pool training regions across the whole project under one
     * consistent intensity scale (no per-image/per-annotation normalization).
     */
    private Piece buildPieceFrom(ImageServer<BufferedImage> srv, PathObjectHierarchy h, ROI roi,
            int channel, double gNormLo, double gNormHi) throws Exception {
        int px = (int) Math.floor(roi.getBoundsX());
        int py = (int) Math.floor(roi.getBoundsY());
        int pw = (int) Math.ceil(roi.getBoundsWidth());
        int ph = (int) Math.ceil(roi.getBoundsHeight());
        if (pw <= 0 || ph <= 0) {
            return null;
        }
        double sd = capSearchDown(pw, ph);
        BufferedImage region = srv.readRegion(
                RegionRequest.createInstance(srv.getPath(), sd, px, py, pw, ph));
        if (region == null) {
            return null;
        }
        BufferedImage gray = ChannelExtractor.toGray(region, channel, gNormLo, serverNormHi(gNormLo, gNormHi));
        BufferedImage mask = MaskRasterizer.rasterise(roi, px, py, sd, gray.getWidth(), gray.getHeight());
        int inRoi = applyMask(gray, mask);
        if (inRoi <= 0) {
            return null;
        }
        java.util.List<GtInfo> gtInfo = new ArrayList<>();
        JsonArray gt = collectGroundTruthLines(h, roi, px, py, sd, gtInfo);
        return new Piece(roi, px, py, pw, ph, sd, gray, gt, gtInfo, inRoi);
    }

    /** Zero every gray sample outside the ROI mask; returns the in-ROI pixel count. */
    private static int applyMask(BufferedImage gray, BufferedImage mask) {
        int w = gray.getWidth();
        int h = gray.getHeight();
        var graster = gray.getRaster();
        int[] gs = graster.getSamples(0, 0, w, h, 0, (int[]) null);
        int[] ms = mask.getRaster().getSamples(0, 0, w, h, 0, (int[]) null);
        int inside = 0;
        for (int i = 0; i < gs.length; i++) {
            if (ms[i] < 128) {
                gs[i] = 0;
            } else {
                inside++;
            }
        }
        graster.setSamples(0, 0, w, h, 0, gs);
        return inside;
    }

    /**
     * Iterative search over the fiber-detection axes (seed spacing, seed sensitivity, bend
     * angle, link angle). Threshold + smoothing + downsample are fixed at the user's current
     * values (they have a live preview). Coarse grid first, then re-centre a finer grid on the
     * best each round.
     */
    private List<AggResult> iterativeSuggest(List<Piece> pieces, boolean supervised) throws Exception {
        final double countTol = countTolSpinner.getValue() / 100.0;
        final int maxRounds = supervised ? maxRoundsSpinner.getValue() : 1;
        // Round 0 spans a BROAD range (dense → sparse seeding) so the search can reach high
        // fibre counts, not just hover near the current settings. The current values are
        // included so the result is never worse than what the user already has.
        int sc = seedSpacingAnalysedPx(downsampleSpinner.getValue());
        // --- COUNT-driving axes (stage A) ---
        int[] seeds = uniqInts(2, 4, 8, 16, Math.max(2, sc));
        // Sensitivity sweep MUST include values more sensitive (lower) than the current one —
        // otherwise the search can never find MORE fibers than the user already has (the old
        // 0.05/0.15/0.30 range only ever got less sensitive, so it plateaued at the start count).
        double[] lmps = uniqDoubles(0.02, 0.05, 0.12, clampD(seedSensSpinner.getValue(), 0.02, 0.9));
        // Link distance (thresh_linkd): how far apart traced segments join into one countable
        // fiber — a primary count lever (trade merging vs fragmentation).
        double ld0 = Math.max(2.0, linkDistAnalysedPx(downsampleSpinner.getValue()));
        double[] linkds = uniqDoubles(Math.max(2.0, ld0 * 0.5), ld0, ld0 * 1.6);
        // Nucleation threshold (thresh_Dxlink): lower => more nucleation points => more fibers.
        double nu0 = clampD(dxlinkSpinner.getValue(), 0.0, 10.0);
        double[] nucls = uniqDoubles(Math.max(0.05, nu0 * 0.5), nu0);
        // --- GEOMETRY axes (stage B) ---
        double[] bends = uniqDoubles(45, 65, 85, clampD(bendAngleSpinner.getValue(), 5, 89));
        double[] links = uniqDoubles(15, 35, 60, clampD(linkAngleSpinner.getValue(), 0, 89));
        // Cross-link search box (s_xlinkbox): the radius FIRE searches for nucleation/cross-links.
        double xb0 = Math.max(1.0, xlinkBoxAnalysedPx(downsampleSpinner.getValue()));
        double[] xboxes = uniqDoubles(Math.max(1.0, xb0 * 0.6), xb0, xb0 * 1.6);
        // Cross-stage holds (round 0 = current spinner values). Stage A holds geometry fixed at
        // these; stage B holds the count axes at stage A's winner.
        double bendFix = clampD(bendAngleSpinner.getValue(), 5, 89);
        double linkFix = clampD(linkAngleSpinner.getValue(), 0, 89);
        double xboxFix = xb0;

        logger.info("SUGGEST start (two-stage): pieces={} supervised={} | FIXED thr_im2={} "
                + "sigma_im={} downsample={} minLen={}{} | countTol={}% maxRounds={}",
                pieces.size(), supervised, threshIm2(), smoothingSpinner.getValue(),
                downsampleSpinner.getValue(), minLenSpinner.getValue(), unitLabel(),
                Math.round(countTol * 100), maxRounds);
        for (Piece p : pieces) {
            logger.info("  piece @({},{}) {}x{} px, searchDown={}, inROI={} px, gtLines={}",
                    p.px, p.py, p.pw, p.ph, p.searchDown, p.inRoiPx, p.gt.size());
        }

        java.util.Map<String, AggResult> globalBest = new java.util.LinkedHashMap<>();
        AggResult prevBest = null;
        int totalRuns = 0;
        for (int round = 0; round < maxRounds; round++) {
            // ---- Stage A: count-driving axes; geometry held at bend/link/xboxFix ----
            int comboA = seeds.length * lmps.length * linkds.length * nucls.length;
            totalRuns += comboA * pieces.size();
            statusAsync(String.format("Tuning round %d/%d — stage A (count): %d combos…",
                    round + 1, maxRounds, comboA));
            logger.info("SUGGEST round {}/{} stage A: {} combos/piece | seeds={} sens={} linkd={} "
                    + "nucl={} | hold bend={} link={} xbox={}", round + 1, maxRounds, comboA,
                    java.util.Arrays.toString(seeds), java.util.Arrays.toString(lmps),
                    java.util.Arrays.toString(linkds), java.util.Arrays.toString(nucls),
                    Math.round(bendFix), Math.round(linkFix), Math.round(xboxFix));
            List<AggResult> aggA = aggregatedSearch(pieces, seeds, lmps,
                    new double[] {bendFix}, new double[] {linkFix}, linkds, nucls,
                    new double[] {xboxFix}, supervised, countTol);
            if (aggA.isEmpty()) {
                logger.warn("SUGGEST round {}: stage A returned no results", round + 1);
                break;
            }
            for (AggResult ar : aggA) {
                AggResult cur = globalBest.get(comboKey(ar.overrides));
                if (cur == null || ar.score > cur.score) {
                    globalBest.put(comboKey(ar.overrides), ar);
                }
            }
            AggResult bestA = aggA.get(0);
            int bestSeed = (int) Math.round(bestA.overrides.getOrDefault("thresh_LMPdist", (double) sc));
            double bestLmp = bestA.overrides.getOrDefault("thresh_LMP", 0.05);
            double bestLinkd = bestA.overrides.getOrDefault("thresh_linkd", ld0);
            double bestNucl = bestA.overrides.getOrDefault("thresh_Dxlink", nu0);

            // ---- Stage B: geometry axes; count axes fixed at stage A's winner ----
            int comboB = bends.length * links.length * xboxes.length;
            totalRuns += comboB * pieces.size();
            statusAsync(String.format("Tuning round %d/%d — stage B (geometry): %d combos…",
                    round + 1, maxRounds, comboB));
            logger.info("SUGGEST round {}/{} stage B: {} combos/piece | bend={} link={} xbox={} | "
                    + "hold seed={} sens={} linkd={} nucl={}", round + 1, maxRounds, comboB,
                    java.util.Arrays.toString(bends), java.util.Arrays.toString(links),
                    java.util.Arrays.toString(xboxes), bestSeed, String.format("%.2f", bestLmp),
                    Math.round(bestLinkd), String.format("%.2f", bestNucl));
            List<AggResult> aggB = aggregatedSearch(pieces, new int[] {bestSeed},
                    new double[] {bestLmp}, bends, links, new double[] {bestLinkd},
                    new double[] {bestNucl}, xboxes, supervised, countTol);
            for (AggResult ar : aggB) {
                AggResult cur = globalBest.get(comboKey(ar.overrides));
                if (cur == null || ar.score > cur.score) {
                    globalBest.put(comboKey(ar.overrides), ar);
                }
            }

            AggResult best = bestA;
            if (!aggB.isEmpty() && aggB.get(0).score > best.score) {
                best = aggB.get(0);
            }
            logger.info("SUGGEST round {} best: score={} nDet={}{} | {}", round + 1,
                    String.format("%.3f", best.score), best.nDetected,
                    supervised ? ("/" + best.nGt + " recall=" + String.format("%.2f", best.recall)) : "",
                    describeParams(best.overrides));

            // Combined best across BOTH stages drives the next round's re-centring.
            bestSeed = (int) Math.round(best.overrides.getOrDefault("thresh_LMPdist", (double) bestSeed));
            bestLmp = best.overrides.getOrDefault("thresh_LMP", bestLmp);
            bestLinkd = best.overrides.getOrDefault("thresh_linkd", bestLinkd);
            bestNucl = best.overrides.getOrDefault("thresh_Dxlink", bestNucl);
            double bestBend = cosToExt(best.overrides.getOrDefault("thresh_ext", extToCos(bendFix)));
            double bestLink = cosToLinka(best.overrides.getOrDefault("thresh_linka", linkaToCos(linkFix)));
            double bestXbox = best.overrides.getOrDefault("s_xlinkbox", xboxFix);

            if (prevBest != null && best.score <= prevBest.score + 1e-4) {
                logger.info("SUGGEST converged at round {} (no score improvement)", round + 1);
                break;
            }
            prevBest = best;

            // Re-centre finer 3-level ranges on the combined best, shrinking each round.
            double f = 1.0 / (round + 1);
            int sStep = Math.max(1, (int) Math.round(bestSeed * 0.4 * f));
            seeds = uniqInts(Math.max(2, bestSeed - sStep), bestSeed, bestSeed + sStep);
            lmps = uniqDoubles(clampD(bestLmp - 0.1 * f, 0.02, 0.9), bestLmp,
                    clampD(bestLmp + 0.1 * f, 0.02, 0.9));
            linkds = uniqDoubles(clampD(bestLinkd - Math.max(2.0, bestLinkd * 0.4 * f), 2, 200),
                    bestLinkd, clampD(bestLinkd + Math.max(2.0, bestLinkd * 0.4 * f), 2, 200));
            nucls = uniqDoubles(clampD(bestNucl - Math.max(0.1, bestNucl * 0.5 * f), 0.05, 10),
                    bestNucl, clampD(bestNucl + Math.max(0.1, bestNucl * 0.5 * f), 0.05, 10));
            double bStep = Math.max(4, 18.0 * f);
            bends = uniqDoubles(clampD(bestBend - bStep, 5, 89), bestBend, clampD(bestBend + bStep, 5, 89));
            links = uniqDoubles(clampD(bestLink - bStep, 0, 89), bestLink, clampD(bestLink + bStep, 0, 89));
            xboxes = uniqDoubles(clampD(bestXbox - Math.max(1.0, bestXbox * 0.4 * f), 1, 50),
                    bestXbox, clampD(bestXbox + Math.max(1.0, bestXbox * 0.4 * f), 1, 50));
            bendFix = bestBend;
            linkFix = bestLink;
            xboxFix = bestXbox;
        }

        List<AggResult> out = new ArrayList<>(globalBest.values());
        out.sort((x, y) -> Double.compare(y.score, x.score));
        double bestScore = out.isEmpty() ? 0.0 : out.get(0).score;
        for (AggResult ar : out) {
            ar.inBand = ar.score > 0 && ar.score >= bestScore * 0.95;
        }
        suggestEvaluated = out.size();
        suggestTotalRuns = totalRuns;
        logger.info("SUGGEST done: {} FIRE runs across rounds, {} distinct param sets evaluated. "
                + "Top 5:", totalRuns, out.size());
        for (int i = 0; i < Math.min(5, out.size()); i++) {
            AggResult ar = out.get(i);
            logger.info("  #{} score={} nDet={}{} {} {}", i + 1, String.format("%.3f", ar.score),
                    ar.nDetected, supervised ? ("/" + ar.nGt) : "",
                    ar.inBand ? "★" : " ", describeParams(ar.overrides));
        }
        return out;
    }

    /** Compact "seed=.. sens=.. bend=.. link=.." for logs, from a combo's overrides. */
    private static String describeParams(java.util.Map<String, Double> o) {
        return String.format("seed=%d sens=%.2f bend=%.0f° link=%.0f° linkd=%.0fpx "
                + "nucl=%.2f xbox=%.0fpx",
                (int) Math.round(o.getOrDefault("thresh_LMPdist", 0.0)),
                o.getOrDefault("thresh_LMP", 0.0),
                cosToExt(o.getOrDefault("thresh_ext", 1.0)),
                cosToLinka(o.getOrDefault("thresh_linka", -1.0)),
                o.getOrDefault("thresh_linkd", 0.0),
                o.getOrDefault("thresh_Dxlink", 0.0),
                o.getOrDefault("s_xlinkbox", 0.0));
    }

    /** Run one grid across every piece and pool the per-combo results into the union. */
    private List<AggResult> aggregatedSearch(List<Piece> pieces, int[] seeds, double[] lmps,
            double[] bends, double[] links, double[] linkds, double[] nucls, double[] xboxes,
            boolean supervised, double countTol) throws Exception {
        boolean areaWeight = pieceWeightBox.getSelectionModel().getSelectedIndex() == 1;
        FiberSocketClient client = new FiberSocketClient(
                FiberSocketPreferences.getHost(), FiberSocketPreferences.getPort(), 3000, 600000);
        // key -> [nDet, nGt, matched, orientErr*w, lenFactor*w, wSum, score*w, coverage*w, straight*w]
        java.util.Map<String, double[]> acc = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Map<String, Double>> ovById = new java.util.LinkedHashMap<>();
        // key -> set of GLOBAL GT indices this combo detected (per-piece local indices + offset).
        java.util.Map<String, java.util.Set<Integer>> matchedByKey = new java.util.LinkedHashMap<>();
        // Stable global GT index = piece offset + local index; offsets must match the global
        // gtInfo concatenation order (pieces list order), incremented for EVERY piece.
        int[] gtOffsets = new int[pieces.size()];
        for (int i = 1; i < pieces.size(); i++) {
            gtOffsets[i] = gtOffsets[i - 1] + pieces.get(i - 1).gt.size();
        }
        for (int pi = 0; pi < pieces.size(); pi++) {
            Piece p = pieces.get(pi);
            final int gtOffset = gtOffsets[pi];
            String meta = buildGridMeta(p.searchDown, p.gt, seeds, lmps, bends, links, linkds,
                    nucls, xboxes, supervised, countTol);
            FiberSocketClient.GridSearchResponse resp = client.gridSearch(p.maskedGray, meta);
            if (resp == null || !resp.ok || resp.results == null) {
                logger.warn("SUGGEST piece @({},{}): grid search returned no results (resp={})",
                        p.px, p.py, resp == null ? "null" : (resp.ok ? "empty" : "not ok"));
                continue;
            }
            int maxDet = 0;
            for (FiberSocketClient.GridResult r : resp.results) {
                maxDet = Math.max(maxDet, r.nDetected != null ? r.nDetected : r.nFibers);
            }
            logger.info("SUGGEST piece @({},{}): {} combos scored, best n_detected this piece={}",
                    p.px, p.py, resp.results.size(), maxDet);
            for (FiberSocketClient.GridResult r : resp.results) {
                String key = comboKey(r.overrides);
                ovById.putIfAbsent(key, r.overrides);
                double[] a = acc.computeIfAbsent(key, k -> new double[9]);
                int nDet = r.nDetected != null ? r.nDetected : r.nFibers;
                int ngt = r.nGt != null ? r.nGt : 0;
                int matched = r.matched != null ? r.matched : 0;
                double oerr = r.orientErr != null ? r.orientErr : 90.0;
                double lf = r.lenFactor != null ? r.lenFactor : 0.0;
                double w = areaWeight ? Math.max(1, p.inRoiPx) : Math.max(1, nDet);
                a[0] += nDet; a[1] += ngt; a[2] += matched;
                a[3] += oerr * w; a[4] += lf * w; a[5] += w;
                a[6] += r.score * w;
                a[7] += (r.coverage != null ? r.coverage : 0.0) * w;
                a[8] += (r.straightness != null ? r.straightness : 0.0) * w;
                if (r.matchedGt != null && !r.matchedGt.isEmpty()) {
                    java.util.Set<Integer> set =
                            matchedByKey.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>());
                    for (Integer gi : r.matchedGt) {
                        if (gi != null) {
                            set.add(gtOffset + gi);
                        }
                    }
                }
            }
        }
        List<AggResult> out = new ArrayList<>();
        for (java.util.Map.Entry<String, double[]> e : acc.entrySet()) {
            double[] a = e.getValue();
            double wsum = a[5] > 0 ? a[5] : 1.0;
            AggResult ar = new AggResult();
            ar.supervised = supervised;
            ar.overrides = ovById.get(e.getKey());
            ar.nDetected = (int) a[0];
            ar.nGt = (int) a[1];
            ar.matched = (int) a[2];
            ar.orientErr = a[3] / wsum;
            ar.lenFactor = a[4] / wsum;
            ar.matchedGt = matchedByKey.getOrDefault(e.getKey(), new java.util.LinkedHashSet<>());
            if (supervised) {
                combineScores(ar, countTol);
            } else {
                ar.score = a[6] / wsum;
                ar.coverage = a[7] / wsum;
                ar.straightness = a[8] / wsum;
            }
            out.add(ar);
        }
        out.sort((x, y) -> Double.compare(y.score, x.score));
        double best = out.isEmpty() ? 0.0 : out.get(0).score;
        for (AggResult ar : out) {
            ar.inBand = ar.score > 0 && ar.score >= best * 0.95;
        }
        return out;
    }

    /** Mirror the server's count-primary formula on the pooled union totals. */
    private static void combineScores(AggResult ar, double countTol) {
        final double countSigma = 0.35;
        int ngt = Math.max(1, ar.nGt);
        double countRatio = (double) ar.nDetected / ngt;
        ar.countRatio = countRatio;
        double dev = Math.abs(countRatio - 1.0);
        double countScore = dev <= countTol ? 1.0
                : Math.exp(-(dev - countTol) * (dev - countTol) / (2.0 * countSigma * countSigma));
        double recall = (double) ar.matched / ngt;                       // annotated fibers found
        double precision = ar.nDetected > 0 ? (double) ar.matched / ar.nDetected : 0.0;
        ar.recall = recall;
        double orientFactor = 1.0 - 0.5 * (ar.orientErr / 90.0);
        // Reward the RIGHT fibers, not just the right COUNT: recall (fraction of annotated fibers
        // actually matched) multiplies directly, and precision penalises spurious detections that
        // merely hit the target count. Otherwise "17 detected / target 17" can top the list while
        // detecting a DIFFERENT 17 than were annotated.
        ar.score = countScore * recall * (0.5 + 0.5 * precision)
                * (0.7 + 0.2 * ar.lenFactor + 0.1 * orientFactor);
    }

    /** Stable key over the searched detection axes (base overrides are identical across pieces). */
    private static String comboKey(java.util.Map<String, Double> o) {
        return Math.round(o.getOrDefault("thresh_LMPdist", 0.0)) + "_"
                + String.format("%.3f", o.getOrDefault("thresh_LMP", 0.0)) + "_"
                + String.format("%.3f", o.getOrDefault("thresh_ext", 0.0)) + "_"
                + String.format("%.3f", o.getOrDefault("thresh_linka", 0.0)) + "_"
                + Math.round(o.getOrDefault("thresh_linkd", 0.0)) + "_"
                + String.format("%.2f", o.getOrDefault("thresh_Dxlink", 0.0)) + "_"
                + Math.round(o.getOrDefault("s_xlinkbox", 0.0));
    }

    private static JsonArray intArr(int[] vals) {
        JsonArray a = new JsonArray();
        for (int v : vals) {
            a.add(v);
        }
        return a;
    }

    /** Append one TSV row per tuned area so several regions can be compared offline. */
    private void exportSuggestMeasurements(List<AggResult> agg, List<Piece> pieces,
            boolean supervised) {
        if (agg == null || agg.isEmpty()) {
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Save tuning measurements (append)");
        fc.setInitialFileName("tme-quant_tuning.tsv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("TSV", "*.tsv"));
        File file = fc.showSaveDialog(suggestBtn.getScene().getWindow());
        if (file == null) {
            return;
        }
        if (!file.getName().toLowerCase().endsWith(".tsv")) {
            file = new File(file.getParentFile(), file.getName() + ".tsv");
        }
        AggResult b = agg.get(0);
        int nGt = b.nGt;
        long inRoi = 0;
        for (Piece p : pieces) {
            inRoi += p.inRoiPx;
        }
        String[] cols = {
            java.time.LocalDateTime.now().toString(),
            new File(server.getPath()).getName().replaceAll("[\\t\\n\\r]", " "),
            sanitize(selectedObjectName()),
            Integer.toString(pieces.size()),
            Integer.toString(rw), Integer.toString(rh), Long.toString(inRoi),
            Integer.toString(nGt),
            Integer.toString((int) Math.round(b.overrides.getOrDefault("thresh_LMPdist", 0.0))),
            String.format("%.2f", b.overrides.getOrDefault("thresh_LMP", 0.0)),
            String.format("%.0f", cosToExt(b.overrides.getOrDefault("thresh_ext", 1.0))),
            String.format("%.0f", cosToLinka(b.overrides.getOrDefault("thresh_linka", -1.0))),
            Integer.toString(b.nDetected),
            String.format("%.2f", b.countRatio),
            b.inBand ? "Y" : "N",
            String.format("%.2f", b.lenFactor),
            String.format("%.1f", b.orientErr),
            String.format("%.3f", b.score),
            altSummary(agg),
        };
        boolean writeHeader = !file.exists() || file.length() == 0;
        try (java.io.FileWriter w = new java.io.FileWriter(file, true)) {
            if (writeHeader) {
                w.write("timestamp\timage\tannotation\tn_pieces\tregion_w\tregion_h\tin_roi_px\t"
                        + "n_gt\tseed\tseed_sens\tbend_deg\tlink_deg\tn_detected\tcount_ratio\t"
                        + "in_band\tlen_factor\torient_err\tscore\talternates\n");
            }
            w.write(String.join("\t", cols));
            w.write("\n");
            setBusy(false, "Saved tuning measurements to " + file.getName());
        } catch (java.io.IOException ex) {
            logger.error("Save measurements failed", ex);
            Dialogs.showErrorMessage(TITLE, "Save failed: " + ex.getMessage());
        }
    }

    private static String altSummary(List<AggResult> agg) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(3, agg.size());
        for (int i = 1; i < n; i++) {
            AggResult a = agg.get(i);
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("seed%d/sens%.2f/bend%.0f/link%.0f:%.2f",
                    (int) Math.round(a.overrides.getOrDefault("thresh_LMPdist", 0.0)),
                    a.overrides.getOrDefault("thresh_LMP", 0.0),
                    cosToExt(a.overrides.getOrDefault("thresh_ext", 1.0)),
                    cosToLinka(a.overrides.getOrDefault("thresh_linka", -1.0)), a.score));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private String selectedObjectName() {
        try {
            PathObject sel = hierarchy.getSelectionModel().getSelectedObject();
            if (sel != null && sel.getName() != null) {
                return sel.getName();
            }
        } catch (Exception ignore) {
            // fall through
        }
        return String.format("roi@(%d,%d)", rx, ry);
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\n\\r]", " ");
    }

    /**
     * Line / polyline annotations lying in the region become ground truth, converted
     * to region-analysed coords (the frame the server scores fibers in). Excludes the
     * selection itself and the TACS boundary.
     */
    /** Ground-truth LINE annotations whose centroid is inside {@code piece}, in piece-analysed px. */
    private JsonArray collectGroundTruthLines(ROI piece, int px, int py, double searchDown) {
        return collectGroundTruthLines(hierarchy, piece, px, py, searchDown, null);
    }

    private JsonArray collectGroundTruthLines(ROI piece, int px, int py, double searchDown,
            java.util.List<GtInfo> infoOut) {
        return collectGroundTruthLines(hierarchy, piece, px, py, searchDown, infoOut);
    }

    /**
     * Collect the GT line annotations inside a piece (from the GIVEN hierarchy) as analysed-px
     * polylines. When {@code infoOut} is non-null, also append each line's identity (name +
     * full-res centroid) in the SAME order, so a combo's matched-GT indices map back to specific
     * annotations. Parameterizing the hierarchy lets tuning pool ground truth across project images.
     */
    private JsonArray collectGroundTruthLines(PathObjectHierarchy h, ROI piece, int px, int py,
            double searchDown, java.util.List<GtInfo> infoOut) {
        JsonArray arr = new JsonArray();
        // Boundary exclusion only applies to the live dialog hierarchy (TACS mode); for pooled
        // project hierarchies there's no dialog boundary. Area annotations are excluded anyway
        // by the isLine() test, so the tuning-region ROIs are never mistaken for ground truth.
        ROI bnd = (h == hierarchy && tacsCheck.isSelected()) ? boundaryRoi() : null;
        for (PathObject o : h.getAnnotationObjects()) {
            ROI r = o.getROI();
            if (r == null || !r.isLine() || r == bnd) {
                continue;
            }
            double cx = r.getCentroidX();
            double cy = r.getCentroidY();
            if (!piece.contains(cx, cy)) {
                continue; // outside this contiguous piece's geometry
            }
            JsonArray line = new JsonArray();
            for (Point2 p : r.getAllPoints()) {
                JsonArray pt = new JsonArray();
                pt.add((p.getX() - px) / searchDown);
                pt.add((p.getY() - py) / searchDown);
                line.add(pt);
            }
            if (line.size() >= 2) {
                arr.add(line);
                if (infoOut != null) {
                    infoOut.add(new GtInfo(o.getName(), cx, cy));
                }
            }
        }
        return arr;
    }

    /**
     * Build a GRIDSRCH meta for one piece. The grid sweeps the four fiber-detection axes
     * (seed spacing, seed sensitivity, bend angle, link angle); threshold, smoothing and every
     * other knob are held FIXED at the user's current values as base overrides — those have a
     * live preview, so they aren't searched. Includes the piece's ground-truth lines and the
     * count-primary scoring config (when supervised).
     */
    private String buildGridMeta(double searchDown, JsonArray gtLines,
            int[] seeds, double[] lmps, double[] bends, double[] links, double[] linkds,
            double[] nucls, double[] xboxes, boolean supervised, double countTol) {
        int ll1 = Math.max(1, (int) Math.round(toAnalysedPx(minLenSpinner.getValue(), searchDown)));

        JsonObject base = new JsonObject(); // fixed knobs (incl. the user-set, previewable ones)
        base.addProperty("LL1", ll1);
        base.addProperty("thresh_im2", serverThreshIm2());     // FIXED — floored so bright collagen keeps gradient
        base.addProperty("sigma_im", smoothingSpinner.getValue()); // FIXED — set by eye via the mask
        // s_xlinkbox (Cross-link box) and thresh_Dxlink (Nucleation) are now SEARCHED (see grid).
        base.addProperty("sigma_d", distSmoothSpinner.getValue());
        base.addProperty("thresh_dang_aextend", extToCos(angleExtendSpinner.getValue()));
        // thresh_linkd (Link distance) is now a SEARCHED axis (see grid below), not fixed.
        base.addProperty("widMAX", maxWidthAnalysedPx(searchDown));
        base.addProperty("lam_dirdecay", dirDecaySpinner.getValue());
        base.addProperty("s_fiberdir", fiberDirAnalysedPx(searchDown));
        base.addProperty("thresh_flen", fireFlenAnalysedPx(searchDown));
        base.addProperty("s_maxspace", maxSpaceAnalysedPx(searchDown));
        base.addProperty("ang_interval", angIntervalSpinner.getValue());
        base.addProperty("lambda", lambdaSpinner.getValue());
        base.addProperty("thresh_dang_L", dangLenAnalysedPx(searchDown));

        JsonArray seedA = intArr(seeds);
        JsonArray lmpA = doubleArr(lmps);
        JsonArray extA = new JsonArray();
        for (double d : bends) {
            extA.add(extToCos(d));   // bend angle (deg) -> thresh_ext cosine
        }
        JsonArray linkA = new JsonArray();
        for (double d : links) {
            linkA.add(linkaToCos(d)); // link angle (deg) -> thresh_linka
        }
        JsonArray linkdA = doubleArr(linkds);
        // s_xlinkbox must be integer analysed px (C++ int arg); round each swept value.
        JsonArray xboxA = new JsonArray();
        for (double d : xboxes) {
            xboxA.add((int) Math.max(1, Math.round(d)));
        }
        JsonArray nuclA = doubleArr(nucls);
        JsonObject grid = new JsonObject();
        grid.add("thresh_LMPdist", seedA);
        grid.add("thresh_LMP", lmpA);
        grid.add("thresh_ext", extA);
        grid.add("thresh_linka", linkA);
        grid.add("thresh_linkd", linkdA);
        grid.add("thresh_Dxlink", nuclA);
        grid.add("s_xlinkbox", xboxA);

        JsonObject meta = new JsonObject();
        meta.addProperty("prescaled", true);
        meta.add("base_overrides", base);
        meta.add("grid", grid);
        meta.add("gt_lines", gtLines);
        meta.addProperty("match_dist", Math.max(8.0, ll1 * 0.6));
        meta.addProperty("match_angle", 20.0);
        // Cover the whole grid so no combo is silently dropped by the server's cap.
        meta.addProperty("max_combos", seedA.size() * lmpA.size() * extA.size() * linkA.size()
                * linkdA.size() * nuclA.size() * xboxA.size());
        meta.addProperty("top_k", 0); // aggregation only needs the scores, not fibres
        meta.addProperty("score_mode", supervised ? "count" : "match");
        meta.addProperty("count_tol", countTol);
        return GSON.toJson(meta);
    }

    private static JsonArray doubleArr(double[] vals) {
        JsonArray a = new JsonArray();
        for (double v : vals) {
            a.add(v);
        }
        return a;
    }

    /**
     * Non-modal chooser: list the ranked combos. Selecting a row loads its parameters into the
     * dialog immediately (live mask update); "Apply Selected and Preview" traces fibres for it. Stays open
     * so you can flip through options and compare.
     */
    /**
     * Show a new Suggest results window. Only one is kept open at a time: if a previous window is
     * still up, prompt (non-modal, always-on-top) to save its measurements before it's replaced —
     * so stacked windows don't confuse, and prior runs aren't lost silently.
     */
    private void showGridResults(List<AggResult> results, boolean supervised, List<Piece> pieces) {
        if (suggestStage != null && suggestStage.isShowing()) {
            Stage old = suggestStage;
            List<AggResult> oldResults = suggestResults;
            List<Piece> oldPieces = suggestPieces;
            boolean oldSup = suggestSupervised;
            Stage p = new Stage();
            p.initModality(Modality.NONE);
            p.setAlwaysOnTop(true); // stay reachable so the user can respond and save
            p.setTitle("Keep previous results?");
            Label msg = new Label("A previous Suggested-parameters window is open. Showing this new "
                    + "run will close it. If you want to keep the previous run, save its "
                    + "measurements to a text file first — then continue.");
            msg.setWrapText(true);
            msg.setPrefWidth(380);
            Button save = new Button("Save previous…");
            save.setTooltip(ttip("Append the previous run's ranked params + metrics to a TSV "
                    + "file. This dialog stays open so you can then continue."));
            save.setOnAction(e -> exportSuggestMeasurements(oldResults, oldPieces, oldSup));
            Button proceed = new Button("Close previous & show new");
            proceed.setDefaultButton(true);
            proceed.setOnAction(e -> {
                p.close();
                if (old != null) {
                    old.close();
                }
                displaySuggestResults(results, supervised, pieces);
            });
            javafx.scene.layout.HBox btns = new javafx.scene.layout.HBox(8, save, proceed);
            btns.setAlignment(Pos.CENTER_RIGHT);
            VBox root = new VBox(12, msg, btns);
            root.setPadding(new Insets(14));
            p.setScene(new Scene(root));
            p.show();
            return;
        }
        displaySuggestResults(results, supervised, pieces);
    }

    private void displaySuggestResults(List<AggResult> results, boolean supervised,
            List<Piece> pieces) {
        Stage dlg = new Stage();
        dlg.initModality(Modality.NONE); // non-modal so the main preview stays usable
        dlg.setTitle("Suggested parameters");
        // Track as the single open results window so the next run can offer to save/replace it.
        suggestStage = dlg;
        suggestResults = results;
        suggestPieces = pieces;
        suggestSupervised = supervised;
        dlg.setOnHidden(e -> {
            if (suggestStage == dlg) {
                suggestStage = null;
            }
        });

        ToggleGroup group = new ToggleGroup();
        VBox list = new VBox(6);
        list.setPadding(new Insets(10));
        // Selecting any row loads its parameters into the dialog (mask updates live).
        group.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv != null && nv.getUserData() instanceof AggResult) {
                applyOverrides(((AggResult) nv.getUserData()).overrides);
            }
        });

        // Global GT identity list (parallel to the global matched-GT indices on each AggResult).
        java.util.List<GtInfo> allGt = new ArrayList<>();
        for (Piece p : pieces) {
            allGt.addAll(p.gtInfo);
        }
        int totalGt = allGt.size();
        // Per-fiber coverage is only available if the server returned matched_gt (newer servers).
        boolean haveCoverage = results.stream()
                .anyMatch(r -> r.matchedGt != null && !r.matchedGt.isEmpty());
        RadioButton firstRow = null;
        String neverMsg = "";

        if (supervised && totalGt > 0 && haveCoverage && !results.isEmpty()) {
            java.util.Map<AggResult, Integer> rankOf = new java.util.IdentityHashMap<>();
            for (int i = 0; i < results.size(); i++) {
                rankOf.put(results.get(i), i);
            }
            java.util.Set<AggResult> shown =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            java.util.Set<Integer> covered = new java.util.LinkedHashSet<>();

            // --- Top scores (up to 3) ---
            list.getChildren().add(sectionLabel("Top scores"));
            int nTop = Math.min(3, results.size());
            for (int i = 0; i < nTop; i++) {
                AggResult gr = results.get(i);
                RadioButton rb = resultRadio(group, gr, supervised, rankOf.get(gr) + 1, "");
                list.getChildren().add(rb);
                if (firstRow == null) {
                    firstRow = rb;
                }
                covered.addAll(gr.matchedGt);
                shown.add(gr);
            }

            // --- Diversity: greedily pick settings that catch fibers the shown ones MISS ---
            for (int picks = 0; picks < 6; picks++) {
                AggResult bestPick = null;
                java.util.Set<Integer> bestNew = null;
                for (AggResult gr : results) {
                    if (shown.contains(gr)) {
                        continue;
                    }
                    java.util.Set<Integer> gain = new java.util.LinkedHashSet<>(gr.matchedGt);
                    gain.removeAll(covered);
                    if (bestNew == null || gain.size() > bestNew.size()) {
                        bestNew = gain;
                        bestPick = gr;
                    }
                }
                if (bestPick == null || bestNew.isEmpty()) {
                    break; // nothing new to add
                }
                if (picks == 0) {
                    list.getChildren().add(
                            sectionLabel("Other settings that catch fibers the top picks miss"));
                }
                String extra = "  — adds " + bestNew.size() + ": " + gtLabels(allGt, bestNew, 4);
                list.getChildren().add(
                        resultRadio(group, bestPick, supervised, rankOf.get(bestPick) + 1, extra));
                covered.addAll(bestNew);
                shown.add(bestPick);
            }

            // --- GT fibers never detected by ANY parameter set ---
            java.util.Set<Integer> everMatched = new java.util.LinkedHashSet<>();
            for (AggResult gr : results) {
                everMatched.addAll(gr.matchedGt);
            }
            java.util.List<Integer> never = new ArrayList<>();
            for (int i = 0; i < totalGt; i++) {
                if (!everMatched.contains(i)) {
                    never.add(i);
                }
            }
            neverMsg = never.isEmpty()
                    ? String.format(" Every traced fibre (%d) was detected by at least one setting. ✓",
                            totalGt)
                    : String.format(" Never detected by ANY setting (%d of %d): %s.",
                            never.size(), totalGt, gtLabels(allGt, never, 30));
        } else {
            // Unsupervised / no GT lines: simple ranked top-10 (no coverage data to diversify on).
            int show = Math.min(results.size(), 10);
            for (int i = 0; i < show; i++) {
                RadioButton rb = resultRadio(group, results.get(i), supervised, i + 1, "");
                list.getChildren().add(rb);
                if (firstRow == null) {
                    firstRow = rb;
                }
            }
        }

        String noteText;
        if (supervised && totalGt > 0 && haveCoverage) {
            noteText = "Scored to match your annotated fibre COUNT. Selecting a row loads its "
                    + "parameters here (mask updates live); 'Apply Selected and Preview' traces its fibres. "
                    + "The 'Other settings' below the top picks each catch fibres the top scores "
                    + "miss." + neverMsg;
        } else if (supervised) {
            noteText = "Scored to match your annotated fibre COUNT. Selecting a row loads its "
                    + "parameters here; 'Apply Selected and Preview' traces its fibres. (The per-fibre "
                    + "coverage breakdown needs the updated fiber server.)";
        } else {
            noteText = "No line annotations found — scored by foreground coverage + median "
                    + "straightness. Trace ALL fibers inside the annotation and re-run for a "
                    + "count-matched suggestion.";
        }
        Label note = new Label(noteText);
        note.setWrapText(true);
        note.setStyle("-fx-font-size: 11;");
        note.setPadding(new Insets(8, 10, 0, 10));

        Button preview = new Button("Apply Selected and Preview  ▶");
        preview.setDefaultButton(true);
        preview.setTooltip(ttip("Load the selected row's parameters and trace fibres for "
                + "it on the current preview tile/region, so you can see the result."));
        preview.setOnAction(e -> {
            Object sel = group.getSelectedToggle() == null ? null
                    : group.getSelectedToggle().getUserData();
            if (sel instanceof AggResult) {
                applyOverrides(((AggResult) sel).overrides);
                runPreview();
            }
        });
        Button save = new Button("Save measurements…");
        save.setTooltip(ttip(
                "Append a row (this area's best params + metrics) to a TSV file, so you can tune "
                + "several areas and compare them."));
        save.setOnAction(e -> exportSuggestMeasurements(results, pieces, supervised));
        Button close = new Button("Close");
        close.setOnAction(e -> dlg.close());
        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8, preview, save, close);
        buttons.setPadding(new Insets(10));

        Label header = new Label(String.format(
                "  Parameter sets — evaluated %d distinct set(s) in %d FIRE run(s):",
                suggestEvaluated, suggestTotalRuns));
        header.setStyle("-fx-font-weight: bold;");
        ScrollPane listScroll = new ScrollPane(list);
        // Bind the note's wrap width to the actual list width so it wraps to the dialog instead of
        // computing a single-line preferred width (which runs off the right edge without wrapping).
        note.maxWidthProperty().bind(listScroll.widthProperty().subtract(20));
        VBox root = new VBox(4, header, listScroll, note, buttons);
        dlg.setScene(new Scene(root));
        dlg.show();
        // Select the best (first) row last, so the listener applies it once everything exists.
        if (firstRow != null) {
            firstRow.setSelected(true);
        }
    }

    /** A small bold section heading inside the suggested-parameters list. */
    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 11; -fx-text-fill: #444;");
        l.setPadding(new Insets(6, 0, 0, 0));
        return l;
    }

    /** Build a result RadioButton wired to the toggle group, with an optional trailing annotation. */
    private RadioButton resultRadio(ToggleGroup group, AggResult gr, boolean supervised, int rank,
            String extra) {
        RadioButton rb = new RadioButton(describeResult(rank, gr, supervised) + extra);
        rb.setToggleGroup(group);
        rb.setUserData(gr);
        return rb;
    }

    /** Comma-joined identity labels for a set of global GT indices, truncated to {@code max}. */
    private static String gtLabels(java.util.List<GtInfo> allGt, java.util.Collection<Integer> idx,
            int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Integer i : idx) {
            if (i == null || i < 0 || i >= allGt.size()) {
                continue;
            }
            if (n == max) {
                sb.append(", +").append(idx.size() - max).append(" more");
                break;
            }
            if (n > 0) {
                sb.append(", ");
            }
            sb.append(allGt.get(i).label(i));
            n++;
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private String describeResult(int rank, AggResult gr, boolean supervised) {
        java.util.Map<String, Double> o = gr.overrides;
        String params = String.format(
                "seed=%d, sens=%.2f, bend=%.0f°, link=%.0f°, linkd=%.1f%s, nucl=%.2f, xbox=%.1f%s",
                (int) Math.round(o.getOrDefault("thresh_LMPdist", 0.0)),
                o.getOrDefault("thresh_LMP", 0.0),
                cosToExt(o.getOrDefault("thresh_ext", 1.0)),
                cosToLinka(o.getOrDefault("thresh_linka", -1.0)),
                round1(fromAnalysedPx(o.getOrDefault("thresh_linkd", 0.0),
                        downsampleSpinner.getValue())), unitLabel(),
                o.getOrDefault("thresh_Dxlink", 0.0),
                round1(fromAnalysedPx(o.getOrDefault("s_xlinkbox", 0.0),
                        downsampleSpinner.getValue())), unitLabel());
        String star = gr.inBand ? "★ " : "  ";
        if (supervised) {
            return String.format("%s#%d  score %.2f  |  %d detected (target %d)  |  recall %.0f%%  "
                    + "|  orient err %.1f° — %s",
                    star, rank, gr.score, gr.nDetected, gr.nGt, gr.recall * 100, gr.orientErr, params);
        }
        return String.format("%s#%d  score %.2f  |  coverage %.0f%%  |  straight %.2f  |  %d fibers — %s",
                star, rank, gr.score, gr.coverage * 100, gr.straightness, gr.nDetected, params);
    }

    /** Load a chosen combo's detection-parameter overrides into the dialog spinners. */
    private void applyOverrides(java.util.Map<String, Double> o) {
        if (o.containsKey("thresh_LMPdist")) {
            // The grid searches seed spacing in analysed px; convert back to the spinner's
            // real-world unit using the current downsample as the reference.
            double um = fromAnalysedPx(o.get("thresh_LMPdist"), downsampleSpinner.getValue());
            setSpinnerValue(seedSpacingSpinner, round1(clampD(um, 1.0, 200.0)));
        }
        if (o.containsKey("thresh_LMP")) {
            setSpinnerValue(seedSensSpinner, clampD(o.get("thresh_LMP"), 0.0, 1.0));
        }
        if (o.containsKey("thresh_ext")) {
            setSpinnerValue(bendAngleSpinner, clampD(cosToExt(o.get("thresh_ext")), 5.0, 89.0));
        }
        if (o.containsKey("thresh_linka")) {
            setSpinnerValue(linkAngleSpinner, clampD(cosToLinka(o.get("thresh_linka")), 0.0, 89.0));
        }
        if (o.containsKey("thresh_linkd")) {
            double ld = fromAnalysedPx(o.get("thresh_linkd"), downsampleSpinner.getValue());
            setSpinnerValue(linkDistSpinner, round1(clampD(ld, 0.0, 120.0)));
        }
        if (o.containsKey("thresh_Dxlink")) {
            setSpinnerValue(dxlinkSpinner, round1(clampD(o.get("thresh_Dxlink"), 0.0, 10.0)));
        }
        if (o.containsKey("s_xlinkbox")) {
            double xb = fromAnalysedPx(o.get("s_xlinkbox"), downsampleSpinner.getValue());
            setSpinnerValue(xlinkBoxSpinner, round1(clampD(xb, 0.5, 50.0)));
        }
        updateThresholdFeedback();
        redrawPreview();
        setBusy(false, String.format(
                "Applied: %s. Click Preview to see it.", describeParams(o)));
    }

    /** Detect fibers in one tile; returns them offset to region-analysed coords, tagged with tile. */
    /**
     * Detect fibers in one tile. Returns the server response with its fibers offset to
     * region-analysed coords and tagged with the tile index. Does NOT throw on a
     * server-reported failure (e.g. a too-dense tile that survives the server's
     * auto-coarsening) -- it returns the response with {@code ok=false} so the caller
     * can skip just that tile. Only genuine infrastructure errors throw.
     */
    private FiberSocketClient.AnalyzeResponse detectTile(
            FiberSocketClient client, Tile tile, int tileIdx, int channel, double d,
            String detMeta, String[] backendOut) throws Exception {
        RegionRequest req = RegionRequest.createInstance(
                server.getPath(), d, tile.fullX, tile.fullY, tile.fullW, tile.fullH);
        BufferedImage region = server.readRegion(req);
        if (region == null) {
            throw new RuntimeException("server.readRegion returned null for a tile");
        }
        BufferedImage gray = ChannelExtractor.toGray(region, channel, normLo, serverNormHi());
        // Restrict-to-object: zero pixels outside the run's ROI so FIRE never nucleates outside it.
        if (runRestrictRoi != null) {
            BufferedImage roiMask = MaskRasterizer.rasterise(
                    runRestrictRoi, tile.fullX, tile.fullY, d, gray.getWidth(), gray.getHeight());
            applyMask(gray, roiMask);
        }
        // Exclusion regions for THIS tile ("don't trace here"). Built per tile from the loaded
        // regions; detMeta's has_exclude_mask was set for the whole run, so when exclusion is
        // active every tile sends one (all-black where no region reaches).
        BufferedImage excludeMask = exclusionSection == null ? null
                : exclusionSection.maskForTile(tile.fullX, tile.fullY, d,
                        gray.getWidth(), gray.getHeight(), pixelSizeMicrons);
        FiberSocketClient.AnalyzeResponse resp = client.analyze(gray, detMeta, null, excludeMask);
        if (resp == null || !resp.ok) {
            return resp; // caller decides (skip the tile); null = no response
        }
        if ("synthetic".equals(resp.backend)) {
            backendOut[0] = "synthetic";
        }
        if (resp.fibers != null) {
            List<FiberSocketClient.Fiber> kept = new ArrayList<>();
            for (FiberSocketClient.Fiber f : resp.fibers) {
                if (f.points == null || f.points.length < 2 || f.center == null) {
                    continue;
                }
                offsetFiber(f, tile.ox, tile.oy);
                f.tile = tileIdx;
                kept.add(f);
            }
            resp.fibers = kept;
        }
        return resp;
    }

    /**
     * Notified as each tile of a full run completes, so a caller can paint that
     * tile's fibers into the image live (before the end-of-run stitch/clip cleanup
     * edits them). Called on the analysis background thread with the tile's kept
     * (core-owned) fibers in region-local coords; the callee marshals to the FX
     * thread itself. Only fired for tiles that traced &gt;0 fibers.
     */
    @FunctionalInterface
    private interface TileSink {
        void onTile(int index, int total, List<FiberSocketClient.Fiber> coreFibers, Tile tile);
    }

    /**
     * Detect a region, optionally in TWO passes and merged: pass 1 at the current threshold (thin,
     * faint fibres), pass 2 at the higher "2nd-pass threshold" (carves thick bright bundles so their
     * internal vertical striations become traceable ridges). The union is deduped + stitched once.
     * When two-pass is off this is exactly {@link #analyzeFull} (single pass), so nothing changes.
     * Two-pass runs both passes with TACS OFF (v1); a boundary is ignored.
     */
    private AnalyzeResult analyzeRegion(
            int channel, double d, boolean tacs, ROI boundary, JsonObject extra,
            java.util.function.Consumer<String> status, TileSink tileSink) throws Exception {
        if (twoPassCheck == null || !twoPassCheck.isSelected()) {
            return analyzeFull(channel, d, tacs, boundary, extra, status, tileSink);
        }
        double t1 = thresholdSpinner.getValue();
        double t2 = secondThresholdSpinner.getValue();
        status.accept(String.format("Two-pass 1/2 — thin fibres (threshold %s)…", trimNum(t1)));
        AnalyzeResult a = analyzeFull(channel, d, false, null, extra, status, tileSink);
        thresholdOverride = t2;
        AnalyzeResult b;
        try {
            status.accept(String.format("Two-pass 2/2 — thick bundles (threshold %s)…", trimNum(t2)));
            b = analyzeFull(channel, d, false, null, extra, status, tileSink);
        } finally {
            thresholdOverride = null;
        }
        status.accept(String.format("Merging passes (%d + %d fibres)…",
                a.fibers.size(), b.fibers.size()));
        List<FiberSocketClient.Fiber> union = new ArrayList<>(a.fibers);
        union.addAll(b.fibers);
        List<FiberSocketClient.Fiber> merged = stitchUnion(union, d, status);
        AnalyzeResult r = new AnalyzeResult(merged, a.backend, a.tiles + b.tiles);
        r.failedTiles = a.failedTiles + b.failedTiles;
        r.autoRaisedTiles = a.autoRaisedTiles + b.autoRaisedTiles;
        r.nTraced = a.nTraced + b.nTraced;
        r.nLengthDropped = a.nLengthDropped + b.nLengthDropped;
        r.nWidthDropped = a.nWidthDropped + b.nWidthDropped;
        r.minLengthPx = a.minLengthPx;
        return r;
    }

    /** Dedup + stitch the merged two-pass fibre set (no TACS/mask). */
    private List<FiberSocketClient.Fiber> stitchUnion(
            List<FiberSocketClient.Fiber> union, double d,
            java.util.function.Consumer<String> status) throws Exception {
        if (union.size() < 2) {
            return union;
        }
        FiberSocketClient client = new FiberSocketClient(
                FiberSocketPreferences.getHost(), FiberSocketPreferences.getPort());
        return postprocessAssembled(client, union, d, false, true, null,
                (int) Math.ceil(rw / d), (int) Math.ceil(rh / d), status, "Merging passes");
    }

    /** Full tiled detection + global post-process (dedup/stitch/TACS). */
    private AnalyzeResult analyzeFull(
            int channel, double d, boolean tacs, ROI boundary, JsonObject extra,
            java.util.function.Consumer<String> status) throws Exception {
        return analyzeFull(channel, d, tacs, boundary, extra, status, null);
    }

    /** As {@link #analyzeFull}, optionally streaming each completed tile to {@code tileSink}. */
    private AnalyzeResult analyzeFull(
            int channel, double d, boolean tacs, ROI boundary, JsonObject extra,
            java.util.function.Consumer<String> status, TileSink tileSink) throws Exception {
        ensureExclusionLoaded(status);
        int rwA = (int) Math.ceil(rw / d);
        int rhA = (int) Math.ceil(rh / d);
        List<Tile> tiles = computeTiles(rwA, rhA, d);
        String detMeta = buildMeta(d, false, extra); // detection never sends a mask
        logger.info("Analyze WHOLE region: ch={} downsample={} threshold={}(im2={}) region={}x{}@({},{}) "
                + "-> {} analysed px -> {} tile(s), tacs={}",
                channel, d, thresholdSpinner.getValue(), serverThreshIm2(), rw, rh, rx, ry,
                rwA + "x" + rhA, tiles.size(), tacs);
        FiberSocketClient client = new FiberSocketClient(
                FiberSocketPreferences.getHost(), FiberSocketPreferences.getPort());
        String[] backend = {"real"};
        List<FiberSocketClient.Fiber> all = new ArrayList<>();
        boolean multiTile = tiles.size() > 1;
        int failedTiles = 0;
        int autoRaisedTiles = 0;
        int totalTraced = 0;
        int totalLenDropped = 0;
        int totalWidDropped = 0;
        double minLenPx = 0;
        long tilesStartMs = System.currentTimeMillis();
        for (int t = 0; t < tiles.size(); t++) {
            String eta = "";
            if (t > 0) {
                long per = (System.currentTimeMillis() - tilesStartMs) / t; // avg per finished tile
                eta = " — ~" + humanTime(per * (tiles.size() - t)) + " left";
            }
            status.accept(tiles.size() > 1
                    ? String.format(
                            "Detecting tile %d/%d%s.  Watch the image — fibers appear as each tile finishes.",
                            t + 1, tiles.size(), eta)
                    : "Detecting fibers…");
            Tile tl = tiles.get(t);
            FiberSocketClient.AnalyzeResponse resp;
            try {
                resp = detectTile(client, tl, t, channel, d, detMeta, backend);
            } catch (Exception ex) {
                resp = null;
                logger.warn("  tile {}/{} read/IO error: {}", t + 1, tiles.size(), ex.getMessage());
            }
            // RESILIENCE: a single tile failing (e.g. too dense even after the server
            // auto-coarsens seeds, or an IO error) must NOT abort the whole run. Skip
            // it and keep the fibers from the tiles that succeeded.
            if (resp == null || !resp.ok) {
                failedTiles++;
                logger.warn("  tile {}/{} skipped: {}", t + 1, tiles.size(),
                        resp == null ? "no response" : resp.error);
                continue;
            }
            if (Boolean.TRUE.equals(resp.seedAutoRaised)) {
                autoRaisedTiles++;
            }
            if (resp.nTraced != null) {
                totalTraced += resp.nTraced;
            }
            if (resp.nLengthDropped != null) {
                totalLenDropped += resp.nLengthDropped;
            }
            if (resp.nWidthDropped != null) {
                totalWidDropped += resp.nWidthDropped;
            }
            if (resp.minLengthPx != null) {
                minLenPx = resp.minLengthPx;
            }
            List<FiberSocketClient.Fiber> tileFibers =
                    resp.fibers != null ? resp.fibers : new ArrayList<>();
            // Core ownership (multi-tile only): a fibre lying in the overlap margin is
            // ALSO traced by the neighbouring tile. Keep each fibre exactly once -- by
            // the tile whose CORE contains its centre (the tile that traced it with full
            // overlap context). This removes the seam double-counting that a geometric
            // dedup (~4px) misses when the two tiles trace the same fibre slightly
            // differently. The cores tile the region exactly (disjoint, covering), so a
            // real fibre crossing a seam is split between two cores and rejoined by the
            // POSTPROC stitch. A single tile's core spans the whole region -> keeps all.
            int traced = tileFibers.size();
            int before = all.size();
            for (FiberSocketClient.Fiber f : tileFibers) {
                if (!multiTile || inCore(f, tl)) {
                    all.add(f);
                }
            }
            // Angle spread: a near-zero std means a degenerate tile (e.g. FIRE tracing a
            // hard no-data/black edge), which is NOT real collagen.
            int cnt = all.size() - before;
            double mean = 0;
            for (int i = before; i < all.size(); i++) {
                mean += all.get(i).angle;
            }
            mean = cnt > 0 ? mean / cnt : 0;
            double var = 0;
            for (int i = before; i < all.size(); i++) {
                double dd = all.get(i).angle - mean;
                var += dd * dd;
            }
            double std = cnt > 0 ? Math.sqrt(var / cnt) : 0;
            logger.info("  tile {}/{}: full=({},{}) {}x{} -> {} traced, {} kept(core), angle mean={} std={}{}",
                    t + 1, tiles.size(), tl.fullX, tl.fullY, tl.fullW, tl.fullH, traced, cnt,
                    Math.round(mean), Math.round(std), std < 5 && cnt > 3 ? "  <-- DEGENERATE" : "");
            // Live painting: hand this tile's kept fibers to the caller so they appear in
            // the image now, rather than only after the whole region finishes. A copy is
            // passed because the post-process step below may renumber the shared fibers.
            if (tileSink != null && cnt > 0) {
                tileSink.onTile(t, tiles.size(),
                        new ArrayList<>(all.subList(before, all.size())), tl);
            }
            Runnable tick = batchTileTick;
            if (tick != null) {
                tick.run(); // whole-batch progress/ETA (batch runs only; null otherwise)
            }
        }
        List<FiberSocketClient.Fiber> finalFibers = all;
        boolean needPost = tiles.size() > 1 || tacs;
        if (needPost && !all.isEmpty()) {
            final String stitchBase = tacs ? "Stitching seams + classifying TACS" : "Stitching seams";
            status.accept(stitchBase + "… assembling " + all.size() + " fibres.");
            finalFibers = postprocessAssembled(client, all, d, tacs, tiles.size() > 1, boundary,
                    rwA, rhA, status, stitchBase);
            logger.info("Post-process: {} raw -> {} fibers (stitch={}, tacs={})",
                    all.size(), finalFibers.size(), tiles.size() > 1, tacs);
        } else {
            for (int i = 0; i < all.size(); i++) {
                all.get(i).id = i;
            }
        }
        if (failedTiles > 0) {
            logger.warn("{} of {} tiles were too dense to analyse even after auto-coarsening seeds.",
                    failedTiles, tiles.size());
        }
        AnalyzeResult ar = new AnalyzeResult(finalFibers, backend[0], tiles.size());
        ar.failedTiles = failedTiles;
        ar.autoRaisedTiles = autoRaisedTiles;
        ar.nTraced = totalTraced;
        ar.nLengthDropped = totalLenDropped;
        ar.nWidthDropped = totalWidDropped;
        ar.minLengthPx = minLenPx;
        return ar;
    }

    /**
     * Dedup + seam-stitch (and optionally TACS-classify) the assembled fibre set, in pieces when it
     * is too big for one round-trip — see {@link PostProcessBlocks} for why and how. A set that
     * fits in one chunk takes the single-call path, unchanged.
     *
     * <p>TACS cannot be blocked spatially (it needs the whole boundary mask to measure distance),
     * so that pass chunks the fibre LIST and sends the same full mask with each chunk, stitching
     * off — each fibre is classified exactly once, against global context.</p>
     */
    private List<FiberSocketClient.Fiber> postprocessAssembled(
            FiberSocketClient client, List<FiberSocketClient.Fiber> all, double d, boolean tacs,
            boolean stitch, ROI boundary, int rwA, int rhA,
            java.util.function.Consumer<String> status, String base) throws Exception {
        if (!PostProcessBlocks.needsBlocking(all.size())) {
            BufferedImage mask =
                    tacs ? MaskRasterizer.rasterise(boundary, rx, ry, d, rwA, rhA) : null;
            return postOnce(client, all, d, tacs, stitch, mask, status, base);
        }
        List<FiberSocketClient.Fiber> cur =
                stitch ? stitchBlocked(client, all, d, rwA, rhA, status) : all;
        if (tacs) {
            cur = tacsChunked(client, cur, d, boundary, rwA, rhA, status);
        }
        // Each block/chunk renumbered from 0, so "Fiber ID" would repeat across them.
        for (int i = 0; i < cur.size(); i++) {
            cur.get(i).id = i;
        }
        return cur;
    }

    /**
     * One POSTPROC round-trip: dedup + stitch + optional TACS in a single call. This is one big
     * blocking step with no per-item progress, so an elapsed-time ticker keeps the status moving.
     * Returns the input unchanged if the server declines, so a failed clean-up never costs the
     * traced fibres.
     */
    private List<FiberSocketClient.Fiber> postOnce(
            FiberSocketClient client, List<FiberSocketClient.Fiber> fibers, double d, boolean tacs,
            boolean stitch, BufferedImage mask, java.util.function.Consumer<String> status,
            String base) throws Exception {
        final long start = System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread ticker = new Thread(() -> {
            try {
                while (running.get()) {
                    Thread.sleep(1000);
                    if (!running.get()) {
                        break;
                    }
                    long secs = (System.currentTimeMillis() - start) / 1000;
                    status.accept(String.format(
                            "%s… %s elapsed (one big step — the window isn't frozen; the image "
                            + "refreshes when it finishes).", base, humanTime(secs * 1000)));
                }
            } catch (InterruptedException ignored) {
                // stopped when postprocess returns
            }
        }, "tmequant-stitch-ticker");
        ticker.setDaemon(true);
        ticker.start();
        FiberSocketClient.AnalyzeResponse pr;
        try {
            pr = client.postprocess(buildPostMeta(fibers, d, tacs, stitch), mask);
        } finally {
            running.set(false);
            ticker.interrupt();
        }
        if (pr != null && pr.ok && pr.fibers != null) {
            return pr.fibers;
        }
        logger.warn("Post-process failed: {}", pr == null ? "no response" : pr.error);
        return fibers;
    }

    /** Dedup + stitch a large set in blocks (see {@link PostProcessBlocks}). */
    private List<FiberSocketClient.Fiber> stitchBlocked(
            FiberSocketClient client, List<FiberSocketClient.Fiber> all, double d,
            int rwA, int rhA, java.util.function.Consumer<String> status) throws Exception {
        return PostProcessBlocks.stitch(client, all, rwA, rhA,
                part -> buildPostMeta(part, d, false, true), status);
    }

    /** TACS-classify a large set in list chunks, each sent with the SAME full boundary mask. */
    private List<FiberSocketClient.Fiber> tacsChunked(
            FiberSocketClient client, List<FiberSocketClient.Fiber> fibers, double d, ROI boundary,
            int rwA, int rhA, java.util.function.Consumer<String> status) throws Exception {
        BufferedImage mask = MaskRasterizer.rasterise(boundary, rx, ry, d, rwA, rhA);
        List<FiberSocketClient.Fiber> out = new ArrayList<>(fibers.size());
        int chunks = (int) Math.ceil(fibers.size() / (double) PostProcessBlocks.CHUNK_FIBERS);
        int step = PostProcessBlocks.CHUNK_FIBERS;
        for (int i = 0, c = 1; i < fibers.size(); i += step, c++) {
            List<FiberSocketClient.Fiber> part =
                    fibers.subList(i, Math.min(fibers.size(), i + step));
            status.accept(String.format("Classifying TACS — chunk %d/%d (%d fibres)…",
                    c, chunks, part.size()));
            FiberSocketClient.AnalyzeResponse pr =
                    client.postprocess(buildPostMeta(part, d, true, false), mask);
            if (pr != null && pr.ok && pr.fibers != null) {
                out.addAll(pr.fibers);
            } else {
                logger.warn("TACS chunk {}/{} failed ({}); those fibres left unclassified",
                        c, chunks, pr == null ? "no response" : pr.error);
                out.addAll(part);
            }
        }
        return out;
    }

    /**
     * Make sure any exclusion regions are loaded before a run builds its meta. Runs on the analysis
     * worker, so a large GeoJSON does not block the UI; cached afterwards, so this is a no-op on
     * every later run with the same source.
     */
    private void ensureExclusionLoaded(java.util.function.Consumer<String> status) {
        if (exclusionSection == null || !exclusionSection.isEnabled() || exclusionSection.isActive()) {
            return;
        }
        if (status != null) {
            status.accept("Loading exclusion regions…");
        }
        exclusionSection.ensureLoaded();
    }

    /** Quick preview: detect only the chosen tile (centre by default; no stitch / TACS). */
    private AnalyzeResult analyzeOneTile(
            int channel, double d, JsonObject extra, java.util.function.Consumer<String> status)
            throws Exception {
        ensureExclusionLoaded(status);
        int rwA = (int) Math.ceil(rw / d);
        int rhA = (int) Math.ceil(rh / d);
        List<Tile> tiles = computeTiles(rwA, rhA, d);
        int idx = effectivePreviewTile(tiles.size()); // the tile the user clicked (or centre)
        Tile tl = tiles.get(idx);
        logger.info("Analyze ONE tile (preview): ch={} downsample={} threshold={}(im2={}) "
                + "tile {}/{} full=({},{}) {}x{}",
                channel, d, thresholdSpinner.getValue(), threshIm2(), idx + 1, tiles.size(),
                tl.fullX, tl.fullY, tl.fullW, tl.fullH);
        status.accept("Detecting one preview tile...");
        String detMeta = buildMeta(d, false, extra);
        // Log the exact overrides FIRE receives (incl. any 'Extra params (JSON)' the user added)
        // so it's clear WHICH parameter values are in effect — the server only echoes 3 of them.
        logger.info("Preview FIRE overrides sent: {}", overridesOf(detMeta));
        FiberSocketClient client = new FiberSocketClient(
                FiberSocketPreferences.getHost(), FiberSocketPreferences.getPort());
        String[] backend = {"real"};
        FiberSocketClient.AnalyzeResponse resp =
                detectTile(client, tl, idx, channel, d, detMeta, backend);
        if (resp == null || !resp.ok) {
            // The single preview tile failed (too dense even after auto-coarsening).
            AnalyzeResult ar = new AnalyzeResult(new ArrayList<>(), backend[0], 1);
            ar.failedTiles = 1;
            return ar;
        }
        List<FiberSocketClient.Fiber> out =
                resp.fibers != null ? resp.fibers : new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            out.get(i).id = i;
        }
        AnalyzeResult ar = new AnalyzeResult(out, backend[0], 1);
        if (Boolean.TRUE.equals(resp.seedAutoRaised)) {
            ar.autoRaisedTiles = 1;
        }
        ar.nTraced = resp.nTraced != null ? resp.nTraced : out.size();
        ar.nLengthDropped = resp.nLengthDropped != null ? resp.nLengthDropped : 0;
        ar.nWidthDropped = resp.nWidthDropped != null ? resp.nWidthDropped : 0;
        ar.minLengthPx = resp.minLengthPx != null ? resp.minLengthPx : 0;
        return ar;
    }

    /**
     * DEBUG tool: run FIRE on the currently selected tile with debug_export on, and write every stage
     * we can reach into {@code <project>/test/export_tile#_<datetime>/} — the exact grey FIRE received,
     * its threshold mask, the distance map that drives the tracer's jumps, the fibre overlay, plus
     * params.json and a fibres CSV/JSON. For diagnosing missed fibres and stray jumps on one tile.
     */
    private void runFullTestExport() {
        Project<BufferedImage> proj = qupath.getProject();
        if (proj == null || proj.getPath() == null || proj.getPath().getParent() == null) {
            Dialogs.showErrorMessage(TITLE,
                    "FULL TEST-EXPORT needs an open project (it writes to <project>/test/).");
            return;
        }
        final int channel = chosenChannel();
        final double d = downsampleSpinner.getValue();
        final JsonObject extra;
        try {
            extra = parseExtra();
        } catch (JsonSyntaxException | IllegalStateException ex) {
            Dialogs.showErrorMessage(TITLE, "Extra params is not valid JSON:\n" + ex.getMessage());
            return;
        }
        final java.nio.file.Path projDir = proj.getPath().getParent();
        setBusy(true, "Test-export: running the selected tile…");
        new Thread(() -> {
            try {
                int rwA = (int) Math.ceil(rw / d);
                int rhA = (int) Math.ceil(rh / d);
                List<Tile> tiles = computeTiles(rwA, rhA, d);
                int idx = effectivePreviewTile(tiles.size());
                Tile tl = tiles.get(idx);
                JsonObject m = JsonParser.parseString(buildMeta(d, false, extra)).getAsJsonObject();
                m.addProperty("debug_export", true);
                String detMeta = GSON.toJson(m);
                String stamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                java.nio.file.Path outDir = projDir.resolve("test")
                        .resolve("export_tile" + (idx + 1) + "_" + stamp);
                java.nio.file.Files.createDirectories(outDir);
                java.nio.file.Files.writeString(outDir.resolve("params.json"), prettyJson(detMeta));
                // CLIENT-SIDE inputs written FIRST, so a tile that crashes the server (in-process FIRE
                // segfault on a pathologically dense tile) still leaves the grey + mask for diagnosis.
                try {
                    BufferedImage treg = server.readRegion(RegionRequest.createInstance(
                            server.getPath(), d, tl.fullX, tl.fullY, tl.fullW, tl.fullH));
                    if (treg != null) {
                        BufferedImage tg = ChannelExtractor.toGray(treg, channel, normLo, serverNormHi());
                        javax.imageio.ImageIO.write(tg, "png",
                                outDir.resolve("00_gray_client.png").toFile());
                        BufferedImage mk = buildTileMaskArgb(tg, smoothingSpinner.getValue(),
                                serverThreshIm2());
                        if (mk != null) {
                            javax.imageio.ImageIO.write(mk, "png",
                                    outDir.resolve("00_threshold_mask_client.png").toFile());
                        }
                    }
                } catch (Exception cex) {
                    logger.warn("Test-export: client-side grey/mask save failed: {}", cex.getMessage());
                }
                // Now the server run (this is the step that can crash on a dense tile).
                FiberSocketClient.AnalyzeResponse resp = null;
                String serverNote = "";
                try {
                    FiberSocketClient client = new FiberSocketClient(
                            FiberSocketPreferences.getHost(), FiberSocketPreferences.getPort());
                    String[] backend = {"real"};
                    resp = detectTile(client, tl, idx, channel, d, detMeta, backend);
                    if (resp == null || !resp.ok) {
                        serverNote = "\nSERVER returned no/failed result"
                                + (resp != null && resp.error != null ? ": " + resp.error : "")
                                + " — the client-side 00_* files above are still valid.";
                    }
                } catch (Exception sex) {
                    serverNote = "\nSERVER FAILED/CRASHED on this tile: " + sex.getMessage()
                            + " — the client-side 00_gray_client.png / 00_threshold_mask_client.png "
                            + "above are still valid for diagnosis.";
                    logger.warn("Test-export: server run failed: {}", sex.getMessage());
                }
                FiberSocketClient.DebugExport dbg = resp == null ? null : resp.debug;
                if (dbg != null) {
                    writeB64Png(outDir.resolve("01_gray_asFIRE.png"), dbg.grayPng);
                    writeB64Png(outDir.resolve("02_threshold_mask.png"), dbg.maskPng);
                    writeB64Png(outDir.resolve("03_distance_map.png"), dbg.distPng);
                    writeB64Png(outDir.resolve("04_fibers_overlay.png"), dbg.overlayPng);
                }
                List<FiberSocketClient.Fiber> fibers =
                        (resp != null && resp.fibers != null) ? resp.fibers : new ArrayList<>();
                java.nio.file.Files.writeString(outDir.resolve("05_fibers.json"), GSON.toJson(fibers));
                java.nio.file.Files.writeString(outDir.resolve("05_fibers.csv"), fibersCsv(fibers, d));
                java.nio.file.Files.writeString(outDir.resolve("README.txt"),
                        testExportReadme(resp, tl, idx, d, dbg) + serverNote);
                logger.info("FULL TEST-EXPORT -> {}", outDir);
                final boolean serverFailed = !serverNote.isEmpty();
                Platform.runLater(() -> setBusy(false, "Test-export written to: " + outDir
                        + (serverFailed ? "  (server failed on this tile; client inputs saved)" : "")));
            } catch (Exception ex) {
                logger.error("Test-export failed", ex);
                fail("Test-export failed: " + ex.getMessage());
            }
        }, "tmequant-testexport").start();
    }

    private static void writeB64Png(java.nio.file.Path p, String b64) throws java.io.IOException {
        if (b64 == null || b64.isEmpty()) {
            return;
        }
        java.nio.file.Files.write(p, java.util.Base64.getDecoder().decode(b64));
    }

    private static String prettyJson(String json) {
        try {
            return new GsonBuilder().serializeSpecialFloatingPointValues().setPrettyPrinting()
                    .create().toJson(JsonParser.parseString(json));
        } catch (RuntimeException ex) {
            return json;
        }
    }

    private static String fmtNum(double v) {
        return Double.isNaN(v) ? "" : String.format(java.util.Locale.US, "%.3f", v);
    }

    private String fibersCsv(List<FiberSocketClient.Fiber> fibers, double d) {
        boolean cal = calibrated && pixelSizeMicrons > 0;
        double toUnit = cal ? d * pixelSizeMicrons : d; // analysed px -> µm (or full-res px)
        String u = cal ? "um" : "px";
        StringBuilder sb = new StringBuilder();
        sb.append("id,length_arc_").append(u).append(",length_end_").append(u)
          .append(",width_mean_").append(u).append(",width_max_").append(u)
          .append(",width_sd_").append(u).append(",angle_deg,straightness,n_points")
          .append(",kink_max_deg,kink_count,crimp_amp_").append(u)
          .append(",crimp_period_").append(u).append(",curvature_deg_per_").append(u).append('\n');
        for (FiberSocketClient.Fiber f : fibers) {
            sb.append(f.id).append(',')
              .append(fmtNum(f.lengthArc * toUnit)).append(',')
              .append(fmtNum(f.lengthEnd * toUnit)).append(',')
              .append(fmtNum(f.width * toUnit)).append(',')
              .append(fmtNum(f.widthMax * toUnit)).append(',')
              .append(fmtNum(f.widthStd * toUnit)).append(',')
              .append(fmtNum(f.angle)).append(',')
              .append(fmtNum(f.straightness)).append(',')
              .append(f.points == null ? 0 : f.points.length).append(',')
              .append(fmtNum(f.kinkMaxDeg)).append(',')
              .append(fmtNum(f.kinkCount)).append(',')
              .append(fmtNum(f.crimpAmp * toUnit)).append(',')
              .append(fmtNum(f.crimpPeriod * toUnit)).append(',')
              .append(fmtNum(toUnit > 0 ? f.curvature / toUnit : Double.NaN)).append('\n');
        }
        return sb.toString();
    }

    private String testExportReadme(FiberSocketClient.AnalyzeResponse resp, Tile tl, int idx,
                                    double d, FiberSocketClient.DebugExport dbg) {
        StringBuilder sb = new StringBuilder();
        sb.append("TME-Quant FULL TEST-EXPORT\nGenerated: ")
          .append(java.time.LocalDateTime.now()).append("\n\n");
        sb.append("Tile ").append(idx + 1).append("  full-res region x=").append(tl.fullX)
          .append(" y=").append(tl.fullY).append(" w=").append(tl.fullW)
          .append(" h=").append(tl.fullH).append("\n");
        sb.append("Downsample: ").append(trimNum(d))
          .append("   Threshold (native): ").append(trimNum(thresholdSpinner.getValue()))
          .append("   Ridge: ").append(ridgeCheck != null && ridgeCheck.isSelected())
          .append("   Two-pass: ").append(twoPassCheck != null && twoPassCheck.isSelected()).append("\n");
        if (dbg != null) {
            sb.append("Server threshold used (0-255): ").append(dbg.thresh)
              .append("   Foreground fraction: ").append(dbg.foregroundFrac)
              .append("   Distance-map max: ").append(dbg.distMax).append("\n");
        }
        if (resp != null) {
            sb.append("FIRE traced: ").append(resp.nTraced)
              .append("   Min-length dropped: ").append(resp.nLengthDropped)
              .append("   Max-width dropped: ").append(resp.nWidthDropped).append("\n");
            if (Boolean.TRUE.equals(resp.seedAutoRaised)) {
                sb.append("NOTE: this tile was auto-coarsened (it was dense).\n");
            }
            if (!resp.ok) {
                sb.append("TILE FAILED: ").append(resp.error).append("\n");
            }
        }
        sb.append("\nFiles:\n")
          .append("  00_gray_client.png    - the grey tile the CLIENT sent (saved BEFORE FIRE runs, so it\n")
          .append("                          survives even if the server crashes on this tile)\n")
          .append("  00_threshold_mask_client.png - client-side threshold mask of that grey\n")
          .append("  01_gray_asFIRE.png    - the EXACT grey image FIRE received (prescale, + ridge if on)\n")
          .append("  02_threshold_mask.png - foreground FIRE keeps (grey > server threshold). A fibre MISSING here is below threshold.\n")
          .append("  03_distance_map.png   - cityblock distance to background; the tracer walks THIS and its jump/search radius equals the value here (bright = long jumps possible)\n")
          .append("  04_fibers_overlay.png - traced fibres (green) on the grey\n")
          .append("  05_fibers.csv/.json   - per-fibre geometry + width stats\n")
          .append("  params.json           - the exact ANALYZE meta sent to FIRE\n");
        return sb.toString();
    }

    private String buildPostMeta(
            List<FiberSocketClient.Fiber> fibers, double d, boolean tacs, boolean stitch) {
        JsonObject m = new JsonObject();
        m.add("fibers", GSON.toJsonTree(fibers));
        m.addProperty("stitch", stitch);
        m.addProperty("has_mask", tacs);
        m.addProperty("distance_threshold", toAnalysedPx(tacsZoneSpinner.getValue(), d));
        // Log the (heavy) stitch step into the same run transcript as the tiles.
        if (runServerLogDir != null && runServerLogFile != null) {
            m.addProperty("log_dir", runServerLogDir);
            m.addProperty("log_file", runServerLogFile);
        }
        return GSON.toJson(m);
    }

    private String settingsSignature() {
        return String.join("|",
                String.valueOf(chosenChannel()),
                String.valueOf(downsampleSpinner.getValue()),
                String.valueOf(thresholdSpinner.getValue()),
                String.valueOf(twoPassCheck != null && twoPassCheck.isSelected()),
                String.valueOf(secondThresholdSpinner != null ? secondThresholdSpinner.getValue() : 0),
                String.valueOf(ridgeCheck != null && ridgeCheck.isSelected()),
                String.valueOf(minLenSpinner.getValue()),
                String.valueOf(seedSpacingSpinner.getValue()),
                String.valueOf(fiberMode()),
                String.valueOf(tacsCheck.isSelected()),
                String.valueOf(boundaryBox.getSelectionModel().getSelectedIndex()),
                String.valueOf(tacsZoneSpinner.getValue()),
                String.valueOf(smoothingSpinner.getValue()),
                String.valueOf(distSmoothSpinner.getValue()),
                String.valueOf(seedSensSpinner.getValue()),
                String.valueOf(bendAngleSpinner.getValue()),
                String.valueOf(linkDistSpinner.getValue()),
                String.valueOf(linkAngleSpinner.getValue()),
                String.valueOf(maxWidthSpinner.getValue()),
                extraParamsArea.getText() == null ? "" : extraParamsArea.getText(),
                String.valueOf(maskToObjectCheck != null && maskToObjectCheck.isSelected()),
                restrictIdentity());
    }

    /** The ROI the current interactive run should be restricted to, or null (no restriction). */
    private ROI interactiveRestrictRoi() {
        if (maskToObjectCheck == null || !maskToObjectCheck.isSelected()) {
            return null;
        }
        return (selectedRoi != null && selectedRoi.isArea()) ? selectedRoi : null;
    }

    /** Short identity of the current mask/clip restriction, for the preview-reuse signature. */
    private String restrictIdentity() {
        ROI r = interactiveRestrictRoi();
        if (r == null) {
            return "none";
        }
        return r.getBoundsX() + "," + r.getBoundsY() + "," + r.getBoundsWidth() + ","
                + r.getBoundsHeight() + "," + r.getNumPoints();
    }

    private static final class AnalyzeResult {
        final List<FiberSocketClient.Fiber> fibers;
        final String backend;
        final int tiles;
        // Diagnostics aggregated across tiles, for explaining a low/zero count.
        int failedTiles = 0;       // tiles too dense even after auto-coarsening
        int autoRaisedTiles = 0;   // tiles recovered by auto-raising seed spacing
        int nTraced = 0;           // fibers FIRE traced before the Min-length filter
        int nLengthDropped = 0;    // removed by Min fiber length
        int nWidthDropped = 0;     // removed by Max fiber width
        double minLengthPx = 0;    // the Min-length value applied (analysed px)

        AnalyzeResult(List<FiberSocketClient.Fiber> fibers, String backend, int tiles) {
            this.fibers = fibers;
            this.backend = backend;
            this.tiles = tiles;
        }
    }

    /** Split the region (in analysed pixels) into overlapping tiles. */
    private List<Tile> computeTiles(int rwA, int rhA, double d) {
        int nCols = Math.max(1, (int) Math.ceil((double) rwA / TILE_CORE));
        int nRows = Math.max(1, (int) Math.ceil((double) rhA / TILE_CORE));
        // Keep the tile COUNT under MAX_TILES by growing tiles -- but never past the effective per-
        // side cap (so a huge region gets more tiles instead of a few giant ones). minCols/minRows
        // are the fewest columns/rows that keep every tile <= that cap.
        int maxTileDim = effectiveMaxTileDim(d);
        int minCols = Math.max(1, (int) Math.ceil((double) rwA / maxTileDim));
        int minRows = Math.max(1, (int) Math.ceil((double) rhA / maxTileDim));
        while ((long) nCols * nRows > MAX_TILES) {
            if (nCols >= nRows && nCols > minCols) {
                nCols--;
            } else if (nRows > minRows) {
                nRows--;
            } else if (nCols > minCols) {
                nCols--;
            } else {
                break; // can't shrink the grid further without exceeding MAX_TILE_DIM
            }
        }
        int coreW = (int) Math.ceil((double) rwA / nCols);
        int coreH = (int) Math.ceil((double) rhA / nRows);
        List<Tile> tiles = new ArrayList<>();
        for (int cy = 0; cy < nRows; cy++) {
            for (int cx = 0; cx < nCols; cx++) {
                int coreX0 = cx * coreW;
                int coreX1 = Math.min(rwA, (cx + 1) * coreW);
                int coreY0 = cy * coreH;
                int coreY1 = Math.min(rhA, (cy + 1) * coreH);
                if (coreX0 >= coreX1 || coreY0 >= coreY1) {
                    continue;
                }
                int readX0 = Math.max(0, coreX0 - TILE_OVERLAP);
                int readX1 = Math.min(rwA, coreX1 + TILE_OVERLAP);
                int readY0 = Math.max(0, coreY0 - TILE_OVERLAP);
                int readY1 = Math.min(rhA, coreY1 + TILE_OVERLAP);
                int fullX = rx + (int) Math.round(readX0 * d);
                int fullY = ry + (int) Math.round(readY0 * d);
                int fullW = Math.min(server.getWidth() - fullX, (int) Math.round((readX1 - readX0) * d));
                int fullH = Math.min(server.getHeight() - fullY, (int) Math.round((readY1 - readY0) * d));
                if (fullW <= 0 || fullH <= 0) {
                    continue;
                }
                tiles.add(new Tile(coreX0, coreX1, coreY0, coreY1, readX0, readY0, fullX, fullY, fullW, fullH));
            }
        }
        return tiles;
    }

    /**
     * True if the fibre's centre falls in this tile's CORE region (region-analysed
     * coords, half-open bounds so the disjoint cores partition the region exactly).
     * Used for multi-tile overlap ownership.
     */
    private static boolean inCore(FiberSocketClient.Fiber f, Tile tl) {
        if (f.center == null || f.center.length < 2) {
            return false;
        }
        double cx = f.center[0];
        double cy = f.center[1];
        return cx >= tl.coreX0 && cx < tl.coreX1 && cy >= tl.coreY0 && cy < tl.coreY1;
    }

    private static void offsetFiber(FiberSocketClient.Fiber f, double ox, double oy) {
        for (double[] p : f.points) {
            if (p != null && p.length >= 2) {
                p[0] += ox;
                p[1] += oy;
            }
        }
        if (f.center != null && f.center.length >= 2) {
            f.center[0] += ox;
            f.center[1] += oy;
        }
    }

    /** One tile: core bounds + read region (full-image px) + analysed-px offset. */
    private static final class Tile {
        final int coreX0;
        final int coreX1;
        final int coreY0;
        final int coreY1;
        final double ox;
        final double oy;
        final int fullX;
        final int fullY;
        final int fullW;
        final int fullH;

        Tile(int coreX0, int coreX1, int coreY0, int coreY1, int readX0, int readY0,
                int fullX, int fullY, int fullW, int fullH) {
            this.coreX0 = coreX0;
            this.coreX1 = coreX1;
            this.coreY0 = coreY0;
            this.coreY1 = coreY1;
            this.ox = readX0;
            this.oy = readY0;
            this.fullX = fullX;
            this.fullY = fullY;
            this.fullW = fullW;
            this.fullH = fullH;
        }
    }

    /**
     * Read the shared preview background (the globally-normalised gray FIRE sees) at
     * the current channel + downsample, on a background thread, then redraw both
     * canvases. A generation counter discards stale reads if settings change again.
     */
    private void refreshBackground() {
        if (maskCanvas == null) {
            return; // not built yet
        }
        refreshTileSampleAsync(); // update the per-tile threshold % alongside the region %
        final int channel = chosenChannel();
        final double downsample = downsampleSpinner.getValue();
        final boolean fits = regionFitsFaithfulPreview(downsample);
        final ROI maskRoi = interactiveRestrictRoi();
        final int gen = ++bgGen;
        new Thread(() -> {
            try {
                int rwA = (int) Math.ceil(rw / downsample);
                int rhA = (int) Math.ceil(rh / downsample);
                // FAITHFUL: when the region fits, read it at the SELECTED downsample so the threshold
                // mask is exactly what FIRE sees. Otherwise read a coarse thumbnail purely for context
                // + tile picking (the whole-region mask is suppressed in redrawMask -- it would lie).
                double bgDown;
                if (fits) {
                    bgDown = downsample;
                } else {
                    int bgFactor = Math.max(1, (int) Math.ceil(Math.max(rwA, rhA) / 1600.0));
                    bgDown = downsample * bgFactor;
                }
                BufferedImage bgRegion = server.readRegion(
                        RegionRequest.createInstance(server.getPath(), bgDown, rx, ry, rw, rh));
                BufferedImage bgGray = ChannelExtractor.toGray(bgRegion, channel, normLo, normHi);
                if (maskRoi != null) {
                    applyMask(bgGray, MaskRasterizer.rasterise(
                            maskRoi, rx, ry, bgDown, bgGray.getWidth(), bgGray.getHeight()));
                }
                var fxImg = ChannelExtractor.toFxImage(bgGray);
                int gw = bgGray.getWidth();
                int gh = bgGray.getHeight();
                double bgRatio = downsample / bgDown; // 1.0 when faithful
                Platform.runLater(() -> {
                    if (gen != bgGen) {
                        return; // a newer read superseded this one
                    }
                    lastBgGray = bgGray;
                    lastBgFx = fxImg;
                    lastBgW = gw;
                    lastBgH = gh;
                    lastBgRatio = bgRatio;
                    previewTooLarge = !fits;
                    maskTileCleared = false; // a fresh read shows the tile mask again
                    // Channel/downsample changed -> any cached picked-tile mask is stale.
                    pickedTileGray = null;
                    pickedTileGrayIdx = -1;
                    pickedTileOverlay = null;
                    refreshClearOverlayUi();
                    // The channel/downsample changed, so any previously-detected
                    // fibers are stale (wrong analysed grid). Drop them; the fiber
                    // canvas shows just the image until the next Preview.
                    lastFibers = null;
                    redrawMask();
                    redrawFibers();
                });
            } catch (Exception ex) { // noqa
                logger.warn("Background read for preview failed: {}", ex.getMessage());
            }
        }, "tmequant-bg").start();
    }

    /** Canvas display scale for a gw x gh background. */
    private static double previewScale(int gw, int gh) {
        return Math.min(Math.min((double) PREVIEW_MAX / gw, (double) PREVIEW_MAX / gh), 4.0);
    }

    private static double clampD(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Wire scroll-to-zoom, click-drag-to-pan and double-click-to-pick-tile onto a preview
     * canvas. The two previews share {@link #previewZoom} and scroll position, so zooming or
     * panning either one moves both — letting you compare the same spot in the mask and the
     * fiber overlay.
     */
    private void wirePreviewMouse(Canvas cv, ScrollPane sp) {
        cv.setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.2 : 1 / 1.2;
            applyZoomAt(sp, e.getX(), e.getY(), factor);
            e.consume();
        });
        cv.setOnMousePressed(e -> {
            dragStartSceneX = e.getSceneX();
            dragStartSceneY = e.getSceneY();
            dragStartH = sp.getHvalue();
            dragStartV = sp.getVvalue();
            previewDragged = false;
        });
        cv.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - dragStartSceneX;
            double dy = e.getSceneY() - dragStartSceneY;
            if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                previewDragged = true;
            }
            var vp = sp.getViewportBounds();
            double rangeX = cv.getWidth() - vp.getWidth();
            double rangeY = cv.getHeight() - vp.getHeight();
            double h = rangeX > 0 ? dragStartH - dx / rangeX : dragStartH;
            double v = rangeY > 0 ? dragStartV - dy / rangeY : dragStartV;
            syncScroll(clampD(h, 0, 1), clampD(v, 0, 1));
        });
        // Double-click (not a drag) picks the tile under the cursor for "One tile (fast)".
        cv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !previewDragged) {
                pickPreviewTile(e.getX(), e.getY());
            }
        });
    }

    /** Apply both previews' scroll position together so they stay aligned. */
    private void syncScroll(double h, double v) {
        if (maskScroll != null) {
            maskScroll.setHvalue(h);
            maskScroll.setVvalue(v);
        }
        if (canvasScroll != null) {
            canvasScroll.setHvalue(h);
            canvasScroll.setVvalue(v);
        }
    }

    /**
     * Scroll both previews so the given point (in canvas content px — both canvases share the same
     * dimensions) sits at the centre of the viewport. Used to bring the picked tile into view on
     * the threshold mask (and keep the fibre preview aligned) when a tile is selected.
     */
    private void centerPreviewsOn(double contentX, double contentY) {
        if (maskScroll == null || maskCanvas == null) {
            return;
        }
        var vp = maskScroll.getViewportBounds();
        double rangeX = maskCanvas.getWidth() - vp.getWidth();
        double rangeY = maskCanvas.getHeight() - vp.getHeight();
        double h = rangeX > 0 ? (contentX - vp.getWidth() / 2) / rangeX : 0;
        double v = rangeY > 0 ? (contentY - vp.getHeight() / 2) / rangeY : 0;
        syncScroll(clampD(h, 0, 1), clampD(v, 0, 1));
    }

    /**
     * Zoom both previews about a point (in the source canvas's content coordinates), keeping
     * that point under the cursor. Clamped to 1×–8×.
     */
    private void applyZoomAt(ScrollPane sp, double mouseContentX, double mouseContentY, double factor) {
        double oldZoom = previewZoom;
        double newZoom = clampD(oldZoom * factor, 1.0, 8.0);
        if (newZoom == oldZoom) {
            return;
        }
        var vp = sp.getViewportBounds();
        double vpW = vp.getWidth();
        double vpH = vp.getHeight();
        double oldW = Math.max(1, sp.getContent().getBoundsInLocal().getWidth());
        double oldH = Math.max(1, sp.getContent().getBoundsInLocal().getHeight());
        // Where the cursor sits inside the viewport (content px under cursor minus scroll offset).
        double offX = mouseContentX - sp.getHvalue() * Math.max(0, oldW - vpW);
        double offY = mouseContentY - sp.getVvalue() * Math.max(0, oldH - vpH);
        double r = newZoom / oldZoom;
        previewZoom = newZoom;
        redrawMask();
        redrawFibers(); // both canvases resize to the new zoom
        double newW = oldW * r;
        double newH = oldH * r;
        double newContentX = mouseContentX * r;
        double newContentY = mouseContentY * r;
        double h = (newW - vpW) > 0 ? (newContentX - offX) / (newW - vpW) : 0;
        double v = (newH - vpH) > 0 ? (newContentY - offY) / (newH - vpH) : 0;
        syncScroll(clampD(h, 0, 1), clampD(v, 0, 1));
    }

    /** Stage ① — draw the background + live red threshold mask (smoothing applied). */
    private void redrawMask() {
        if (lastBgFx == null || maskCanvas == null) {
            return;
        }
        int gw = lastBgW;
        int gh = lastBgH;
        double scale = previewScale(gw, gh) * previewZoom;
        double cw = gw * scale;
        double ch = gh * scale;
        maskCanvas.setWidth(cw);
        maskCanvas.setHeight(ch);
        GraphicsContext gc = maskCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, cw, ch);
        gc.drawImage(lastBgFx, 0, 0, cw, ch);
        if (!previewTooLarge) {
            // Faithful: lastBgGray is at the selected downsample, so this is exactly FIRE's foreground.
            var ov = buildThresholdOverlay();
            if (ov != null) {
                gc.drawImage(ov, 0, 0, cw, ch);
            }
        } else {
            // The whole-region mask here would be thresholding the coarse thumbnail (a lie), so it is
            // suppressed. Show the FAITHFUL mask for one tile only, read at the selected downsample.
            drawPickedTileMask(gc, scale);
        }
        // Tile grid on the mask canvas too, so the tiling is visible in the threshold preview.
        drawTileGrid(gc, scale);
    }

    /**
     * Too-large mode: draw the faithful red threshold mask for the previewed tile (read at the
     * selected downsample) at that tile's position on the coarse thumbnail. Nothing is drawn once
     * "Clear overlay" has cleared it.
     */
    private void drawPickedTileMask(GraphicsContext gc, double scale) {
        if (maskTileCleared) {
            return;
        }
        double d = downsampleSpinner.getValue();
        int rwA = (int) Math.ceil(rw / d);
        int rhA = (int) Math.ceil(rh / d);
        List<Tile> tiles = computeTiles(rwA, rhA, d);
        if (tiles.isEmpty()) {
            return;
        }
        int idx = effectivePreviewTile(tiles.size());
        Tile t = tiles.get(idx);
        if (pickedTileGray == null || pickedTileGrayIdx != idx) {
            // Read on a background thread (a tile can be a few MP); redraw when it arrives. Never
            // block the FX thread on a server read -- that froze the UI and left the canvas blank.
            loadPickedTileMaskAsync(idx, t, d);
            return; // nothing to draw yet; the async read will trigger another redraw
        }
        // Rebuild the overlay only when the tile, threshold, or smoothing changed (the per-pixel
        // build is costly at a few MP, and redrawMask runs on every zoom/pan).
        int thr = threshIm2();
        double sig = smoothingSpinner.getValue();
        if (pickedTileOverlay == null || pickedTileOverlayThr != thr || pickedTileOverlaySigma != sig) {
            pickedTileOverlay = buildOverlayFor(pickedTileGray, 1.0); // gray is AT the selected ds
            pickedTileOverlayThr = thr;
            pickedTileOverlaySigma = sig;
        }
        var ov = pickedTileOverlay;
        if (ov == null) {
            return;
        }
        double s = scale * lastBgRatio;
        // pickedTileGray spans the tile's READ extent (core + overlap); draw only its CORE sub-rect,
        // positioned at the core's place on the thumbnail.
        int srcX = (int) Math.round(t.coreX0 - t.ox);
        int srcY = (int) Math.round(t.coreY0 - t.oy);
        int srcW = t.coreX1 - t.coreX0;
        int srcH = t.coreY1 - t.coreY0;
        double dx = t.coreX0 * s;
        double dy = t.coreY0 * s;
        double dw = (t.coreX1 - t.coreX0) * s;
        double dh = (t.coreY1 - t.coreY0) * s;
        gc.drawImage(ov, srcX, srcY, srcW, srcH, dx, dy, dw, dh);
    }

    /** Read one preview tile at the selected downsample on a background thread, then redraw. */
    private void loadPickedTileMaskAsync(int idx, Tile t, double d) {
        if (maskTileLoading && pickedTileGrayIdx == idx) {
            return; // this tile is already being read
        }
        final int gen = ++maskTileLoadGen;
        maskTileLoading = true;
        pickedTileGrayIdx = idx; // mark intent so repeated redraws don't re-launch
        final int ch = chosenChannel();
        final double lo = normLo;
        final double hi = normHi;
        final int fx = t.fullX;
        final int fy = t.fullY;
        final int fw = t.fullW;
        final int fh = t.fullH;
        new Thread(() -> {
            BufferedImage g = null;
            try {
                BufferedImage reg = server.readRegion(
                        RegionRequest.createInstance(server.getPath(), d, fx, fy, fw, fh));
                g = ChannelExtractor.toGray(reg, ch, lo, hi);
            } catch (Exception ex) {
                logger.warn("Preview tile read failed: {}", ex.getMessage());
            }
            final BufferedImage gray = g;
            Platform.runLater(() -> {
                maskTileLoading = false;
                if (gen != maskTileLoadGen) {
                    return; // a newer pick/read superseded this one
                }
                pickedTileGray = gray;
                pickedTileGrayIdx = gray != null ? idx : -1;
                pickedTileOverlay = null; // force a rebuild for the new tile
                redrawMask();
            });
        }, "tmequant-preview-tile").start();
    }

    /**
     * Overlay the tile grid on the fiber preview, and highlight the tile that "One tile
     * (fast)" will preview. Tile cores are in region-analysed px; the same {@code scale *
     * lastBgRatio} mapping used for fibers takes them to canvas px.
     */
    private void drawTileGrid(GraphicsContext gc, double scale) {
        double d = downsampleSpinner.getValue();
        int rwA = (int) Math.ceil(rw / d);
        int rhA = (int) Math.ceil(rh / d);
        List<Tile> tiles = computeTiles(rwA, rhA, d);
        if (tiles.size() <= 1) {
            if (maskTileLabel != null) {
                maskTileLabel.setText("Whole region fits in one tile.");
            }
            return;
        }
        if (previewTooLarge && maskTileLabel != null) {
            maskTileLabel.setText(String.format(
                    "⚠ Region too large to threshold whole at downsample %s (a whole-region mask "
                    + "would be computed on a downsampled image and mislead you). Showing the "
                    + "faithful threshold mask for ONE tile — double-click any tile to move it.",
                    trimNum(d)));
        }
        double s = scale * lastBgRatio;
        boolean oneTile = previewScopeBox.getSelectionModel().getSelectedIndex() == 0;
        int sel = effectivePreviewTile(tiles.size()); // the picked tile (or centre)
        double cw = lastBgW * scale;
        double ch = lastBgH * scale;
        // Draw the tiling as faint boundary LINES (not per-cell rectangles), SUBSAMPLED so
        // adjacent drawn lines stay a few px apart. This keeps the grid visible even for a huge
        // tile count without becoming a solid "wall" on a small thumbnail.
        final double minGapPx = 9.0;
        java.util.TreeSet<Long> xs = new java.util.TreeSet<>();
        java.util.TreeSet<Long> ys = new java.util.TreeSet<>();
        for (Tile t : tiles) {
            xs.add(Math.round(t.coreX0 * s));
            xs.add(Math.round(t.coreX1 * s));
            ys.add(Math.round(t.coreY0 * s));
            ys.add(Math.round(t.coreY1 * s));
        }
        gc.setLineWidth(1.0);
        gc.setStroke(Color.color(0.3, 0.6, 1.0, 0.55));
        // Null sentinel (not Long.MIN_VALUE — xv - Long.MIN_VALUE overflows and drops every line).
        Long last = null;
        for (long xv : xs) {
            if (last == null || xv - last >= minGapPx) {
                gc.strokeLine(xv, 0, xv, ch);
                last = xv;
            }
        }
        last = null;
        for (long yv : ys) {
            if (last == null || yv - last >= minGapPx) {
                gc.strokeLine(0, yv, cw, yv);
                last = yv;
            }
        }
        // Selected tile highlight on top of the grid.
        Tile selTile = tiles.get(sel);
        double x = selTile.coreX0 * s;
        double y = selTile.coreY0 * s;
        double w = (selTile.coreX1 - selTile.coreX0) * s;
        double h = (selTile.coreY1 - selTile.coreY0) * s;
        gc.setStroke(Color.YELLOW);
        gc.setLineWidth(2.0);
        gc.strokeRect(x, y, w, h);
        gc.setFill(Color.color(1, 1, 0, 0.12));
        gc.fillRect(x, y, w, h);
        gc.setLineWidth(1.0);
        if (maskTileLabel != null && !previewTooLarge) {
            maskTileLabel.setText(String.format(
                    "%d tiles — double-click to pick a tile (tile %d selected). %s 'Export tile' saves it.",
                    tiles.size(), sel + 1,
                    oneTile ? "'Preview fibers' analyses it;" : "'Whole region' previews all;"));
        }
    }

    /** Largest tile side (analysed px), from the Preferences value, clamped to a sane range. */
    private int maxTileDim() {
        double v = FiberSocketPreferences.maxTileDimProperty().get();
        return (int) Math.max(256, Math.min(16384, Math.round(v)));
    }

    /**
     * The effective per-side tile cap (analysed px) at downsample {@code d}. The Preferences value is
     * treated as a PHYSICAL (full-res px) cap: a tile of N analysed px covers N·d full-res px, so at
     * higher downsample we shrink the analysed side to keep the physical footprint — and therefore
     * the fibre count handed to FIRE's linking step — roughly constant. Without this, raising the
     * downsample enlarges every tile (2× downsample = 4× the tissue per tile), which is what pushed
     * moderate tiles past FIRE's fiberproc memory limit (0 fibers). Floored so tiles never get
     * uselessly tiny at extreme downsample.
     */
    private int effectiveMaxTileDim(double d) {
        int pref = maxTileDim();
        if (d <= 1.0) {
            return pref;
        }
        return Math.max(256, Math.min(pref, (int) Math.ceil(pref / d)));
    }

    /** Refresh the read-only "Tile size" display: the largest tile edge at the current downsample. */
    private void updateTileSizeLabel() {
        if (tileSizeLabel == null) {
            return;
        }
        double d = downsampleSpinner != null ? downsampleSpinner.getValue() : 1.0;
        long tilePx = Math.round(effectiveMaxTileDim(d) * d); // full-res px per tile side (the cap)
        String txt = (calibrated && pixelSizeMicrons > 0)
                ? String.format("up to %,.0f µm/side", tilePx * pixelSizeMicrons)
                : String.format("up to %,d px/side", tilePx);
        tileSizeLabel.setText(txt);
    }

    /** True if the region at downsample {@code d} is small enough to read + threshold whole (faithfully). */
    private boolean regionFitsFaithfulPreview(double d) {
        long rwA = (long) Math.ceil(rw / d);
        long rhA = (long) Math.ceil(rh / d);
        return rwA * rhA <= PREVIEW_FAITHFUL_MAX_PX;
    }

    /** Resolve the preview tile index, clamped to the current tile count (-1 = centre). */
    private int effectivePreviewTile(int nTiles) {
        if (previewTileIdx >= 0 && previewTileIdx < nTiles) {
            return previewTileIdx;
        }
        return nTiles / 2;
    }

    /** Click on the mask -> select the tile under the cursor as the preview tile. */
    private void pickPreviewTile(double canvasX, double canvasY) {
        if (lastBgFx == null) {
            return;
        }
        double scale = previewScale(lastBgW, lastBgH) * previewZoom;
        double s = scale * lastBgRatio;
        if (s <= 0) {
            return;
        }
        double ax = canvasX / s;
        double ay = canvasY / s;
        double d = downsampleSpinner.getValue();
        int rwA = (int) Math.ceil(rw / d);
        int rhA = (int) Math.ceil(rh / d);
        List<Tile> tiles = computeTiles(rwA, rhA, d);
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            if (ax >= t.coreX0 && ax < t.coreX1 && ay >= t.coreY0 && ay < t.coreY1) {
                previewTileIdx = i;
                maskTileCleared = false; // picking a tile shows its faithful mask again
                redrawMask();
                redrawFibers();
                refreshClearOverlayUi();
                refreshTileSampleAsync(); // update the per-tile threshold % for the newly picked tile
                // Recenter the threshold mask (and the linked fibre preview) on the picked tile.
                double tileCenterX = (t.coreX0 + t.coreX1) / 2.0 * s;
                double tileCenterY = (t.coreY0 + t.coreY1) / 2.0 * s;
                centerPreviewsOn(tileCenterX, tileCenterY);
                return;
            }
        }
    }

    /**
     * Save the selected tile's EXACT pixels (the prescaled gray image FIRE analyses)
     * as a PNG, with the tile's x,y position in the filename. Useful for reproducing a
     * crash or sharing a problem region.
     */
    private void exportTile() {
        try {
            double d = downsampleSpinner.getValue();
            int channel = chosenChannel();
            int rwA = (int) Math.ceil(rw / d);
            int rhA = (int) Math.ceil(rh / d);
            List<Tile> tiles = computeTiles(rwA, rhA, d);
            Tile tl = tiles.get(effectivePreviewTile(tiles.size()));
            // Exactly what the server receives for this tile: read at the analysis
            // downsample, one channel, scaled 0-255 with the global reference.
            RegionRequest req = RegionRequest.createInstance(
                    server.getPath(), d, tl.fullX, tl.fullY, tl.fullW, tl.fullH);
            BufferedImage region = server.readRegion(req);
            if (region == null) {
                Dialogs.showErrorMessage(TITLE, "Could not read the tile pixels.");
                return;
            }
            BufferedImage gray = ChannelExtractor.toGray(region, channel, normLo, serverNormHi());

            // Filename carries the full-image x,y of the tile (read origin), size, downsample.
            String defaultName = String.format(
                    "tme-quant_tile_x%d_y%d_w%d_h%d_ds%s_ch%d.png",
                    tl.fullX, tl.fullY, gray.getWidth(), gray.getHeight(),
                    trimNum(d), channel);
            FileChooser fc = new FileChooser();
            fc.setTitle("Export tile pixels");
            fc.setInitialFileName(defaultName);
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
            File file = fc.showSaveDialog(maskCanvas.getScene().getWindow());
            if (file == null) {
                return;
            }
            if (!file.getName().toLowerCase().endsWith(".png")) {
                file = new File(file.getParentFile(), file.getName() + ".png");
            }
            javax.imageio.ImageIO.write(gray, "PNG", file);
            setBusy(false, "Exported tile (x=" + tl.fullX + ", y=" + tl.fullY + ") to " + file.getName());
            logger.info("Exported tile full=({},{}) {}x{} ds={} -> {}",
                    tl.fullX, tl.fullY, gray.getWidth(), gray.getHeight(), d, file.getAbsolutePath());
        } catch (Exception ex) {
            logger.error("Export tile failed", ex);
            Dialogs.showErrorMessage(TITLE, "Export failed: " + ex.getMessage());
        }
    }

    /** Human-friendly duration: "45 s", "3 min", "1 h 12 min". */
    private static String humanTime(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 90) {
            return s + " s";
        }
        long m = (s + 30) / 60;
        if (m < 90) {
            return m + " min";
        }
        return (m / 60) + " h " + (m % 60) + " min";
    }

    /** Format a downsample for a filename: "2" not "2.0", "1.5" stays "1.5". */
    private static String trimNum(double v) {
        if (v == Math.rint(v)) {
            return Integer.toString((int) v);
        }
        return Double.toString(v).replace('.', 'p');
    }

    /** Stage ② — draw the background + the detected fiber polylines (after Preview). */
    private void redrawFibers() {
        if (lastBgFx == null || canvas == null) {
            return;
        }
        int gw = lastBgW;
        int gh = lastBgH;
        double scale = previewScale(gw, gh) * previewZoom;
        double cw = gw * scale;
        double ch = gh * scale;
        canvas.setWidth(cw);
        canvas.setHeight(ch);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, cw, ch);
        gc.drawImage(lastBgFx, 0, 0, cw, ch);
        gc.setLineWidth(1.0);
        // Fibers are in region-analysed pixels; the background may be coarser
        // (lastBgRatio = analysisDownsample / backgroundDownsample), so scale by both.
        double s = scale * lastBgRatio;
        if (lastFibers != null) {
            for (FiberSocketClient.Fiber f : lastFibers) {
                double[][] pts = f.points;
                if (pts == null || pts.length < 2) {
                    continue;
                }
                gc.setStroke(colorFor(f.tacs));
                gc.beginPath();
                gc.moveTo(pts[0][0] * s, pts[0][1] * s);
                for (int i = 1; i < pts.length; i++) {
                    gc.lineTo(pts[i][0] * s, pts[i][1] * s);
                }
                gc.stroke();
            }
        }
        // Tile grid + the selectable highlight overlay the fiber preview (click to pick).
        drawTileGrid(gc, scale);
    }

    /** Refresh both preview canvases (background + mask, background + fibers). */
    private void redrawPreview() {
        redrawMask();
        redrawFibers();
    }

    /**
     * Red overlay marking the pixels FIRE treats as foreground: the globally-
     * normalised background, BLURRED by the current smoothing (sigma_im), then
     * thresholded -- matching FIRE's own {@code ims = smooth(im); imt = ims > thr}.
     */
    private javafx.scene.image.Image buildThresholdOverlay() {
        return buildOverlayFor(lastBgGray, lastBgRatio);
    }

    /**
     * Red foreground overlay for a gray image. {@code ratio} = analysisDownsample / imageDownsample
     * (so 1.0 when the image IS at the analysis downsample -- the faithful case). sigma_im is in
     * analysis px, so the equivalent blur on this image is {@code sigma_im * ratio}.
     */
    /**
     * The threshold mask for a tile as an AWT ARGB image (red where foreground), matching
     * {@link #buildOverlayFor} — for painting on the QuPath viewer via {@link FiberPreviewOverlay}.
     * {@code gray} is read at the selected downsample, so no ratio scaling of sigma is needed.
     */
    private BufferedImage buildTileMaskArgb(BufferedImage gray, double sigma, int thr) {
        if (gray == null) {
            return null;
        }
        int w = gray.getWidth();
        int h = gray.getHeight();
        int[] s = gray.getRaster().getSamples(0, 0, w, h, 0, (int[]) null);
        if (sigma > 0.05) {
            gaussianBlur(s, w, h, sigma);
        }
        int[] out = new int[w * h];
        int fg = MASK_FG_ARGB; // semi-transparent violet ARGB (same as buildOverlayFor)
        for (int i = 0; i < out.length; i++) {
            out[i] = s[i] > thr ? fg : 0;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        return img;
    }

    private javafx.scene.image.Image buildOverlayFor(BufferedImage gray, double ratio) {
        if (gray == null) {
            return null;
        }
        int w = gray.getWidth();
        int h = gray.getHeight();
        int[] s = gray.getRaster().getSamples(0, 0, w, h, 0, (int[]) null);
        double sigmaBg = smoothingSpinner.getValue() * ratio;
        if (sigmaBg > 0.05) {
            gaussianBlur(s, w, h, sigmaBg);
        }
        int thr = threshIm2();
        javafx.scene.image.WritableImage img = new javafx.scene.image.WritableImage(w, h);
        javafx.scene.image.PixelWriter pw = img.getPixelWriter();
        int fg = MASK_FG_ARGB; // semi-transparent violet ARGB
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                pw.setArgb(x, y, s[row + x] > thr ? fg : 0x00000000);
            }
        }
        return img;
    }

    /** In-place separable Gaussian blur on an 8-bit sample buffer (for the mask). */
    private static void gaussianBlur(int[] img, int w, int h, double sigma) {
        int radius = Math.max(1, (int) Math.ceil(sigma * 3));
        double[] k = new double[radius * 2 + 1];
        double sum = 0;
        double s2 = 2 * sigma * sigma;
        for (int i = -radius; i <= radius; i++) {
            double v = Math.exp(-(i * i) / s2);
            k[i + radius] = v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }
        double[] tmp = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double acc = 0;
                for (int i = -radius; i <= radius; i++) {
                    int xx = Math.min(w - 1, Math.max(0, x + i));
                    acc += img[y * w + xx] * k[i + radius];
                }
                tmp[y * w + x] = acc;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double acc = 0;
                for (int i = -radius; i <= radius; i++) {
                    int yy = Math.min(h - 1, Math.max(0, y + i));
                    acc += tmp[yy * w + x] * k[i + radius];
                }
                img[y * w + x] = (int) Math.round(acc);
            }
        }
    }

    private static Color colorFor(String tacs) {
        if (tacs == null) {
            return Color.LIME;
        }
        switch (tacs) {
            case "TACS-3":
                return Color.RED;
            case "TACS-2":
                return Color.LIMEGREEN;
            case "TACS-1":
                return Color.DODGERBLUE;
            default:
                return Color.LIME;
        }
    }

    /**
     * Point the analysis region (rx/ry/rw/rh + selectedRoi) at the annotation currently
     * selected in QuPath, if any. Returns true if the region actually changed. Leaves the
     * region untouched when nothing with a ROI is selected.
     */
    private boolean retargetRegionToSelection() {
        if (wholeImageCheck != null && wholeImageCheck.isSelected()) {
            return false; // whole-image mode pins the region; ignore the selection
        }
        PathObject sel = hierarchy.getSelectionModel().getSelectedObject();
        // Only AREA annotations define the analysis region. Lines/points (e.g. ground-truth
        // tracings) and detections are ignored, so drawing them doesn't reset the region.
        if (sel == null || !sel.isAnnotation() || !sel.hasROI() || !sel.getROI().isArea()) {
            return false;
        }
        ROI roi = sel.getROI();
        int x = Math.max(0, (int) Math.floor(roi.getBoundsX()));
        int y = Math.max(0, (int) Math.floor(roi.getBoundsY()));
        int w = Math.min(server.getWidth() - x, (int) Math.ceil(roi.getBoundsWidth()));
        int h = Math.min(server.getHeight() - y, (int) Math.ceil(roi.getBoundsHeight()));
        if (w <= 0 || h <= 0) {
            return false;
        }
        if (sel == selected && roi == selectedRoi && x == rx && y == ry && w == rw && h == rh) {
            return false;
        }
        this.selected = sel;
        this.selectedRoi = roi;
        this.rx = x;
        this.ry = y;
        this.rw = w;
        this.rh = h;
        return true;
    }

    /** Pin the analysis region to the entire image (whole-image mode). */
    private void setWholeImageRegion() {
        this.selected = null;
        this.selectedRoi = null;
        this.rx = 0;
        this.ry = 0;
        this.rw = server.getWidth();
        this.rh = server.getHeight();
    }

    /** QuPath selection changed: follow it (region) and refresh the boundary list. */
    private void onSelectionChanged() {
        if (busy) {
            return; // don't move the region out from under a running analysis
        }
        boolean changed = retargetRegionToSelection();
        refreshBoundaryBox();
        if (changed) {
            previewTileIdx = -1;
            previewZoom = 1.0;
            // The intensity stats + normalization are REGION-specific: a dense collagen region and
            // the whole (mostly-dark) image have very different ranges. Recompute them so the mask,
            // the "% above" readout and FIRE's prescale all reflect the region actually selected —
            // but KEEP the user's threshold value (native units), just re-clamp it to the new range.
            computeStats(chosenChannel());
            applyStatsToThresholdSpinner(false);
            updateRegionStatus();
            refreshBackground();
        }
    }

    /** Hierarchy changed (objects added/removed/edited): keep the boundary list current. */
    private void onHierarchyChanged() {
        if (busy) {
            return;
        }
        refreshBoundaryBox();
    }

    /** Rebuild the boundary dropdown from the live hierarchy, preserving the current choice. */
    private void refreshBoundaryBox() {
        int idx = boundaryBox.getSelectionModel().getSelectedIndex();
        PathObject prev = (idx >= 0 && idx < boundaryOptions.size()) ? boundaryOptions.get(idx) : null;
        List<String> labels = boundaryLabels(); // repopulates boundaryOptions
        boundaryBox.getItems().setAll(labels);
        int newIdx = prev != null ? boundaryOptions.indexOf(prev) : -1;
        if (newIdx < 0 && !boundaryOptions.isEmpty()) {
            newIdx = 0;
        }
        boundaryBox.getSelectionModel().select(newIdx);
    }

    /** Update the status line to reflect the current analysis region. */
    private void updateRegionStatus() {
        if (statusLabel == null) {
            return;
        }
        String nm = selected == null ? "whole image"
                : (selected.getName() != null ? selected.getName()
                   : (selected.getPathClass() != null ? selected.getPathClass().toString()
                      : "selected annotation"));
        statusLabel.setText(String.format("Region %d×%d px at (%d, %d)%s — %s.",
                rw, rh, rx, ry,
                calibrated ? String.format(", %.4f µm/px", pixelSizeMicrons) : " (uncalibrated)", nm));
    }

    /**
     * Remove previously-created Fiber and TACS-1/2/3 objects whose centroid falls inside the
     * given region. Returns how many were removed. Objects elsewhere in the image are kept.
     */
    private int removeExistingFiberObjects(int x, int y, int w, int h) {
        return removeExistingFiberObjects(hierarchy, x, y, w, h);
    }

    /** Remove prior Fiber/TACS objects whose centroid falls in the region, from a GIVEN hierarchy. */
    private int removeExistingFiberObjects(PathObjectHierarchy hier, int x, int y, int w, int h) {
        java.util.Set<String> targetClasses = java.util.Set.of("Fiber", "TACS-1", "TACS-2", "TACS-3");
        List<PathObject> toRemove = new java.util.ArrayList<>();
        List<PathObject> candidates = new java.util.ArrayList<>();
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

    private void commit(Stage stage) {
        // Retarget the analysis region to whatever annotation is selected in QuPath right now,
        // so the window can stay open and be reused across regions. Unchanged if nothing is
        // selected (keeps the region the dialog opened on).
        final boolean regionChanged = retargetRegionToSelection();
        final int channel = chosenChannel();
        final double downsample = downsampleSpinner.getValue();
        FiberSocketPreferences.thresholdProperty().set(thresholdSpinner.getValue()); // for the headless script default
        saveLastSettings(); // remember these settings for THIS image (restored if it is reopened)
        final boolean tacs = tacsCheck.isSelected();
        final ROI boundary = tacs ? boundaryRoi() : null;
        if (tacs && boundary == null) {
            Dialogs.showErrorMessage(TITLE, "TACS is on but no boundary annotation is selected.");
            return;
        }
        final JsonObject extra;
        try {
            extra = parseExtra();
        } catch (JsonSyntaxException | IllegalStateException ex) {
            Dialogs.showErrorMessage(TITLE, "Extra params is not valid JSON object:\n" + ex.getMessage());
            return;
        }
        // Reuse the last whole-region preview only if settings AND region are unchanged;
        // otherwise run the full tiled analysis fresh so a one-tile preview never commits
        // partial data (and a retargeted region always re-runs).
        final boolean canReuse = !regionChanged && lastFibers != null && lastWasFull
                && lastDownsample == downsample
                && settingsSignature().equals(lastSignature);
        FiberSocketPreferences.setCollagenChannel(channel);
        setBusy(true, canReuse ? "Adding fibers..." : "Running full analysis...");
        runRestrictRoi = interactiveRestrictRoi();

        // Save a run log into the project (if there is one and the option is on) so a long
        // run's detail survives the server console scrolling away or a crash. The client
        // progress file and the FIRE server transcript share one stamp and folder.
        final Project<BufferedImage> logProject = qupath.getProject();
        final java.nio.file.Path logDir =
                saveLogCheck.isSelected() ? RunLog.projectLogDir(logProject) : null;
        final String logStamp = RunLog.stamp();
        if (logDir != null) {
            runServerLogDir = logDir.toString();
            runServerLogFile = "fiber-run-" + logStamp + "-server.log";
        } else {
            runServerLogDir = null;
            runServerLogFile = null;
        }
        String logImgName = "current image";
        try {
            if (logProject != null && qupath.getImageData() != null) {
                logImgName = logProject.getEntry(qupath.getImageData()).getImageName();
            } else if (server != null) {
                logImgName = server.getMetadata().getName();
            }
        } catch (Exception ignore) {
            // fall back to the placeholder name
        }
        final String logHeader = String.join("\n",
                "Image: " + logImgName,
                "Region (full-res px): x=" + rx + " y=" + ry + " w=" + rw + " h=" + rh,
                "Downsample: " + downsample + "   Max tile size (px): " + maxTileDim(),
                "Min fibre length: " + minLenSpinner.getValue() + " " + unitLabel()
                        + "   Seed spacing: " + seedSpacingSpinner.getValue() + " " + unitLabel(),
                "Output: " + (detectionsCheck.isSelected() ? "detections" : "annotations")
                        + (runRestrictRoi != null ? "   Restrict-to-object: ON" : "")
                        + (canReuse ? "   (reusing last preview — no tiling)" : "")
                        + (runServerLogFile != null ? "\nServer transcript: " + runServerLogFile : ""));
        final double areaRunId = FiberObjectBuilder.runIdFromStamp(logStamp);
        FiberObjectBuilder.setRunId(areaRunId);
        RunLog.appendRunIndex(logDir, logStamp, areaRunId, runSettingsBlock());
        final RunLog runLog = RunLog.open(logDir, "fiber-run-" + logStamp + "-client.txt",
                "Apply to chosen area", logHeader + "\n" + runSettingsBlock());
        final java.util.function.Consumer<String> statusLog = s -> {
            statusAsync(s);
            runLog.line(s);
        };

        // Live painting: as each tile of a fresh run completes, paint its fibers onto the
        // viewer via a lightweight OVERLAY (plain Graphics2D lines) rather than adding them as
        // per-tile PathObjects — tens of thousands of provisional detections would churn the
        // hierarchy and freeze QuPath. The overlay is removed when the run finishes and the
        // real, cleaned objects are committed. We still keep the raw fibers accumulated so a
        // mid-run failure can leave the completed tiles behind as objects. Reused previews and
        // runs whose image isn't the active viewer skip the overlay.
        final ImagePlane planeProv =
                selectedRoi != null ? selectedRoi.getImagePlane() : ImagePlane.getDefaultPlane();
        final PathClass fiberClassProv = PathClass.fromString("Fiber", ColorTools.packRGB(0, 220, 0));
        final List<FiberSocketClient.Fiber> liveAcc =
                java.util.Collections.synchronizedList(new ArrayList<>());
        clearLiveOverlay(); // drop any lingering "show tile in viewer" overlay before we paint live
        final QuPathViewer liveViewer =
                (!canReuse && qupath.getViewer() != null && qupath.getViewer().getImageData() == imageData)
                        ? qupath.getViewer() : null;
        if (liveViewer != null) {
            liveOverlay = new FiberPreviewOverlay(
                    liveViewer.getOverlayOptions(), FIBER_OVERLAY_COLOR, FIBER_CASING_COLOR);
            liveOverlay.setWidthByThickness(thicknessWidthCheck.isSelected());
            liveOverlayViewer = liveViewer;
            final FiberPreviewOverlay ov = liveOverlay;
            Platform.runLater(() -> {
                liveViewer.getCustomOverlayLayers().add(ov);
                watchOverlayToggle(liveViewer); // honour the "C" overlay toggle live
                refreshClearOverlayUi();
            });
        }
        final FiberPreviewOverlay overlay = liveOverlay;
        final TileSink liveSink = canReuse ? null : (idx, total, coreFibers, tile) -> {
            runLog.line("tile " + (idx + 1) + "/" + total + ": +" + coreFibers.size() + " fibre(s)");
            if (coreFibers.isEmpty()) {
                return;
            }
            liveAcc.addAll(coreFibers); // kept for the failure fallback (raw objects)
            if (overlay != null) {
                overlay.addFibers(coreFibers, rx, ry, downsample);
                requestOverlayRepaint(liveViewer);
            }
        };

        new Thread(() -> {
            try {
                final List<FiberSocketClient.Fiber> fibers;
                final double d;
                if (canReuse) {
                    fibers = lastFibers;
                    d = lastDownsample;
                } else {
                    if (!guardHeavyRun(channel, downsample, "Apply to chosen area", true)) {
                        runLog.close();
                        runServerLogDir = null;
                        runServerLogFile = null;
                        setBusy(false, "Cancelled — settings unchanged.");
                        return;
                    }
                    AnalyzeResult res = analyzeRegion(
                            channel, downsample, tacs, boundary, extra, statusLog, liveSink);
                    fibers = res.fibers;
                    d = downsample;
                }
                Platform.runLater(() -> {
                    // Drop the transient live-preview overlay; the cleaned-up final set below
                    // replaces it as real objects.
                    clearLiveOverlay();
                    boolean asDetections = detectionsCheck.isSelected();
                    ImagePlane plane =
                            selectedRoi != null ? selectedRoi.getImagePlane() : ImagePlane.getDefaultPlane();
                    PathClass fiberClass = PathClass.fromString("Fiber", ColorTools.packRGB(0, 220, 0));
                    List<PathObject> objects = FiberObjectBuilder.build(
                            fibers, rx, ry, d, pixelSizeMicrons, plane, asDetections, fiberClass,
                            runRestrictRoi, minLenSpinner.getValue());
                    // Large ANNOTATION commits can stall or crash QuPath: confirm, offering detections.
                    if (!asDetections && objects.size() > ANNOTATION_WARN_COUNT) {
                        String toDet = "Create " + objects.size() + " detections instead (recommended)";
                        String toAnn = "Create " + objects.size() + " annotations anyway";
                        String cancel = "Cancel (add nothing)";
                        String pick = Dialogs.showChoiceDialog(TITLE,
                                objects.size() + " fibres would be added as ANNOTATIONS.\n\n"
                                        + "More than ~" + ANNOTATION_WARN_COUNT + " annotations can make "
                                        + "QuPath very slow or crash it. Detections hold the same "
                                        + "measurements and stay responsive. For ground-truth editing, "
                                        + "use annotations on a smaller region.",
                                List.of(toDet, toAnn, cancel), toDet);
                        if (pick == null || cancel.equals(pick)) {
                            runLog.line("CANCELLED at commit: declined to add " + objects.size()
                                    + " annotations.");
                            runLog.close();
                            runServerLogDir = null;
                            runServerLogFile = null;
                            setBusy(false, "Nothing added — " + objects.size()
                                    + " annotations declined. Tick 'Create detections' or use a smaller region.");
                            return;
                        }
                        if (toDet.equals(pick)) {
                            asDetections = true;
                            detectionsCheck.setSelected(true);
                            objects = FiberObjectBuilder.build(
                                    fibers, rx, ry, d, pixelSizeMicrons, plane, true, fiberClass,
                                    runRestrictRoi, minLenSpinner.getValue());
                        }
                    }
                    // Optionally clear prior Fiber/TACS objects in this region first, so
                    // repeated runs don't stack overlapping objects.
                    int removed = 0;
                    if (clearExistingCheck.isSelected()) {
                        removed = removeExistingFiberObjects(rx, ry, rw, rh);
                    }
                    logger.info("Apply to chosen area: removed {} prior, committing {} {} (reuse={}, regionChanged={})",
                            removed, objects.size(), asDetections ? "detections" : "annotations",
                            canReuse, regionChanged);
                    if (!objects.isEmpty()) {
                        hierarchy.addObjects(objects);
                        hierarchy.getSelectionModel().clearSelection();
                    }
                    String msg = objects.size() + " fibers added"
                            + (removed > 0 ? " (" + removed + " prior removed)" : "") + ".";
                    runLog.line("DONE: " + msg);
                    runLog.close();
                    runServerLogDir = null;
                    runServerLogFile = null;
                    if (runLog.file() != null) {
                        msg += "  Log: " + runLog.file().getFileName();
                    }
                    setBusy(false, msg);
                    Dialogs.showInfoNotification(TITLE, msg);
                    // Keep the window open. If we retargeted to a new region, refresh the
                    // preview so the dialog reflects the region just committed.
                    if (regionChanged) {
                        previewTileIdx = -1;
                        previewZoom = 1.0;
                        refreshBackground();
                    }
                });
            } catch (Exception ex) {
                logger.error("Apply to chosen area failed", ex);
                // Drop the transient overlay, but persist the tiles that DID finish as raw
                // objects (one bulk add) so the user can see how far the run got.
                final List<FiberSocketClient.Fiber> partial = new ArrayList<>(liveAcc);
                final int kept = partial.size();
                Platform.runLater(() -> {
                    clearLiveOverlay();
                    if (!partial.isEmpty()) {
                        // Can't ask mid-failure, so a large partial set goes in as detections.
                        boolean partialAsDet = detectionsCheck.isSelected()
                                || partial.size() > ANNOTATION_WARN_COUNT;
                        List<PathObject> objs = FiberObjectBuilder.build(
                                partial, rx, ry, downsample, pixelSizeMicrons, planeProv,
                                partialAsDet, fiberClassProv, null, 0.0);
                        if (!objs.isEmpty()) {
                            hierarchy.addObjects(objs);
                        }
                    }
                });
                runLog.line("FAILED: " + ex.getMessage()
                        + (kept > 0 ? " (" + kept + " fibers from completed tiles left in place)" : ""));
                runLog.close();
                runServerLogDir = null;
                runServerLogFile = null;
                fail("Apply to chosen area failed: " + ex.getMessage()
                        + (kept > 0 ? " (" + kept + " fibers from completed tiles left in place)" : ""));
            }
        }, "tmequant-commit").start();
    }

    /**
     * Show the selected tile on the REAL image in the active QuPath viewer, zoomed to that tile —
     * giving true full-resolution inspection with native zoom/pan, instead of the coarse in-dialog
     * thumbnail. Always paints the threshold mask; when {@code withFibers} is true it ALSO runs FIRE
     * on the tile and paints the detected fibres. Painted via {@link FiberPreviewOverlay} (no objects
     * added); "Clear overlay" (or another action) clears the overlay.
     */
    private void showTileInViewer(boolean withFibers) {
        if (busy) {
            return;
        }
        saveLastSettings(); // "Show tile in viewer" is a real use of these settings — remember them
        final QuPathViewer vw = qupath.getViewer();
        if (vw == null || vw.getImageData() != imageData) {
            Dialogs.showWarningNotification(TITLE,
                    "Open this image in the active QuPath viewer first, then try again.");
            return;
        }
        final int channel = chosenChannel();
        final double d = downsampleSpinner.getValue();
        final JsonObject extra;
        if (withFibers) {
            try {
                extra = parseExtra();
            } catch (JsonSyntaxException | IllegalStateException ex) {
                Dialogs.showErrorMessage(TITLE, "Extra params is not valid JSON:\n" + ex.getMessage());
                return;
            }
        } else {
            extra = null; // threshold-only: no FIRE run
        }
        final int rwA = (int) Math.ceil(rw / d);
        final int rhA = (int) Math.ceil(rh / d);
        final List<Tile> tiles = computeTiles(rwA, rhA, d);
        final int idx = effectivePreviewTile(tiles.size());
        final Tile tl = tiles.get(idx);
        // Zoom the viewer so the tile fits ~90% of the view, centred on it (full-res image coords).
        final double vwW = Math.max(1.0, vw.getView().getWidth());
        final double vwH = Math.max(1.0, vw.getView().getHeight());
        final double targetDown = Math.max(1.0, Math.max(tl.fullW / vwW, tl.fullH / vwH) / 0.9);
        final double cx = tl.fullX + tl.fullW / 2.0;
        final double cy = tl.fullY + tl.fullH / 2.0;
        final int rxL = rx;
        final int ryL = ry;
        // Snapshot the mask inputs so the worker thread can build the SAME red threshold mask the
        // ① preview shows (blur by sigma, red where gray > threshIm2), read at the selected downsample.
        final double smoothingVal = smoothingSpinner.getValue();
        // The threshold-only viewer (stage ①) previews the 2ND-PASS threshold when two-pass is on
        // (the always-on ① mask already shows pass 1); the with-fibres viewer stays on the main one.
        final boolean showSecondThreshold =
                !withFibers && twoPassCheck != null && twoPassCheck.isSelected();
        final double maskThreshNative = showSecondThreshold
                ? secondThresholdSpinner.getValue() : thresholdSpinner.getValue();
        final int maskThr = threshIm2For(maskThreshNative);
        final double nLo = normLo;
        final double nHi = normHi;
        setBusy(true, String.format(withFibers ? "Detecting tile %d/%d for the viewer…"
                : "Rendering the threshold for tile %d/%d…", idx + 1, tiles.size()));
        // Only the fibre path runs a server detection worth logging; the threshold-only path is a
        // local read + client-side mask, so it stays a no-op.
        final RunLog tileLog = withFibers ? beginPreviewLog("Show tile in viewer") : RunLog.NOOP;
        final java.util.function.Consumer<String> tileStatus = s -> {
            statusAsync(s);
            tileLog.line(s);
        };
        new Thread(() -> {
            try {
                if (withFibers && !guardHeavyRun(channel, d, "Show tile in viewer", false)) {
                    setBusy(false, "Cancelled — settings unchanged.");
                    return;
                }
                final List<FiberSocketClient.Fiber> fibers;
                if (withFibers) {
                    AnalyzeResult res = analyzeOneTile(channel, d, extra, tileStatus);
                    fibers = res.fibers != null ? res.fibers : new ArrayList<>();
                    tileLog.line(diagnostics(res).trim());
                } else {
                    fibers = new ArrayList<>();
                }
                // Build the tile's threshold mask (same recipe as the ① live mask, faithful at d).
                BufferedImage maskArgb = null;
                try {
                    BufferedImage tileRegion = server.readRegion(RegionRequest.createInstance(
                            server.getPath(), d, tl.fullX, tl.fullY, tl.fullW, tl.fullH));
                    if (tileRegion != null) {
                        BufferedImage tileGray = ChannelExtractor.toGray(tileRegion, channel, nLo, nHi);
                        maskArgb = buildTileMaskArgb(tileGray, smoothingVal, maskThr);
                    }
                } catch (Exception mex) {
                    logger.warn("Tile threshold-mask read failed: {}", mex.getMessage());
                }
                final BufferedImage maskArgbF = maskArgb;
                Platform.runLater(() -> {
                    clearLiveOverlay(); // drop any previous tile-preview overlay
                    FiberPreviewOverlay ov = new FiberPreviewOverlay(
                            vw.getOverlayOptions(), FIBER_OVERLAY_COLOR, FIBER_CASING_COLOR);
                    ov.setWidthByThickness(thicknessWidthCheck.isSelected());
                    ov.setMask(maskArgbF, tl.fullX, tl.fullY, tl.fullW, tl.fullH);
                    ov.addFibers(fibers, rxL, ryL, d);
                    liveOverlay = ov;
                    liveOverlayViewer = vw;
                    vw.getCustomOverlayLayers().add(ov);
                    watchOverlayToggle(vw); // honour the "C" overlay toggle live
                    refreshClearOverlayUi();
                    // Zoom, then centre on the tile in FULL-RES image px. (The 3-arg
                    // setDownsampleFactor(ds, x, y) does NOT centre on image coords — it sent the
                    // view off into black space; setCenterPixelLocation is the correct call.)
                    vw.setDownsampleFactor(targetDown);
                    vw.setCenterPixelLocation(cx, cy);
                    vw.repaint();
                    refreshClearOverlayUi(); // reveal both 'Clear overlay' buttons + amber halo
                    setBusy(false, withFibers
                            ? fibers.size() + " fibre(s) + threshold mask shown on the image at full "
                                    + "resolution — zoom/pan freely. 'Clear overlay' clears it."
                            : (showSecondThreshold ? "2nd-pass" : "Threshold") + " mask ("
                                    + trimNum(maskThreshNative) + ") shown on the image at full "
                                    + "resolution — zoom/pan freely. 'Clear overlay' clears it.");
                });
            } catch (Exception ex) {
                logger.error("Show-tile-in-viewer failed", ex);
                tileLog.line("Show tile in viewer FAILED: " + ex.getMessage());
                fail("Show tile in viewer failed: " + ex.getMessage());
            } finally {
                if (withFibers) {
                    endPreviewLog(tileLog);
                }
            }
        }, "tmequant-tileview").start();
    }

    /** Show a yes/no confirm dialog and return the answer, from any thread (blocks a worker thread). */
    private boolean confirmOnFx(String title, String message) {
        if (Platform.isFxApplicationThread()) {
            return Dialogs.showConfirmDialog(title, message);
        }
        final java.util.concurrent.atomic.AtomicBoolean res =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                res.set(Dialogs.showConfirmDialog(title, message));
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return res.get();
    }

    /**
     * Pre-run RUNTIME heads-up. Crashes are now prevented structurally (the physical tile-size cap
     * bounds FIRE's per-tile load and the fiber2beam node cap bounds interpolation), and a too-dense
     * tile fails gracefully (skipped in a full run), so this NO LONGER warns about density or
     * resolution — those turned out to be the settings that actually work. All it does now is warn
     * before a genuinely LONG tiled run (a large tile count), which is purely about wall-clock time.
     * Single-tile previews never warn. Returns true to proceed, false to abort. Never blocks a run
     * because the check itself failed. {@code wholeRun} = a tiled whole-region/commit/batch run.
     */
    private boolean guardHeavyRun(int channel, double downsample, String action, boolean wholeRun) {
        if (!wholeRun) {
            return true; // one tile — nothing to warn about
        }
        try {
            int rwA = (int) Math.ceil(rw / downsample);
            int rhA = (int) Math.ceil(rh / downsample);
            int tiles = computeTiles(rwA, rhA, downsample).size();
            final int MANY_TILES = 400; // only a genuinely long run is worth a heads-up
            if (tiles <= MANY_TILES) {
                return true;
            }
            String msg = String.format(
                    "%s will process %,d tiles at downsample %s — this is a long run.%n%nProceed?",
                    action, tiles, trimNum(downsample));
            return confirmOnFx("Long run — proceed?", msg);
        } catch (Exception ex) {
            logger.warn("Pre-run runtime check failed (continuing without it): {}", ex.getMessage());
            return true;
        }
    }

    /** Repaint the viewer when the "C" overlay toggle flips (registered once per live overlay). */
    private void watchOverlayToggle(QuPathViewer vw) {
        if (vw == null || overlayVisListener != null) {
            return;
        }
        overlayVisListener = (o, was, now) -> Platform.runLater(vw::repaint);
        vw.getOverlayOptions().showPixelClassificationProperty().addListener(overlayVisListener);
    }

    /** Remove the transient live-preview overlay from its viewer (FX-thread safe, idempotent). */
    private void clearLiveOverlay() {
        final FiberPreviewOverlay ov = liveOverlay;
        final QuPathViewer vw = liveOverlayViewer;
        liveOverlay = null;
        liveOverlayViewer = null;
        if (vw != null && overlayVisListener != null) {
            vw.getOverlayOptions().showPixelClassificationProperty().removeListener(overlayVisListener);
            overlayVisListener = null;
        }
        if (ov == null || vw == null) {
            return;
        }
        Runnable r = () -> {
            vw.getCustomOverlayLayers().remove(ov);
            vw.repaint();
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /**
     * Build one "Clear overlay" button. Two identical ones live in the ① threshold section and the
     * fiber-detection section so the control is next to whichever preview the user is looking at.
     * Each is hidden (and unmanaged, so it leaves no gap) until an overlay is present; when present it
     * gets an amber halo matching the amber ⚠ message so the eye connects the two.
     */
    private Button makeClearOverlayButton() {
        Button b = new Button("Clear overlay");
        b.setTooltip(ttip(
                "Remove the tile preview overlay: the single-tile threshold mask in the ① preview "
                        + "and any 'Show tile in viewer' overlay on the image. Double-click a tile to "
                        + "preview it again."));
        b.setVisible(false);
        b.setManaged(false);
        b.managedProperty().bind(b.visibleProperty());
        b.setOnAction(e -> clearPreviewOverlay());
        return b;
    }

    /**
     * True only while an overlay the user actively created is on the image — i.e. a "Show tile in
     * viewer" overlay ({@code liveOverlay}). The default single-tile threshold mask shown in the ①
     * preview when a region is too large does NOT count: it appears without any click, so lighting the
     * "Clear overlay" control for it is confusing.
     */
    private boolean previewOverlayPresent() {
        return liveOverlay != null;
    }

    /** Clear both overlays (dialog tile mask + viewer overlay) and refresh the "Clear overlay" UI. */
    private void clearPreviewOverlay() {
        clearLiveOverlay();     // remove the tile-in-viewer overlay, if any
        maskTileCleared = true; // hide the picked-tile faithful mask in the ① preview
        redrawMask();
        refreshClearOverlayUi();
    }

    /**
     * Show/hide both "Clear overlay" buttons (with the amber halo) and tint the ⚠ message text to
     * match — all gated on whether an overlay is actually present. Call after anything that adds or
     * removes an overlay.
     */
    private void refreshClearOverlayUi() {
        boolean present = previewOverlayPresent();
        for (Button b : new Button[] {backToFullBtn, clearOverlayThreshBtn}) {
            if (b != null) {
                b.setVisible(present);
                b.setStyle(present ? CLEAR_OVERLAY_HALO : "");
            }
        }
    }

    /** Throttled viewer repaint so the live overlay refreshes smoothly without flooding the FX queue. */
    private void requestOverlayRepaint(QuPathViewer vw) {
        if (vw == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastOverlayRepaintMs < 120) {
            return;
        }
        lastOverlayRepaintMs = now;
        Platform.runLater(vw::repaint);
    }

    private void setBusy(boolean busy, String status) {
        this.busy = busy;
        Runnable r = () -> {
            if (suggestBtn != null) {
                suggestBtn.setDisable(busy);
            }
            previewBtn.setDisable(busy);
            addBtn.setDisable(busy);
            if (status != null) {
                statusLabel.setText(status);
            }
            // Draw the eye to the status (it lives in stage ②, two panes above the Suggest button):
            // a bold amber highlight while a run is in progress, back to normal when idle.
            statusLabel.setStyle(busy
                    ? "-fx-text-fill: #b35900; -fx-font-weight: bold;"
                    : "-fx-text-fill: -fx-text-base-color; -fx-font-weight: normal;");
            // Mirror the status onto the prominent bar above Add-to-image while a run is active,
            // so progress/ETA is visible where the user is looking. Hidden when idle.
            if (runStatusBar != null) {
                if (busy) {
                    if (status != null) {
                        runStatusBar.setText(status);
                    }
                    runStatusBar.setVisible(true);
                    runStatusBar.setManaged(true);
                } else {
                    runStatusBar.setVisible(false);
                    runStatusBar.setManaged(false);
                }
            }
            // If the user switched images during a run, apply that rebind now that we're idle.
            if (!busy && pendingImage != null) {
                ImageData<BufferedImage> pi = pendingImage;
                pendingImage = null;
                rebindToImage(pi);
            }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    private void fail(String msg) {
        Platform.runLater(() -> {
            setBusy(false, msg);
            Dialogs.showErrorMessage(TITLE, msg);
        });
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
