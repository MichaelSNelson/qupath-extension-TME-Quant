package qupath.ext.tmequant;

import com.google.gson.Gson;
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
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
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
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
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
    private static final int MAX_TILES = 64; // safety cap

    private final QuPathGUI qupath;
    private final ImageData<BufferedImage> imageData;
    private final ImageServer<BufferedImage> server;
    private final PathObjectHierarchy hierarchy;
    private PathObject selected; // current region annotation — kept in sync with QuPath's selection
    // Region + selection: mutable so "Add to image" can retarget to the current QuPath
    // selection without reopening the dialog.
    private ROI selectedRoi;
    private int rx;
    private int ry;
    private int rw;
    private int rh;
    private final boolean calibrated;
    private final double pixelSizeMicrons;
    private final String unit; // "um" or "px"

    private ChoiceBox<String> channelBox;
    private ChoiceBox<String> previewScopeBox; // one tile (fast) vs whole region
    private Spinner<Double> downsampleSpinner;
    private Spinner<Double> thresholdSpinner; // native intensity units
    private Label thresholdFeedback;          // live "x% of region above" readout
    // Global intensity reference (native units) computed once per channel, shared by
    // every tile so the scale + threshold are consistent (no per-tile noise blow-up).
    private double normLo = 0;
    private double normHi = 255;
    private double statMin = 0;
    private double statMax = 255;
    private double[] statSample = new double[0];
    private Spinner<Double> minLenSpinner; // um or px
    private Spinner<Double> seedSpacingSpinner; // µm (calibrated) / full-res px — downsample-independent
    private ChoiceBox<String> fiberModeBox;
    private CheckBox tacsCheck;
    private ChoiceBox<String> boundaryBox;
    private Spinner<Double> tacsZoneSpinner; // um or px
    private CheckBox detectionsCheck;
    private CheckBox clearExistingCheck; // remove prior Fiber/TACS objects in-region before adding
    private CheckBox wholeImageCheck;    // analyze the whole image instead of the selected region
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
    // Live QuPath state: keep the dialog in sync with selection + hierarchy changes.
    private PathObjectHierarchyListener hierarchyListener;
    private PathObjectSelectionListener selectionListener;
    private int previewTileIdx = -1; // which tile "One tile (fast)" previews (-1 = centre)
    private Label maskTileLabel;     // shows/lets you pick the preview tile

    private static final Gson GSON = new Gson();
    private Label statusLabel;
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

    private FiberAnalysisDialog(
            QuPathGUI qupath, ImageData<BufferedImage> imageData, PathObject selected) {
        this.qupath = qupath;
        this.imageData = imageData;
        this.server = imageData.getServer();
        this.hierarchy = imageData.getHierarchy();
        this.selected = selected;
        this.selectedRoi = (selected != null && selected.hasROI()) ? selected.getROI() : null;

        // Prefer real pixel-size metadata, but only when it is genuinely usable.
        // QuPath's hasPixelSizeMicrons() only checks the unit is "µm" -- it does
        // NOT verify the value is finite or > 0, so a malformed calibration (unit
        // µm but value 0/NaN) reports calibrated=true and getAveragedPixelSizeMicrons()
        // hands back 0 or NaN. Dividing user microns by that would corrupt every
        // length. So we additionally require the averaged size to be finite and
        // positive; otherwise we fall back to working directly in PIXELS (we can't
        // manufacture a scale that the image doesn't carry). Matches the >0/!NaN
        // guard FiberObjectBuilder already applies on the commit side.
        PixelCalibration cal = server.getPixelCalibration();
        double avgMicrons = (cal != null && cal.hasPixelSizeMicrons())
                ? cal.getAveragedPixelSizeMicrons() : Double.NaN;
        this.calibrated = Double.isFinite(avgMicrons) && avgMicrons > 0;
        this.pixelSizeMicrons = calibrated ? avgMicrons : Double.NaN;
        this.unit = calibrated ? "um" : "px";

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
        channelBox.setTooltip(new Tooltip(
                "Which image channel holds the collagen signal (e.g. the SHG or a collagen stain "
                        + "channel). Only this one channel is sent to the engine; the others are ignored."));

        previewScopeBox = new ChoiceBox<>();
        previewScopeBox.getItems().addAll("One tile (fast)", "Whole region");
        previewScopeBox.getSelectionModel().select(0);
        previewScopeBox.setTooltip(new Tooltip(
                "Preview scope. 'One tile' runs FIRE on just one tile for fast tuning — double-click "
                        + "the fiber preview to choose WHICH tile (the yellow box). 'Whole region' runs "
                        + "all tiles + stitching + TACS (slower). Add to image always does the full run."));
        // Toggling scope shows/hides the selected-tile highlight on the mask.
        previewScopeBox.getSelectionModel().selectedIndexProperty()
                .addListener((o, ov, nv) -> redrawFibers());

        double d0 = Math.max(1.0, Math.ceil(Math.max(rw, rh) / 1200.0));
        downsampleSpinner = spinnerD(1.0, 32.0, d0, 1.0);

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
        fiberModeBox.setTooltip(new Tooltip(
                "'Merged fibers' joins traced pieces into whole continuous fibers — best for long "
                        + "collagen bundles and most analyses. 'Segments' keeps each traced piece "
                        + "separate — use when short straight segments are your unit of measurement."));

        tacsCheck = new CheckBox("Classify TACS relative to a boundary");
        tacsCheck.setWrapText(true);
        tacsCheck.setSelected(tacsDefault);
        tacsCheck.setTooltip(new Tooltip(
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
        boundaryBox.setTooltip(new Tooltip(
                "The tumour-stroma boundary outline (a separate annotation inside the analysis region). "
                        + "Fibers are classified by their angle and distance relative to this outline."));
        boundaryBox.disableProperty().bind(tacsCheck.selectedProperty().not());
        double zoneDefault = calibrated ? 100.0 : 100.0;
        double zoneMax = calibrated ? 2000.0 : 1000.0;
        tacsZoneSpinner = spinnerD(1.0, zoneMax, zoneDefault, calibrated ? 5.0 : 5.0);
        tacsZoneSpinner.disableProperty().bind(tacsCheck.selectedProperty().not());

        detectionsCheck = new CheckBox("Create detections (not annotations)");
        detectionsCheck.setWrapText(true);
        detectionsCheck.setSelected(FiberSocketPreferences.getCreateDetections());

        wholeImageCheck = new CheckBox("Use whole image (ignore selection)");
        wholeImageCheck.setWrapText(true);
        wholeImageCheck.setTooltip(new Tooltip(
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
        clearExistingCheck.setTooltip(new Tooltip(
                "Before adding, delete any previously-created Fiber and TACS-1/2/3 objects whose "
                        + "location falls inside the analysis region, so repeated runs don't stack "
                        + "overlapping objects. Objects elsewhere in the image are kept."));

        // Smoothing is part of stage ① (it changes what FIRE thresholds): live mask.
        // sigma_im is created below; wire its listener after creation.

        // Advanced FIRE knobs (analysed-pixel / fractional / degree units).
        smoothingSpinner = spinnerD(0.0, 5.0, 0.0, 0.5); // sigma_im
        distSmoothSpinner = spinnerD(0.0, 5.0, 0.3, 0.1); // sigma_d
        seedSensSpinner = spinnerD(0.0, 1.0, 0.2, 0.05); // thresh_LMP
        // Cross-link search box in microns (calibrated) / full-res px — downsample-independent.
        // Default 6 µm ≈ the old 3-analysed-px default at downsample 4 and ~0.5 µm/px.
        xlinkBoxSpinner = spinnerD(0.5, 50.0, 6.0, 0.5); // s_xlinkbox
        bendAngleSpinner = spinnerD(5.0, 89.0, 70.0, 5.0); // thresh_ext = cos(deg)
        angleExtendSpinner = spinnerD(0.0, 89.0, 10.0, 5.0); // thresh_dang_aextend = cos(deg)
        // Distances/lengths/widths are in microns (full-res px when uncalibrated), converted to
        // analysed px at run time — so they're downsample-independent. µm defaults ≈ the old
        // analysed-px defaults at downsample 4 and ~0.5 µm/px.
        linkDistSpinner = spinnerD(0.0, 120.0, 30.0, 1.0); // thresh_linkd
        linkAngleSpinner = spinnerD(0.0, 89.0, 30.0, 5.0); // thresh_linka = -cos(deg)
        maxWidthSpinner = spinnerD(0.0, 120.0, 40.0, 1.0); // widMAX (0 = off)
        // Advanced parity knobs — pipeline defaults so adding them changes nothing until touched.
        dxlinkSpinner = spinnerD(0.0, 10.0, 1.0, 0.1); // thresh_Dxlink
        dirDecaySpinner = spinnerD(0.0, 1.0, 0.5, 0.05); // lam_dirdecay
        fiberDirSpinner = spinnerD(2.0, 40.0, 6.0, 1.0); // s_fiberdir (µm)
        fireFlenSpinner = spinnerD(0.0, 200.0, 30.0, 1.0); // thresh_flen (µm)
        maxSpaceSpinner = spinnerD(1.0, 60.0, 10.0, 1.0); // s_maxspace (µm)
        angIntervalSpinner = spinnerD(1.0, 30.0, 5.0, 1.0); // ang_interval (deg)
        lambdaSpinner = spinnerD(0.0, 1.0, 0.01, 0.01); // lambda
        dangLenSpinner = spinnerD(0.0, 200.0, 30.0, 1.0); // thresh_dang_L (µm)
        dxlinkSpinner.setTooltip(new Tooltip(
                "Nucleation distance threshold (thresh_Dxlink): how strong a distance-map peak must "
                        + "be to seed a cross-link. Pipeline default 1.0 (CT-FIRE GUI ships 1.5)."));
        dirDecaySpinner.setTooltip(new Tooltip(
                "Direction-decay weight (lam_dirdecay, 0–1): how strongly the tracer keeps its current "
                        + "heading vs. following the local ridge. Default 0.5."));
        fiberDirSpinner.setTooltip(new Tooltip(
                "Window used to estimate a fiber's local direction (s_fiberdir), in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + " — converted to analysis pixels at run time. Larger = smoother/steadier "
                        + "direction."));
        fireFlenSpinner.setTooltip(new Tooltip(
                "FIRE's own short-fiber removal length (thresh_flen), in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + ", applied inside the backend during linking. Distinct from 'Min fiber "
                        + "length' (the post-trace LL1 filter)."));
        maxSpaceSpinner.setTooltip(new Tooltip(
                "Vertex spacing used when interpolating each fiber centerline (s_maxspace), in "
                        + (calibrated ? "microns." : "full-resolution pixels.")));
        angIntervalSpinner.setTooltip(new Tooltip(
                "Angle sampling interval (ang_interval, degrees) used when computing per-fiber angles. "
                        + "Default 5."));
        lambdaSpinner.setTooltip(new Tooltip(
                "Beam-fit regularisation (lambda) used in fiber post-processing. Default 0.01."));
        dangLenSpinner.setTooltip(new Tooltip(
                "Dangling-end pruning length (thresh_dang_L), in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + ": short spurs up to this length at a fiber end are trimmed."));

        // Suggest/tuner options.
        countTolSpinner = spinnerD(0.0, 50.0, 15.0, 5.0); // count tolerance band (%)
        maxRoundsSpinner = spinnerI(1, 6, 3, 1); // iterative refinement rounds
        pieceWeightBox = new ChoiceBox<>();
        pieceWeightBox.getItems().addAll("Flat union", "Area-weighted");
        pieceWeightBox.getSelectionModel().select(0);
        pieceWeightBox.setMaxWidth(Double.MAX_VALUE);
        countTolSpinner.setTooltip(new Tooltip(
                "How close the detected count must be to your annotated count to score perfectly "
                        + "(± this %). Human annotation is imperfect, so a band beats an exact target. "
                        + "Default ±15%."));
        maxRoundsSpinner.setTooltip(new Tooltip(
                "Iterative zoom: after a coarse grid, the search re-centres a finer grid on the best "
                        + "detection settings and repeats, up to this many rounds (stops early once "
                        + "the best stops improving). Default 3."));
        pieceWeightBox.setTooltip(new Tooltip(
                "For a disjoint (multi-part) annotation: how to combine the pieces. 'Flat union' pools "
                        + "all detected/ground-truth fibres into one total (recommended). 'Area-weighted' "
                        + "weights each piece's quality terms by its area."));
        // Smoothing belongs to stage ①: FIRE blurs the image BEFORE thresholding, so
        // the live mask must reflect it. Update the mask as it changes.
        smoothingSpinner.valueProperty().addListener((o, ov, nv) -> redrawMask());
        smoothingSpinner.setTooltip(new Tooltip(
                "Gaussian blur (sigma_im, in analysis px at the current downsample) applied before "
                        + "thresholding AND tracing. Part of stage ①: the live mask reflects it. "
                        + "Raise to suppress noise/grain; lower toward 0 to keep fine detail."));
        distSmoothSpinner.setTooltip(new Tooltip(
                "Extra smoothing of the internal distance map before seeds are placed (sigma_d). "
                        + "Raise (0.5–2) on noisy/faint collagen to merge broken fiber ridges into "
                        + "continuous traces; the companion to 'Smoothing'."));
        seedSensSpinner.setTooltip(new Tooltip(
                "How easily a point becomes a fiber starting-point (thresh_LMP). LOWER accepts weaker "
                        + "spots → MORE seeds → more fibers; raise to seed only the brightest cores. "
                        + "(Different from 'Seed spacing', which sets how far apart seeds must be.)"));
        xlinkBoxSpinner.setTooltip(new Tooltip(
                "Search-box radius (s_xlinkbox) used when finding nucleation points / cross-links, in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + " — converted to analysis pixels at run time, so it stays fixed when you "
                        + "change the downsample. Larger merges nearby maxima into one seed; smaller "
                        + "keeps them separate. Default 6 µm ≈ the pipeline default 3 px at downsample 4."));
        angleExtendSpinner.setTooltip(new Tooltip(
                "Angle-extend threshold (thresh_dang_aextend), in degrees. The largest turn a fiber "
                        + "end may take while still being extended through a junction. Default 10°."));
        bendAngleSpinner.setTooltip(new Tooltip(
                "Largest turn the tracer may make at each step, in degrees (maps to thresh_ext). "
                        + "RAISE to follow curvier/wavier collagen; lower to keep only straighter fibers. "
                        + "Default 70°."));
        linkDistSpinner.setTooltip(new Tooltip(
                "Largest gap (thresh_linkd) that can be bridged to join two fiber pieces into one, in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + ". Raise to connect fragmented bundles; lower if separate fibers merge."));
        linkAngleSpinner.setTooltip(new Tooltip(
                "How far from straight two pieces may bend and still be joined, in degrees (maps to "
                        + "thresh_linka). Raise to link curvier fibers across gaps; lower for near-straight "
                        + "joins only. Pairs with 'Link distance'. Default 30°."));
        // widMAX is applied SERVER-SIDE as a max-width filter (the compiled FIRE-only
        // backend doesn't read it), mirroring tme-quant's extraction.py: fibers wider
        // than this are dropped. 0 disables the filter.
        maxWidthSpinner.setTooltip(new Tooltip(
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
        extraParamsArea.setTooltip(new Tooltip(
                "Override any FIRE 'value'-dict parameter; these win over the controls above. "
                        + "Live keys include thresh_Dxlink, lam_dirdecay, s_fiberdir, thresh_flen, "
                        + "s_maxspace, ang_interval, lambda, thresh_dang_L. LL1 (min length) and widMAX "
                        + "(max width) are applied server-side. NO effect in this FIRE-only build: "
                        + "num_scales, coefficient_percentile (curvelet), s_minstep, s_maxstep, "
                        + "thresh_short_L, thresh_numv. Unknown keys are ignored. See the Settings "
                        + "reference (Help links at the bottom of this dialog)."));

        thresholdSpinner.setTooltip(new Tooltip(
                "Background cutoff in the image's NATIVE intensity units (the values you see "
                        + "when mousing over the image). Pixels at/below it are background. The "
                        + "label below shows how much of the region is above it."));
        updateThresholdFeedback();
        wirePreferences();
        minLenSpinner.setTooltip(new Tooltip(
                "Fibers shorter than this are discarded after tracing (LL1), in "
                        + (calibrated ? "microns" : "pixels")
                        + ". Raise to drop noise specks/fragments; lower to keep short fibers."));
        seedSpacingSpinner.setTooltip(new Tooltip(
                "Minimum distance between fiber starting-points (thresh_LMPdist), in "
                        + (calibrated ? "microns" : "full-resolution pixels")
                        + " — converted to analysis pixels at run time, so it stays fixed when you "
                        + "change the downsample. Larger → fewer, more spread-out fibers and less "
                        + "memory; smaller → denser detection. (Sets spacing, not the seed threshold.)"));
        downsampleSpinner.setTooltip(new Tooltip(
                "Resolution reduction before analysis: 2 = half resolution (~4× faster, coarser). "
                        + "Use 1 for small/high-mag regions; 2–8 for large regions. The engine is tuned "
                        + "for ~500 px images, so full-res whole slides need a higher value here. "
                        + "Preview and the committed result both use this value."));
        tacsZoneSpinner.setTooltip(new Tooltip(
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
        int r1 = 0;
        t1.addRow(r1++, new Label("Collagen channel"), channelBox);
        t1.addRow(r1++, new Label("Downsample"), downsampleSpinner);
        t1.addRow(r1++, new Label("Smoothing (sigma)"), smoothingSpinner);
        t1.addRow(r1++, new Label("Background threshold"), thresholdSpinner);
        thresholdFeedback.setStyle("-fx-font-size: 11;");
        t1.add(thresholdFeedback, 1, r1++);
        t1.setMinWidth(360);

        maskCanvas = new Canvas(PREVIEW_MAX, PREVIEW_MAX * (double) rh / Math.max(1, rw));
        maskScroll = new ScrollPane(maskCanvas);
        maskScroll.setPrefSize(PREVIEW_MAX + 16, PREVIEW_MAX + 16);
        Label maskHdr = new Label("Threshold mask — updates live (no analysis)");
        maskHdr.setStyle("-fx-font-weight: bold;");
        Label maskNote = new Label("Red = the pixels FIRE will treat as collagen, given this "
                + "channel, downsample, smoothing and threshold.");
        maskNote.setWrapText(true);
        maskNote.setStyle("-fx-font-size: 11;");
        // Tile grid, selection and export live with the fiber preview (stage ②), where the
        // 'One tile (fast)' preview actually runs — created here, added to fiberBox below.
        maskTileLabel = new Label();
        maskTileLabel.setWrapText(true);
        maskTileLabel.setStyle("-fx-font-size: 11; -fx-font-weight: bold;");
        Button exportTileBtn = new Button("Export tile…");
        exportTileBtn.setTooltip(new Tooltip(
                "Save the EXACT pixels of the selected tile (the prescaled gray image FIRE analyses) "
                        + "as a PNG. The filename includes its x,y position in the image — useful for "
                        + "reproducing a crash or sharing a problem region."));
        exportTileBtn.setOnAction(e -> exportTile());
        VBox maskBox = new VBox(4, maskHdr, maskScroll, maskNote);
        HBox sec1 = new HBox(14, t1, maskBox);
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
        int r2 = 0;
        t2.add(depNote, 0, r2++, 2, 1);
        t2.addRow(r2++, new Label("Preview scope"), previewScopeBox);
        t2.addRow(r2++, new Label("Seed spacing (" + unitLabel() + ")"), seedSpacingSpinner);
        t2.addRow(r2++, new Label("Seed sensitivity"), seedSensSpinner);
        t2.addRow(r2++, new Label("Cross-link search box (" + unitLabel() + ")"), xlinkBoxSpinner);
        t2.addRow(r2++, new Label("Max bend angle (deg)"), bendAngleSpinner);
        t2.addRow(r2++, new Label("Angle-extend threshold (deg)"), angleExtendSpinner);
        t2.addRow(r2++, new Label("Max link angle (deg)"), linkAngleSpinner);
        t2.add(linkAngleNote, 0, r2++, 2, 1);
        t2.addRow(r2++, new Label("Min fiber length (" + unitLabel() + ")"), minLenSpinner);
        t2.addRow(r2++, new Label("Fiber mode"), fiberModeBox);
        t2.add(sep(), 0, r2++, 2, 1);
        t2.add(tacsCheck, 0, r2++, 2, 1);
        t2.addRow(r2++, new Label("Boundary annotation"), boundaryBox);
        t2.addRow(r2++, new Label("TACS zone width (" + unitLabel() + ")"), tacsZoneSpinner);
        t2.add(sep(), 0, r2++, 2, 1);
        t2.add(detectionsCheck, 0, r2++, 2, 1);
        t2.add(clearExistingCheck, 0, r2++, 2, 1);

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
        adv.addRow(ar++, new Label("Distance smoothing"), distSmoothSpinner);
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
        Label extraLabel = new Label("Extra params (JSON)");
        extraLabel.setWrapText(true);
        adv.add(extraLabel, 0, ar);
        adv.add(extraParamsArea, 1, ar++);
        javafx.scene.control.TitledPane advPane =
                new javafx.scene.control.TitledPane("Advanced FIRE settings", adv);
        advPane.setExpanded(false);
        t2.add(advPane, 0, r2++, 2, 1);
        t2.setMinWidth(360);

        canvas = new Canvas(PREVIEW_MAX, PREVIEW_MAX * (double) rh / Math.max(1, rw));
        canvasScroll = new ScrollPane(canvas);
        // Both previews are linked: scroll-wheel zooms, click-drag pans, double-click picks
        // the tile "One tile (fast)" runs — so you don't bounce up to the threshold mask.
        wirePreviewMouse(maskCanvas, maskScroll);
        wirePreviewMouse(canvas, canvasScroll);
        canvasScroll.setPrefSize(PREVIEW_MAX + 16, PREVIEW_MAX + 16);
        Label fiberHdr = new Label("Fiber detection preview");
        fiberHdr.setStyle("-fx-font-weight: bold;");
        previewBtn = new Button("Preview fibers  ▶");
        previewBtn.setMaxWidth(Double.MAX_VALUE);
        previewBtn.setOnAction(e -> runPreview());
        Label runNote = new Label("Clicking this runs FIRE on the region (a few seconds): "
                + "'One tile (fast)' does the highlighted tile (double-click the preview above to "
                + "pick a tile; centre by default); 'Whole region' does all tiles + stitch + TACS. "
                + "Scroll to zoom either preview, drag to pan — both stay in sync. "
                + "The ① mask updates instantly; only this step computes fibers.");
        runNote.setWrapText(true);
        runNote.setStyle("-fx-font-size: 11;");
        statusLabel = new Label(String.format(
                "Region %d x %d px at (%d, %d)%s. Click 'Preview fibers' to run detection.",
                rw, rh, rx, ry,
                calibrated ? String.format(" - pixel size %.4f um", pixelSizeMicrons) : " - uncalibrated"));
        statusLabel.setWrapText(true);
        VBox fiberBox = new VBox(4, fiberHdr, canvasScroll, maskTileLabel, previewBtn,
                exportTileBtn, wholeImageCheck, runNote, statusLabel);
        HBox sec2 = new HBox(14, t2, fiberBox);
        javafx.scene.control.TitledPane tp2 =
                new javafx.scene.control.TitledPane("②  Fiber detection", sec2);
        tp2.setCollapsible(false);

        Label hint = new Label(
                "Region = bounding box of your selection. For TACS, select a LARGER region, "
                        + "then pick the tumour annotation (inside it) as the boundary.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11;");

        // --- Parameter tuning (Suggest): visible instructions + options ---
        Label tuneInstr = new Label(
                "Suggest parameters searches the FIBER-DETECTION knobs you CAN'T preview live — "
                + "seed spacing, seed sensitivity, bend angle, link angle. First set the "
                + "background threshold, smoothing and downsample by eye using the ① mask (those "
                + "are held fixed, not searched). Then: 1) select the parent AREA annotation to "
                + "tune inside; 2) trace ALL real fibers inside it as LINE annotations (the search "
                + "targets that total count); 3) click Suggest. Disjoint annotations are pooled; "
                + "only pixels inside the annotation are used. Annotate ≥ ~8 fibers.");
        tuneInstr.setWrapText(true);
        tuneInstr.setStyle("-fx-font-size: 11;");
        countTolSpinner.setMaxWidth(Double.MAX_VALUE);
        maxRoundsSpinner.setMaxWidth(Double.MAX_VALUE);
        GridPane tuneGrid = new GridPane();
        tuneGrid.setHgap(10);
        tuneGrid.setVgap(6);
        tuneGrid.getColumnConstraints().addAll(labelCol(), fieldCol());
        int tr = 0;
        tuneGrid.addRow(tr++, new Label("Count tolerance (±%)"), countTolSpinner);
        tuneGrid.addRow(tr++, new Label("Refinement rounds"), maxRoundsSpinner);
        tuneGrid.addRow(tr++, new Label("Disjoint pieces"), pieceWeightBox);
        VBox tuneBox = new VBox(6, tuneInstr, tuneGrid);
        tuneBox.setPadding(new Insets(6));
        javafx.scene.control.TitledPane tunePane =
                new javafx.scene.control.TitledPane("Parameter tuning (Suggest)", tuneBox);
        tunePane.setExpanded(false);

        suggestBtn = new Button("Suggest parameters…");
        suggestBtn.setMaxWidth(Double.MAX_VALUE);
        suggestBtn.setTooltip(new Tooltip(
                "Grid-search the FIBER-DETECTION parameters (seed spacing, seed sensitivity, bend "
                + "angle, link angle) and rank them. Threshold, smoothing and downsample are held "
                + "fixed at your current values — set those by eye with the ① mask first.\n"
                + "For best results, trace ALL fibers inside the annotation as LINE annotations "
                + "(the search matches that count). With no lines it falls back to coverage + "
                + "straightness.\nTip: keep the region small — every combo is a real FIRE run."));
        suggestBtn.setOnAction(e -> runSuggest());
        addBtn = new Button("Add to image");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> commit(stage));
        Button closeBtn = new Button("Close");
        closeBtn.setMaxWidth(Double.MAX_VALUE);
        closeBtn.setOnAction(e -> stage.close());
        // Keep the dialog above QuPath's main window. JavaFX's alwaysOnTop is the only
        // portable hook and it floats above all windows (not just QuPath), so make it an
        // explicit, off-by-default toggle.
        ToggleButton pinBtn = new ToggleButton("Pin on top");
        pinBtn.setTooltip(new Tooltip(
                "Keep this dialog floating on top so it isn't hidden behind QuPath while you "
                        + "select regions. (Floats above all windows, not only QuPath.)"));
        pinBtn.selectedProperty().addListener((o, ov, nv) -> stage.setAlwaysOnTop(nv));
        HBox bottom = new HBox(8, suggestBtn, addBtn, closeBtn, pinBtn);
        HBox.setHgrow(suggestBtn, Priority.ALWAYS);
        HBox.setHgrow(addBtn, Priority.ALWAYS);
        HBox.setHgrow(closeBtn, Priority.ALWAYS);

        // Documentation links: the full guide (README) and the per-setting reference.
        Hyperlink docLink = new Hyperlink("Guide & install (README)");
        docLink.setOnAction(e -> QuPathGUI.openInBrowser(DOC_README));
        Hyperlink settingsLink = new Hyperlink("Collagen-detection settings reference");
        settingsLink.setOnAction(e -> QuPathGUI.openInBrowser(DOC_SETTINGS));
        Label docPrefix = new Label("Documentation:");
        docPrefix.setStyle("-fx-font-size: 11;");
        HBox docBar = new HBox(4, docPrefix, docLink, new Label("·"), settingsLink);
        docBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox rootBox = new VBox(10, tp1, tp2, hint, tunePane, bottom, docBar);
        rootBox.setPadding(new Insets(10));
        ScrollPane rootScroll = new ScrollPane(rootBox);
        rootScroll.setFitToWidth(true);
        stage.setScene(new Scene(rootScroll));

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
        stage.setOnHidden(e -> {
            hierarchy.removeListener(hierarchyListener);
            hierarchy.getSelectionModel().removePathObjectSelectionListener(selectionListener);
        });

        stage.show();
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
        c.setMinWidth(180);
        c.setPrefWidth(180);
        c.setHalignment(HPos.LEFT);
        return c;
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
        return s;
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
        // fiberMode pref stores the FIRE mode (1/2), not the box index (0=Merged,1=Segments).
        fiberModeBox.getSelectionModel().selectedIndexProperty().addListener(
                (o, ov, nv) -> FiberSocketPreferences.fiberModeProperty().set(nv.intValue() == 1 ? 1 : 2));
        rememberCheck(detectionsCheck, FiberSocketPreferences.createDetectionsProperty());
        rememberCheck(clearExistingCheck, FiberSocketPreferences.clearExistingProperty());
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

    // ---- global intensity stats (native units) for consistent scaling + threshold ----

    /** Read a coarse whole-region sample of the channel and compute its stats. */
    private void computeStats(int channel) {
        try {
            int statDown = Math.max(1, (int) Math.ceil(Math.max(rw, rh) / 256.0));
            RegionRequest req =
                    RegionRequest.createInstance(server.getPath(), statDown, rx, ry, rw, rh);
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
        if (statSample.length == 0) {
            return 0;
        }
        int c = 0;
        for (double v : statSample) {
            if (v > t) {
                c++;
            }
        }
        return (double) c / statSample.length;
    }

    private void updateThresholdFeedback() {
        if (thresholdFeedback == null || thresholdSpinner == null) {
            return;
        }
        double t = thresholdSpinner.getValue();
        thresholdFeedback.setText(String.format(
                "%.1f%% of region above (range %.0f-%.0f)", fractionAbove(t) * 100.0, statMin, statMax));
    }

    private void applyStatsToThresholdSpinner() {
        double step = Math.max((statMax - statMin) / 100.0, 1e-6);
        thresholdSpinner.setValueFactory(
                new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(
                        statMin, statMax, defaultThreshold(), step));
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

    /** Map the native-units threshold onto the global 0-255 scale used by FIRE. */
    private int threshIm2() {
        double range = normHi > normLo ? normHi - normLo : 1.0;
        return clamp((int) Math.round((thresholdSpinner.getValue() - normLo) / range * 255.0), 0, 255);
    }

    private String buildMeta(double downsample, boolean hasMask, JsonObject extra) {
        int threshIm2 = threshIm2();
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
        meta.add("overrides", overrides);
        return GSON.toJson(meta);
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
        final boolean wholeRegion = previewScopeBox.getSelectionModel().getSelectedIndex() == 1;
        setBusy(true, "Reading region + running FIRE...");

        new Thread(() -> {
            try {
                AnalyzeResult res = wholeRegion
                        ? analyzeFull(channel, downsample, tacs, boundary, extra, this::statusAsync)
                        : analyzeOneTile(channel, downsample, extra, this::statusAsync);
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
                fail("Preview failed: " + ex.getMessage());
            }
        }, "tmequant-preview").start();
    }

    private void statusAsync(String msg) {
        setBusy(true, msg);
    }

    /**
     * Turn the run's diagnostics into an actionable sentence explaining a low/zero
     * fiber count: how many FIRE traced, how many the Min-length / Max-width filters
     * removed, whether dense tiles were auto-coarsened or skipped.
     */
    private String diagnostics(AnalyzeResult res) {
        StringBuilder sb = new StringBuilder();
        // The biggest "no fibers" cause: Min length removed most of what FIRE traced.
        if (res.nLengthDropped > 0 && res.nTraced > 0) {
            // res.minLengthPx is in ANALYSED px; show it in the user's units.
            String unitMsg = calibrated
                    ? String.format("%.0f µm", res.minLengthPx * lastDownsample * pixelSizeMicrons)
                    : String.format("%.0f px", res.minLengthPx * lastDownsample);
            sb.append(String.format(" FIRE traced %d; %d were shorter than Min length (%s)",
                    res.nTraced, res.nLengthDropped, unitMsg));
            if (res.nLengthDropped >= res.nTraced * 0.5) {
                sb.append(" — lower Min fiber length to keep more");
            }
            sb.append('.');
        }
        if (res.nWidthDropped > 0) {
            sb.append(String.format(" %d removed by Max fiber width.", res.nWidthDropped));
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
        final int inRoiPx;
        Piece(ROI roi, int px, int py, int pw, int ph, double searchDown,
                BufferedImage maskedGray, JsonArray gt, int inRoiPx) {
            this.roi = roi; this.px = px; this.py = py; this.pw = pw; this.ph = ph;
            this.searchDown = searchDown; this.maskedGray = maskedGray; this.gt = gt;
            this.inRoiPx = inRoiPx;
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
        final List<ROI> pieceRois = splitContiguous(selectedRoi);
        if (pieceRois.isEmpty()) {
            Dialogs.showErrorMessage(TITLE,
                    "Select an area annotation in QuPath to tune inside, then click Suggest again.");
            return;
        }
        // --- size guard (synchronous, before any server work) ---
        // Each FIRE run is fast on a small masked region, so the guard is mostly about region
        // size and piece count; the broad coarse round is up to ~4 levels on 4 axes.
        final int rounds = maxRoundsSpinner.getValue();
        final int combos = 4 * 4 * 4 * 4; // coarse round upper bound: 4 axes × ~4 levels
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
                Platform.runLater(() -> {
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
        int px = (int) Math.floor(roi.getBoundsX());
        int py = (int) Math.floor(roi.getBoundsY());
        int pw = (int) Math.ceil(roi.getBoundsWidth());
        int ph = (int) Math.ceil(roi.getBoundsHeight());
        if (pw <= 0 || ph <= 0) {
            return null;
        }
        double sd = capSearchDown(pw, ph);
        BufferedImage region = server.readRegion(
                RegionRequest.createInstance(server.getPath(), sd, px, py, pw, ph));
        if (region == null) {
            return null;
        }
        BufferedImage gray = ChannelExtractor.toGray(region, channel, normLo, normHi);
        BufferedImage mask = MaskRasterizer.rasterise(roi, px, py, sd, gray.getWidth(), gray.getHeight());
        int inRoi = applyMask(gray, mask);
        if (inRoi <= 0) {
            return null;
        }
        JsonArray gt = collectGroundTruthLines(roi, px, py, sd);
        return new Piece(roi, px, py, pw, ph, sd, gray, gt, inRoi);
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
        int[] seeds = uniqInts(2, 4, 8, 16, Math.max(2, sc));
        double[] lmps = uniqDoubles(0.05, 0.15, 0.30, clampD(seedSensSpinner.getValue(), 0.02, 0.9));
        double[] bends = uniqDoubles(45, 65, 85, clampD(bendAngleSpinner.getValue(), 5, 89));
        double[] links = uniqDoubles(15, 35, 60, clampD(linkAngleSpinner.getValue(), 0, 89));

        logger.info("SUGGEST start: pieces={} supervised={} | FIXED thr_im2={} sigma_im={} "
                + "downsample={} minLen={}{} | countTol={}% maxRounds={}",
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
            int nCombos = seeds.length * lmps.length * bends.length * links.length;
            totalRuns += nCombos * pieces.size();
            statusAsync(String.format("Tuning round %d/%d — %d combos…", round + 1, maxRounds, nCombos));
            logger.info("SUGGEST round {}/{}: {} combos/piece | seeds(px)={} sens={} bend°={} link°={}",
                    round + 1, maxRounds, nCombos, java.util.Arrays.toString(seeds),
                    java.util.Arrays.toString(lmps), java.util.Arrays.toString(bends),
                    java.util.Arrays.toString(links));
            List<AggResult> agg = aggregatedSearch(pieces, seeds, lmps, bends, links, supervised, countTol);
            if (agg.isEmpty()) {
                logger.warn("SUGGEST round {}: no results (all combos failed)", round + 1);
                break;
            }
            // Accumulate the best score per distinct combo across ALL rounds, so the chooser
            // shows the strongest sets from the entire search — not just the last round.
            for (AggResult ar : agg) {
                String key = comboKey(ar.overrides);
                AggResult cur = globalBest.get(key);
                if (cur == null || ar.score > cur.score) {
                    globalBest.put(key, ar);
                }
            }
            AggResult best = agg.get(0);
            logger.info("SUGGEST round {} best: score={} nDet={}{} | {}", round + 1,
                    String.format("%.3f", best.score), best.nDetected,
                    supervised ? ("/" + best.nGt + " recall=" + String.format("%.2f", best.recall)) : "",
                    describeParams(best.overrides));
            int bestSeed = (int) Math.round(best.overrides.getOrDefault("thresh_LMPdist", (double) sc));
            double bestLmp = best.overrides.getOrDefault("thresh_LMP", 0.15);
            double bestBend = cosToExt(best.overrides.getOrDefault("thresh_ext", extToCos(65)));
            double bestLink = cosToLinka(best.overrides.getOrDefault("thresh_linka", linkaToCos(35)));
            if (prevBest != null && best.score <= prevBest.score + 1e-4) {
                logger.info("SUGGEST converged at round {} (no score improvement)", round + 1);
                break;
            }
            prevBest = best;
            // Re-centre a finer grid on the best, shrinking the span each round.
            int sStep = Math.max(1, (int) Math.round(bestSeed * (0.4 / (round + 1))));
            double bStep = Math.max(4, 18.0 / (round + 1));
            double kStep = Math.max(4, 18.0 / (round + 1));
            double lStep = Math.max(0.03, 0.1 / (round + 1));
            seeds = uniqInts(Math.max(2, bestSeed - sStep), bestSeed, bestSeed + sStep,
                    Math.max(2, (int) Math.round(bestSeed / 2.0)));
            lmps = uniqDoubles(clampD(bestLmp - lStep, 0.02, 0.9), bestLmp,
                    clampD(bestLmp + lStep, 0.02, 0.9));
            bends = uniqDoubles(clampD(bestBend - bStep, 5, 89), bestBend,
                    clampD(bestBend + bStep, 5, 89));
            links = uniqDoubles(clampD(bestLink - kStep, 0, 89), bestLink,
                    clampD(bestLink + kStep, 0, 89));
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
        return String.format("seed=%d sens=%.2f bend=%.0f° link=%.0f°",
                (int) Math.round(o.getOrDefault("thresh_LMPdist", 0.0)),
                o.getOrDefault("thresh_LMP", 0.0),
                cosToExt(o.getOrDefault("thresh_ext", 1.0)),
                cosToLinka(o.getOrDefault("thresh_linka", -1.0)));
    }

    /** Run one grid across every piece and pool the per-combo results into the union. */
    private List<AggResult> aggregatedSearch(List<Piece> pieces, int[] seeds, double[] lmps,
            double[] bends, double[] links, boolean supervised, double countTol) throws Exception {
        boolean areaWeight = pieceWeightBox.getSelectionModel().getSelectedIndex() == 1;
        FiberSocketClient client = new FiberSocketClient(
                FiberSocketPreferences.getHost(), FiberSocketPreferences.getPort(), 3000, 600000);
        // key -> [nDet, nGt, matched, orientErr*w, lenFactor*w, wSum, score*w, coverage*w, straight*w]
        java.util.Map<String, double[]> acc = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Map<String, Double>> ovById = new java.util.LinkedHashMap<>();
        for (Piece p : pieces) {
            String meta = buildGridMeta(p.searchDown, p.gt, seeds, lmps, bends, links,
                    supervised, countTol);
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
        final double recallFloor = 0.5;
        int ngt = Math.max(1, ar.nGt);
        double countRatio = (double) ar.nDetected / ngt;
        ar.countRatio = countRatio;
        double dev = Math.abs(countRatio - 1.0);
        double countScore = dev <= countTol ? 1.0
                : Math.exp(-(dev - countTol) * (dev - countTol) / (2.0 * countSigma * countSigma));
        double recall = (double) ar.matched / ngt;
        ar.recall = recall;
        double guard = recallFloor > 0 ? Math.min(1.0, recall / recallFloor) : 1.0;
        double orientFactor = 1.0 - 0.5 * (ar.orientErr / 90.0);
        ar.score = countScore * guard * (0.6 + 0.25 * ar.lenFactor + 0.15 * orientFactor);
    }

    /** Stable key over the four detection axes (base overrides are identical across pieces). */
    private static String comboKey(java.util.Map<String, Double> o) {
        return Math.round(o.getOrDefault("thresh_LMPdist", 0.0)) + "_"
                + String.format("%.3f", o.getOrDefault("thresh_LMP", 0.0)) + "_"
                + String.format("%.3f", o.getOrDefault("thresh_ext", 0.0)) + "_"
                + String.format("%.3f", o.getOrDefault("thresh_linka", 0.0));
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
        JsonArray arr = new JsonArray();
        ROI bnd = tacsCheck.isSelected() ? boundaryRoi() : null;
        for (PathObject o : hierarchy.getAnnotationObjects()) {
            ROI r = o.getROI();
            if (r == null || !r.isLine() || r == selectedRoi || r == bnd) {
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
            int[] seeds, double[] lmps, double[] bends, double[] links,
            boolean supervised, double countTol) {
        int ll1 = Math.max(1, (int) Math.round(toAnalysedPx(minLenSpinner.getValue(), searchDown)));

        JsonObject base = new JsonObject(); // fixed knobs (incl. the user-set, previewable ones)
        base.addProperty("LL1", ll1);
        base.addProperty("thresh_im2", threshIm2());           // FIXED — set by eye via the mask
        base.addProperty("sigma_im", smoothingSpinner.getValue()); // FIXED — set by eye via the mask
        base.addProperty("s_xlinkbox", xlinkBoxAnalysedPx(searchDown));
        base.addProperty("sigma_d", distSmoothSpinner.getValue());
        base.addProperty("thresh_dang_aextend", extToCos(angleExtendSpinner.getValue()));
        base.addProperty("thresh_linkd", linkDistAnalysedPx(searchDown));
        base.addProperty("widMAX", maxWidthAnalysedPx(searchDown));
        base.addProperty("thresh_Dxlink", dxlinkSpinner.getValue());
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
        JsonObject grid = new JsonObject();
        grid.add("thresh_LMPdist", seedA);
        grid.add("thresh_LMP", lmpA);
        grid.add("thresh_ext", extA);
        grid.add("thresh_linka", linkA);

        JsonObject meta = new JsonObject();
        meta.addProperty("prescaled", true);
        meta.add("base_overrides", base);
        meta.add("grid", grid);
        meta.add("gt_lines", gtLines);
        meta.addProperty("match_dist", Math.max(8.0, ll1 * 0.6));
        meta.addProperty("match_angle", 20.0);
        // Cover the whole grid so no combo is silently dropped by the server's cap.
        meta.addProperty("max_combos", seedA.size() * lmpA.size() * extA.size() * linkA.size());
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
     * dialog immediately (live mask update); "Preview selected" traces fibres for it. Stays open
     * so you can flip through options and compare.
     */
    private void showGridResults(List<AggResult> results, boolean supervised, List<Piece> pieces) {
        Stage dlg = new Stage();
        dlg.initModality(Modality.NONE); // non-modal so the main preview stays usable
        dlg.setTitle("Suggested parameters");

        ToggleGroup group = new ToggleGroup();
        VBox list = new VBox(6);
        list.setPadding(new Insets(10));
        int show = Math.min(results.size(), 10);
        for (int i = 0; i < show; i++) {
            AggResult gr = results.get(i);
            RadioButton rb = new RadioButton(describeResult(i + 1, gr, supervised));
            rb.setToggleGroup(group);
            rb.setUserData(gr);
            list.getChildren().add(rb);
        }
        // Selecting a row loads its parameters into the dialog (mask updates live), so the
        // selection visibly "does something" without a server round-trip.
        group.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv != null && nv.getUserData() instanceof AggResult) {
                applyOverrides(((AggResult) nv.getUserData()).overrides);
            }
        });

        Label note = new Label(supervised
                ? "Scored to match your annotated fibre COUNT (length + orientation secondary). "
                  + "Rows in the top band (★) are all acceptable. Selecting a row loads its "
                  + "parameters here (mask updates live); 'Preview selected' traces its fibres. "
                  + "This window stays open — compare options, then keep the one you like."
                : "No line annotations found — scored by foreground coverage + median straightness. "
                  + "Trace ALL fibers inside the annotation and re-run for a count-matched suggestion.");
        note.setWrapText(true);
        note.setStyle("-fx-font-size: 11;");
        note.setPadding(new Insets(8, 10, 0, 10));

        Button preview = new Button("Preview selected  ▶");
        preview.setDefaultButton(true);
        preview.setTooltip(new Tooltip("Load the selected row's parameters and trace fibres for "
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
        save.setTooltip(new Tooltip(
                "Append a row (this area's best params + metrics) to a TSV file, so you can tune "
                + "several areas and compare them."));
        save.setOnAction(e -> exportSuggestMeasurements(results, pieces, supervised));
        Button close = new Button("Close");
        close.setOnAction(e -> dlg.close());
        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8, preview, save, close);
        buttons.setPadding(new Insets(10));

        Label header = new Label(String.format(
                "  Ranked parameter sets — evaluated %d distinct set(s) in %d FIRE run(s); "
                + "showing top %d:", suggestEvaluated, suggestTotalRuns, show));
        header.setStyle("-fx-font-weight: bold;");
        VBox root = new VBox(4, header, new ScrollPane(list), note, buttons);
        dlg.setScene(new Scene(root));
        dlg.show();
        // Select the best row last, so the listener applies it once everything exists.
        if (!list.getChildren().isEmpty()) {
            ((RadioButton) list.getChildren().get(0)).setSelected(true);
        }
    }

    private String describeResult(int rank, AggResult gr, boolean supervised) {
        java.util.Map<String, Double> o = gr.overrides;
        String params = String.format("seed=%d, sens=%.2f, bend=%.0f°, link=%.0f°",
                (int) Math.round(o.getOrDefault("thresh_LMPdist", 0.0)),
                o.getOrDefault("thresh_LMP", 0.0),
                cosToExt(o.getOrDefault("thresh_ext", 1.0)),
                cosToLinka(o.getOrDefault("thresh_linka", -1.0)));
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
            um = clampD(um, 1.0, 200.0);
            seedSpacingSpinner.getValueFactory().setValue(um);
        }
        if (o.containsKey("thresh_LMP")) {
            seedSensSpinner.getValueFactory().setValue(clampD(o.get("thresh_LMP"), 0.0, 1.0));
        }
        if (o.containsKey("thresh_ext")) {
            bendAngleSpinner.getValueFactory().setValue(clampD(cosToExt(o.get("thresh_ext")), 5.0, 89.0));
        }
        if (o.containsKey("thresh_linka")) {
            linkAngleSpinner.getValueFactory().setValue(clampD(cosToLinka(o.get("thresh_linka")), 0.0, 89.0));
        }
        updateThresholdFeedback();
        redrawPreview();
        setBusy(false, String.format(
                "Applied: seed=%d, sens=%.2f, bend=%.0f°, link=%.0f°. Click Preview to see it.",
                (int) Math.round(o.getOrDefault("thresh_LMPdist", 0.0)),
                o.getOrDefault("thresh_LMP", 0.0),
                cosToExt(o.getOrDefault("thresh_ext", 1.0)),
                cosToLinka(o.getOrDefault("thresh_linka", -1.0))));
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
        BufferedImage gray = ChannelExtractor.toGray(region, channel, normLo, normHi);
        FiberSocketClient.AnalyzeResponse resp = client.analyze(gray, detMeta, null);
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

    /** Full tiled detection + global post-process (dedup/stitch/TACS). */
    private AnalyzeResult analyzeFull(
            int channel, double d, boolean tacs, ROI boundary, JsonObject extra,
            java.util.function.Consumer<String> status) throws Exception {
        int rwA = (int) Math.ceil(rw / d);
        int rhA = (int) Math.ceil(rh / d);
        List<Tile> tiles = computeTiles(rwA, rhA, d);
        String detMeta = buildMeta(d, false, extra); // detection never sends a mask
        logger.info("Analyze WHOLE region: ch={} downsample={} threshold={}(im2={}) region={}x{}@({},{}) "
                + "-> {} analysed px -> {} tile(s), tacs={}",
                channel, d, thresholdSpinner.getValue(), threshIm2(), rw, rh, rx, ry,
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
        for (int t = 0; t < tiles.size(); t++) {
            status.accept(String.format("Detecting - tile %d/%d...", t + 1, tiles.size()));
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
        }
        List<FiberSocketClient.Fiber> finalFibers = all;
        boolean needPost = tiles.size() > 1 || tacs;
        if (needPost && !all.isEmpty()) {
            status.accept(tacs ? "Stitching seams + classifying TACS..." : "Stitching seams...");
            BufferedImage mask = tacs ? MaskRasterizer.rasterise(boundary, rx, ry, d, rwA, rhA) : null;
            String ppMeta = buildPostMeta(all, d, tacs, tiles.size() > 1);
            FiberSocketClient.AnalyzeResponse pr = client.postprocess(ppMeta, mask);
            if (pr != null && pr.ok && pr.fibers != null) {
                finalFibers = pr.fibers;
                logger.info("Post-process: {} raw -> {} fibers (stitch={}, tacs={})",
                        all.size(), finalFibers.size(), tiles.size() > 1, tacs);
            } else {
                logger.warn("Post-process failed: {}", pr == null ? "no response" : pr.error);
            }
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

    /** Quick preview: detect only the chosen tile (centre by default; no stitch / TACS). */
    private AnalyzeResult analyzeOneTile(
            int channel, double d, JsonObject extra, java.util.function.Consumer<String> status)
            throws Exception {
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

    private String buildPostMeta(
            List<FiberSocketClient.Fiber> fibers, double d, boolean tacs, boolean stitch) {
        JsonObject m = new JsonObject();
        m.add("fibers", GSON.toJsonTree(fibers));
        m.addProperty("stitch", stitch);
        m.addProperty("has_mask", tacs);
        m.addProperty("distance_threshold", toAnalysedPx(tacsZoneSpinner.getValue(), d));
        return GSON.toJson(m);
    }

    private String settingsSignature() {
        return String.join("|",
                String.valueOf(chosenChannel()),
                String.valueOf(downsampleSpinner.getValue()),
                String.valueOf(thresholdSpinner.getValue()),
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
                extraParamsArea.getText() == null ? "" : extraParamsArea.getText());
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
        // Cap total tiles (grow tiles rather than exceed the cap).
        while ((long) nCols * nRows > MAX_TILES) {
            if (nCols >= nRows && nCols > 1) {
                nCols--;
            } else if (nRows > 1) {
                nRows--;
            } else {
                break;
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
        final int channel = chosenChannel();
        final double downsample = downsampleSpinner.getValue();
        final int gen = ++bgGen;
        new Thread(() -> {
            try {
                int rwA = (int) Math.ceil(rw / downsample);
                int rhA = (int) Math.ceil(rh / downsample);
                int bgFactor = Math.max(1, (int) Math.ceil(Math.max(rwA, rhA) / 1600.0));
                double bgDown = downsample * bgFactor;
                BufferedImage bgRegion = server.readRegion(
                        RegionRequest.createInstance(server.getPath(), bgDown, rx, ry, rw, rh));
                BufferedImage bgGray = ChannelExtractor.toGray(bgRegion, channel, normLo, normHi);
                var fxImg = ChannelExtractor.toFxImage(bgGray);
                int gw = bgGray.getWidth();
                int gh = bgGray.getHeight();
                double bgRatio = 1.0 / bgFactor;
                Platform.runLater(() -> {
                    if (gen != bgGen) {
                        return; // a newer read superseded this one
                    }
                    lastBgGray = bgGray;
                    lastBgFx = fxImg;
                    lastBgW = gw;
                    lastBgH = gh;
                    lastBgRatio = bgRatio;
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
        var ov = buildThresholdOverlay();
        if (ov != null) {
            gc.drawImage(ov, 0, 0, cw, ch);
        }
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
        double s = scale * lastBgRatio;
        boolean oneTile = previewScopeBox.getSelectionModel().getSelectedIndex() == 0;
        int sel = effectivePreviewTile(tiles.size()); // the picked tile (or centre)
        gc.setLineWidth(1.0);
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            double x = t.coreX0 * s;
            double y = t.coreY0 * s;
            double w = (t.coreX1 - t.coreX0) * s;
            double h = (t.coreY1 - t.coreY0) * s;
            if (i == sel) {
                gc.setStroke(Color.YELLOW);
                gc.setLineWidth(2.0);
                gc.strokeRect(x, y, w, h);
                gc.setFill(Color.color(1, 1, 0, 0.12));
                gc.fillRect(x, y, w, h);
                gc.setLineWidth(1.0);
            } else {
                gc.setStroke(Color.color(0.3, 0.6, 1.0, 0.7));
                gc.strokeRect(x, y, w, h);
            }
        }
        if (maskTileLabel != null) {
            maskTileLabel.setText(String.format(
                    "%d tiles — double-click to pick a tile (tile %d selected). %s 'Export tile' saves it.",
                    tiles.size(), sel + 1,
                    oneTile ? "'Preview fibers' analyses it;" : "'Whole region' previews all;"));
        }
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
                redrawFibers();
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
            BufferedImage gray = ChannelExtractor.toGray(region, channel, normLo, normHi);

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
        if (lastBgGray == null) {
            return null;
        }
        int w = lastBgGray.getWidth();
        int h = lastBgGray.getHeight();
        int[] s = lastBgGray.getRaster().getSamples(0, 0, w, h, 0, (int[]) null);
        // sigma_im is in analysis px; the background is coarser by 1/lastBgRatio, so
        // the equivalent blur on the background image is sigma_im * lastBgRatio.
        double sigmaBg = smoothingSpinner.getValue() * lastBgRatio;
        if (sigmaBg > 0.05) {
            gaussianBlur(s, w, h, sigmaBg);
        }
        int thr = threshIm2();
        javafx.scene.image.WritableImage img = new javafx.scene.image.WritableImage(w, h);
        javafx.scene.image.PixelWriter pw = img.getPixelWriter();
        int fg = 0x70FF3030; // semi-transparent red ARGB
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
        java.util.Set<String> targetClasses = java.util.Set.of("Fiber", "TACS-1", "TACS-2", "TACS-3");
        List<PathObject> toRemove = new java.util.ArrayList<>();
        List<PathObject> candidates = new java.util.ArrayList<>();
        candidates.addAll(hierarchy.getAnnotationObjects());
        candidates.addAll(hierarchy.getDetectionObjects());
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
            hierarchy.removeObjects(toRemove, false);
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

        new Thread(() -> {
            try {
                final List<FiberSocketClient.Fiber> fibers;
                final double d;
                if (canReuse) {
                    fibers = lastFibers;
                    d = lastDownsample;
                } else {
                    AnalyzeResult res = analyzeFull(channel, downsample, tacs, boundary, extra, this::statusAsync);
                    fibers = res.fibers;
                    d = downsample;
                }
                Platform.runLater(() -> {
                    boolean asDetections = detectionsCheck.isSelected();
                    FiberSocketPreferences.createDetectionsProperty().set(asDetections);
                    ImagePlane plane =
                            selectedRoi != null ? selectedRoi.getImagePlane() : ImagePlane.getDefaultPlane();
                    PathClass fiberClass = PathClass.fromString("Fiber", ColorTools.packRGB(0, 220, 0));
                    List<PathObject> objects = FiberObjectBuilder.build(
                            fibers, rx, ry, d, pixelSizeMicrons, plane, asDetections, fiberClass);
                    // Optionally clear prior Fiber/TACS objects in this region first, so
                    // repeated runs don't stack overlapping objects.
                    int removed = 0;
                    if (clearExistingCheck.isSelected()) {
                        removed = removeExistingFiberObjects(rx, ry, rw, rh);
                    }
                    logger.info("Add to image: removed {} prior, committing {} {} (reuse={}, regionChanged={})",
                            removed, objects.size(), asDetections ? "detections" : "annotations",
                            canReuse, regionChanged);
                    if (!objects.isEmpty()) {
                        hierarchy.addObjects(objects);
                        hierarchy.getSelectionModel().clearSelection();
                    }
                    String msg = objects.size() + " fibers added"
                            + (removed > 0 ? " (" + removed + " prior removed)" : "") + ".";
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
                logger.error("Add to image failed", ex);
                fail("Add to image failed: " + ex.getMessage());
            }
        }, "tmequant-commit").start();
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
