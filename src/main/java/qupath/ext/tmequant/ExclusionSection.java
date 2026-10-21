package qupath.ext.tmequant;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

/**
 * Stage ⓪ of the dialog: regions that fibre tracing must leave alone.
 *
 * <p>The driving case is nuclei. Nuclei are strongly birefringent, so on a nucleus-rich field a
 * third of what the threshold calls "collagen" is actually nuclei and ~19% of traced fibres are
 * short arcs sitting on one. QuPath can segment those nuclei from the paired brightfield image;
 * this section turns them (or any other objects) into a "don't trace here" mask.</p>
 *
 * <p>Two sources, because both workflows are real:</p>
 * <ul>
 *   <li><b>This image</b> — objects already in the hierarchy. Nothing to export or re-import.</li>
 *   <li><b>GeoJSON file</b> — for objects segmented on a DIFFERENT image, e.g. the brightfield
 *       partner of a birefringence image. Read straight from the file, so a 350 MB cell export
 *       never has to become 123k QuPath objects.</li>
 * </ul>
 *
 * <p>The mask is applied to the traced geometry, not by deleting pixels: measured on real data,
 * 6 of the 7 longest fibres in a nucleus-rich field pass THROUGH a nucleus while sitting only ~6%
 * inside one, so erasing would chop the best fibres to remove the junk. See the server's
 * {@code _apply_exclusion}.</p>
 */
public final class ExclusionSection extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(ExclusionSection.class);

    private static final String ANY_CLASS = "Any class";
    private static final String ALL_DETECTIONS = "All detections";
    private static final String ALL_ANNOTATIONS = "All annotations";
    private static final String ALL_OBJECTS = "All objects";

    private final CheckBox enableCheck;
    private final RadioButton srcHierarchy;
    private final RadioButton srcFile;
    private final ComboBox<String> objectTypeBox;
    private final ComboBox<String> classBox;
    private final TextField fileField;
    private final Button browseBtn;
    private final Button loadBtn;
    private final ComboBox<String> roiBox;
    private final Spinner<Double> dilateSpinner;   // user unit: µm, or full-res px when uncalibrated
    private final Label dilateLabel = new Label("Grow regions (µm)");
    /** Whether the current image has a pixel size; picks the unit {@link #dilateSpinner} is in. */
    private boolean calibrated = true;
    private final Spinner<Double> maxFracSpinner;
    private final CheckBox trimCheck;
    private final CheckBox eraseCheck;
    private final Label statusLabel;

    /** The loaded regions, or null until the user loads a source. */
    private ExclusionMask mask;
    /** Set when the loaded mask no longer matches the current settings. */
    private final BooleanProperty stale = new SimpleBooleanProperty(true);

    private ImageData<BufferedImage> imageData;
    private final Window owner;
    /** Where Browse should start when the field is empty — the project folder; may return null. */
    private final java.util.function.Supplier<java.io.File> projectDir;
    private java.io.File lastBrowseDir;

    public ExclusionSection(Window owner) {
        this(owner, () -> null);
    }

    public ExclusionSection(Window owner, java.util.function.Supplier<java.io.File> projectDir) {
        super(6);
        this.owner = owner;
        this.projectDir = projectDir == null ? () -> null : projectDir;
        setPadding(new Insets(8));

        enableCheck = new CheckBox("Exclude regions from fibre tracing");
        enableCheck.setTooltip(ttip(
                "Off by default. When on, fibres are not reported inside the regions you choose "
                + "below — typically nuclei segmented from the paired brightfield image, which are "
                + "birefringent and get traced as short false 'circles'."));

        ToggleGroup srcGroup = new ToggleGroup();
        srcHierarchy = new RadioButton("Objects in this image");
        srcFile = new RadioButton("GeoJSON file");
        srcHierarchy.setToggleGroup(srcGroup);
        srcFile.setToggleGroup(srcGroup);
        srcHierarchy.setSelected(true);
        srcHierarchy.setTooltip(ttip(
                "Use detections or annotations already present in the current image — no export "
                + "and re-import needed. This is the usual choice when the nuclei were segmented "
                + "on this same image."));
        srcFile.setTooltip(ttip(
                "Read the regions from a GeoJSON file exported from QuPath. Use this when the "
                + "objects live in a DIFFERENT image — e.g. nuclei segmented on the brightfield "
                + "partner of this birefringence image. The file is streamed, so even a "
                + "hundreds-of-MB cell export loads in seconds and never becomes QuPath objects."));

        objectTypeBox = new ComboBox<>();
        objectTypeBox.getItems().addAll(ALL_DETECTIONS, ALL_ANNOTATIONS, ALL_OBJECTS);
        objectTypeBox.setValue(ALL_DETECTIONS);
        objectTypeBox.setTooltip(ttip("Which objects in this image to take as excluded regions."));

        classBox = new ComboBox<>();
        classBox.getItems().add(ANY_CLASS);
        classBox.setValue(ANY_CLASS);
        classBox.setTooltip(ttip(
                "Restrict to one classification, so you can exclude (say) only objects classed as "
                + "Tumor or Debris and leave the rest traceable."));

        fileField = new TextField();
        fileField.setPromptText("…/cells.geojson");
        fileField.setPrefColumnCount(28);
        HBox.setHgrow(fileField, Priority.ALWAYS);
        browseBtn = new Button("Browse…");
        browseBtn.setOnAction(e -> browse());

        roiBox = new ComboBox<>();
        roiBox.getItems().addAll("Nucleus (if the object has one)", "Whole object");
        roiBox.setValue("Nucleus (if the object has one)");
        roiBox.setTooltip(ttip(
                "Cell detections carry both a cell boundary and a nucleus outline. The NUCLEUS is "
                + "what is birefringent, so that is the right choice here; 'Whole object' excludes "
                + "the larger cell boundary and will mask more of the image."));

        dilateSpinner = new Spinner<>();
        dilateSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 20, 1.0, 0.5));
        dilateSpinner.setEditable(true);
        dilateSpinner.setPrefWidth(90);
        dilateSpinner.setTooltip(ttip(
                "Grow each region by this much, in µm (full-resolution pixels on an uncalibrated "
                + "image), before masking. Segmentation outlines often "
                + "sit just INSIDE the bright object, leaving a rim that still gets traced. HIGHER "
                + "catches more of that rim but masks more real collagen; 0 uses the outline as-is."));

        maxFracSpinner = new Spinner<>();
        maxFracSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 100, 50, 5));
        maxFracSpinner.setEditable(true);
        maxFracSpinner.setPrefWidth(90);
        maxFracSpinner.setTooltip(ttip(
                "Drop a fibre when at least this much of its length lies inside an excluded region "
                + "— such a fibre IS the excluded object rather than collagen crossing it. LOWER is "
                + "more aggressive and starts removing genuine fibres that merely pass through a "
                + "nucleus; 0 turns the rule off and only trimming applies."));

        trimCheck = new CheckBox("Trim fibre ends that sit inside an excluded region");
        trimCheck.setSelected(true);
        trimCheck.setTooltip(ttip(
                "Peel back the START and END of a fibre while it lies inside an excluded region, "
                + "keeping the part on real collagen. This removes the spokes that radiate out of a "
                + "nucleus without discarding the fibre they run into. The MIDDLE is never trimmed, "
                + "so a fibre crossing a nucleus stays whole."));

        eraseCheck = new CheckBox("Also erase excluded regions before tracing (cuts crossing fibres)");
        eraseCheck.setTooltip(ttip(
                "Removes the excluded pixels from the image so they can never be traced, and stops "
                + "them inflating the density/seed guards (on a nucleus-rich field ~34% of the "
                + "thresholded 'collagen' was nuclei, which drove the guard to raise the threshold "
                + "and under-detect real collagen). The cost: a fibre CROSSING an excluded object "
                + "is cut in two. Off by default — try it only if the guards are misfiring."));

        loadBtn = new Button("Load regions");
        loadBtn.setOnAction(e -> load());
        loadBtn.setTooltip(ttip("Read the chosen source and report how many regions it holds. "
                + "Runs automatically before an analysis if you have not clicked it."));

        statusLabel = new Label("No regions loaded.");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(520);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(6);
        g.setPadding(new Insets(4, 0, 0, 18));
        int r = 0;
        g.add(srcHierarchy, 0, r);
        g.add(new HBox(8, objectTypeBox, classBox), 1, r++);
        g.add(srcFile, 0, r);
        HBox fileRow = new HBox(6, fileField, browseBtn);
        g.add(fileRow, 1, r++);
        g.add(new Label("Region to exclude"), 0, r);
        g.add(roiBox, 1, r++);
        g.add(dilateLabel, 0, r);
        g.add(dilateSpinner, 1, r++);
        g.add(new Label("Drop fibre if ≥ (% inside)"), 0, r);
        g.add(maxFracSpinner, 1, r++);
        g.add(trimCheck, 0, r++, 2, 1);
        g.add(eraseCheck, 0, r++, 2, 1);
        g.add(new HBox(8, loadBtn), 0, r++, 2, 1);
        g.add(statusLabel, 0, r, 2, 1);

        // Persist every control here: an exclusion source (a nuclei GeoJSON, a classification) is a
        // way of working rather than a property of one slide, so it should survive a restart and
        // follow the user from image to image. The loaded REGIONS are deliberately not cached --
        // they belong to the image they were read for.
        restorePrefs();

        getChildren().addAll(enableCheck, g);
        g.disableProperty().bind(enableCheck.selectedProperty().not());

        // Only one source's controls are live at a time, so it is obvious which one will be used.
        objectTypeBox.disableProperty().bind(srcHierarchy.selectedProperty().not());
        classBox.disableProperty().bind(srcHierarchy.selectedProperty().not());
        fileField.disableProperty().bind(srcFile.selectedProperty().not());
        browseBtn.disableProperty().bind(srcFile.selectedProperty().not());

        // Any change to WHAT is loaded invalidates the cached regions (but the tuning spinners
        // below — dilate/maxFrac/trim/erase — are applied per tile, so they do not).
        Runnable invalidate = () -> {
            mask = null;
            stale.set(true);
            statusLabel.setText("Source changed — click \"Load regions\".");
        };
        srcHierarchy.selectedProperty().addListener((o, a, b) -> invalidate.run());
        objectTypeBox.valueProperty().addListener((o, a, b) -> invalidate.run());
        classBox.valueProperty().addListener((o, a, b) -> invalidate.run());
        fileField.textProperty().addListener((o, a, b) -> invalidate.run());
        roiBox.valueProperty().addListener((o, a, b) -> invalidate.run());
    }

    /** Restore every control from its persistent preference, then write changes back. */
    private void restorePrefs() {
        enableCheck.setSelected(FiberSocketPreferences.exclEnabledProperty().get());
        enableCheck.selectedProperty().addListener(
                (o, a, b) -> FiberSocketPreferences.exclEnabledProperty().set(b));

        boolean fromFile = FiberSocketPreferences.exclFromFileProperty().get();
        srcFile.setSelected(fromFile);
        srcHierarchy.setSelected(!fromFile);
        srcFile.selectedProperty().addListener(
                (o, a, b) -> FiberSocketPreferences.exclFromFileProperty().set(b));

        String f = FiberSocketPreferences.exclFileProperty().get();
        if (f != null && !f.isBlank()) {
            fileField.setText(f);
        }
        fileField.textProperty().addListener(
                (o, a, b) -> FiberSocketPreferences.exclFileProperty().set(b == null ? "" : b));

        String ob = FiberSocketPreferences.exclObjectsProperty().get();
        if (ob != null && objectTypeBox.getItems().contains(ob)) {
            objectTypeBox.setValue(ob);
        }
        objectTypeBox.valueProperty().addListener(
                (o, a, b) -> FiberSocketPreferences.exclObjectsProperty().set(b == null ? "" : b));

        String roi = FiberSocketPreferences.exclRoiProperty().get();
        if (roi != null && roiBox.getItems().contains(roi)) {
            roiBox.setValue(roi);
        }
        roiBox.valueProperty().addListener(
                (o, a, b) -> FiberSocketPreferences.exclRoiProperty().set(b == null ? "" : b));

        // Stored per unit, re-read when the image's calibration changes (see setImageData).
        loadDilate();
        dilateSpinner.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                FiberSocketPreferences.exclDilateProperty().forUnit(calibrated).set(b);
            }
        });
        setSpinner(maxFracSpinner, FiberSocketPreferences.exclMaxFracProperty());

        trimCheck.setSelected(FiberSocketPreferences.exclTrimProperty().get());
        trimCheck.selectedProperty().addListener(
                (o, a, b) -> FiberSocketPreferences.exclTrimProperty().set(b));
        eraseCheck.setSelected(FiberSocketPreferences.exclEraseProperty().get());
        eraseCheck.selectedProperty().addListener(
                (o, a, b) -> FiberSocketPreferences.exclEraseProperty().set(b));
    }

    private static void setSpinner(Spinner<Double> sp, javafx.beans.property.DoubleProperty pref) {
        try {
            sp.getValueFactory().setValue(pref.get());
        } catch (RuntimeException ex) {
            // stored value outside this spinner's range -- keep the built-in default
        }
        sp.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                pref.set(b);
            }
        });
    }

    private static Tooltip ttip(String text) {
        Tooltip t = new Tooltip(text);
        t.setWrapText(true);
        t.setMaxWidth(420);
        t.setShowDelay(javafx.util.Duration.millis(400));
        t.setShowDuration(javafx.util.Duration.seconds(60));
        t.setHideDelay(javafx.util.Duration.millis(400));
        return t;
    }

    /** Called when the dialog's active image changes, to refresh the classification list. */
    public void setImageData(ImageData<BufferedImage> data) {
        this.imageData = data;
        boolean nowCalibrated = data == null || isCalibrated(data);
        if (nowCalibrated != calibrated) {
            calibrated = nowCalibrated;
            loadDilate();
        }
        String prev = classBox.getValue();
        Set<String> classes = new LinkedHashSet<>();
        classes.add(ANY_CLASS);
        if (data != null && data.getHierarchy() != null) {
            for (PathObject o : data.getHierarchy().getFlattenedObjectList(null)) {
                PathClass pc = o.getPathClass();
                if (pc != null) {
                    classes.add(pc.toString());
                }
            }
        }
        classBox.getItems().setAll(classes);
        classBox.setValue(classes.contains(prev) ? prev : ANY_CLASS);
        mask = null;
        stale.set(true);
    }

    public boolean isEnabled() {
        return enableCheck.isSelected();
    }

    public CheckBox enabledCheckBox() {
        return enableCheck;
    }

    /** Fraction (0-1) of a fibre's length inside the mask at which it is dropped. */
    public double maxFrac() {
        return maxFracSpinner.getValue() / 100.0;
    }

    public boolean trimEnds() {
        return trimCheck.isSelected();
    }

    public boolean erase() {
        return eraseCheck.isSelected();
    }

    /**
     * Dilation in ANALYSED pixels at the given downsample. The spinner is in µm on a calibrated
     * image and in full-resolution pixels on an uncalibrated one, as every other distance in the
     * dialog is. (Before 0.2.4 an uncalibrated value was taken as analysed pixels, so it grew with
     * the downsample.)
     */
    public double dilateAnalysedPx(double downsample, double pixelSizeMicrons) {
        double dilateUserUnit = dilateSpinner.getValue();
        if (dilateUserUnit <= 0) {
            return 0;
        }
        boolean hasPixelSize = pixelSizeMicrons > 0 && !Double.isNaN(pixelSizeMicrons);
        double dilateFullResPx = hasPixelSize ? dilateUserUnit / pixelSizeMicrons : dilateUserUnit;
        return dilateFullResPx / Math.max(1e-9, downsample);
    }

    /** Show and edit the value stored for the current unit, and name that unit in the label. */
    private void loadDilate() {
        try {
            dilateSpinner.getValueFactory().setValue(
                    FiberSocketPreferences.exclDilateProperty().forUnit(calibrated).get());
        } catch (RuntimeException ex) {
            // stored value outside this spinner's range -- keep the current one
        }
        dilateLabel.setText(calibrated ? "Grow regions (µm)" : "Grow regions (px)");
    }

    /** The same test the dialog uses: a pixel size that is finite and above zero. */
    private static boolean isCalibrated(ImageData<BufferedImage> data) {
        var cal = data.getServer().getPixelCalibration();
        double pixelSizeMicrons = (cal != null && cal.hasPixelSizeMicrons())
                ? cal.getAveragedPixelSizeMicrons() : Double.NaN;
        return Double.isFinite(pixelSizeMicrons) && pixelSizeMicrons > 0;
    }

    private ExclusionMask.RoiChoice roiChoice() {
        return roiBox.getValue() != null && roiBox.getValue().startsWith("Nucleus")
                ? ExclusionMask.RoiChoice.NUCLEUS : ExclusionMask.RoiChoice.WHOLE;
    }

    private void browse() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose a GeoJSON file of regions to exclude");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("GeoJSON", "*.geojson", "*.json"));
        // Start where the file most likely is: beside whatever is already typed, else the folder
        // browsed last, else the PROJECT — regions are normally exported next to the project they
        // belong to. Without this the chooser opens at the top of the machine every time.
        java.io.File start = null;
        String cur = fileField.getText();
        if (cur != null && !cur.isBlank()) {
            Path p = Paths.get(cur).getParent();
            if (p != null && Files.isDirectory(p)) {
                start = p.toFile();
            }
        }
        if (start == null && lastBrowseDir != null && lastBrowseDir.isDirectory()) {
            start = lastBrowseDir;
        }
        if (start == null) {
            java.io.File pd = projectDir.get();
            if (pd != null && pd.isDirectory()) {
                start = pd;
            }
        }
        if (start == null) {
            java.io.File home = new java.io.File(System.getProperty("user.home", "."));
            start = home.isDirectory() ? home : null;
        }
        if (start != null) {
            fc.setInitialDirectory(start);
        }
        java.io.File f = fc.showOpenDialog(owner);
        if (f != null) {
            fileField.setText(f.getAbsolutePath());
            lastBrowseDir = f.getParentFile();
        }
    }

    /**
     * Load the chosen source, blocking until done. Returns the regions, or null when nothing could
     * be loaded (an error has already been shown). Safe to call repeatedly — the result is cached
     * until the source settings change — and safe to call off the FX thread, which is how an
     * analysis run picks up regions the user never clicked "Load" for.
     */
    public ExclusionMask ensureLoaded() {
        if (!enableCheck.isSelected()) {
            return null;
        }
        if (mask != null && !stale.get()) {
            return mask;
        }
        return load();
    }

    /** Post a status/error to the FX thread, so load() can run on an analysis worker. */
    private void say(String text) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            statusLabel.setText(text);
        } else {
            javafx.application.Platform.runLater(() -> statusLabel.setText(text));
        }
    }

    private void showError(String message) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            Dialogs.showErrorMessage("Exclusion regions", message);
        } else {
            javafx.application.Platform.runLater(
                    () -> Dialogs.showErrorMessage("Exclusion regions", message));
        }
    }

    private ExclusionMask load() {
        if (!enableCheck.isSelected()) {
            return null;
        }
        try {
            ExclusionMask m;
            if (srcFile.isSelected()) {
                String txt = fileField.getText();
                if (txt == null || txt.isBlank()) {
                    showError("Choose a GeoJSON file first.");
                    return null;
                }
                Path p = Paths.get(txt.trim());
                if (!Files.isReadable(p)) {
                    showError("Cannot read:\n" + p);
                    return null;
                }
                say("Reading " + p.getFileName() + "…");
                m = ExclusionMask.fromGeoJson(p, null, roiChoice());
            } else {
                if (imageData == null || imageData.getHierarchy() == null) {
                    showError("No image is open.");
                    return null;
                }
                List<PathObject> candidates = new ArrayList<>();
                String which = objectTypeBox.getValue();
                var hierarchy = imageData.getHierarchy();
                if (ALL_DETECTIONS.equals(which)) {
                    candidates.addAll(hierarchy.getDetectionObjects());
                } else if (ALL_ANNOTATIONS.equals(which)) {
                    candidates.addAll(hierarchy.getAnnotationObjects());
                } else {
                    candidates.addAll(hierarchy.getFlattenedObjectList(null));
                }
                String cls = classBox.getValue();
                Predicate<PathObject> filter = ANY_CLASS.equals(cls) || cls == null
                        ? o -> true
                        : o -> o.getPathClass() != null && cls.equals(o.getPathClass().toString());
                m = ExclusionMask.fromObjects(candidates, filter, roiChoice());
            }
            if (m.isEmpty()) {
                say("That source holds no usable area regions — nothing will be excluded.");
                mask = null;
                stale.set(true);
                return null;
            }
            mask = m;
            stale.set(false);
            say(m.describe() + " loaded.");
            warnIfMisplaced(m);
            return m;
        } catch (Exception ex) {
            logger.error("Failed to load exclusion regions", ex);
            showError("Could not read the regions:\n" + ex.getMessage());
            say("Load failed: " + ex.getMessage());
            mask = null;
            stale.set(true);
            return null;
        }
    }

    /**
     * Sanity-check the regions against the current image and warn on the two mistakes that produce
     * silently wrong results: regions from an image of a different size (so nothing lines up), and
     * a source that covers essentially everything (e.g. a tissue annotation picked instead of
     * nuclei), which would suppress all fibres.
     */
    private void warnIfMisplaced(ExclusionMask m) {
        if (imageData == null || imageData.getServer() == null) {
            return;
        }
        double[] e = m.extent();
        if (e == null) {
            return;
        }
        double iw = imageData.getServer().getWidth();
        double ih = imageData.getServer().getHeight();
        StringBuilder sb = new StringBuilder();
        if (e[2] < iw * 0.05 || e[3] < ih * 0.05) {
            sb.append("The regions only cover a tiny corner of this image — they may come from a "
                    + "different image or a different resolution.\n\n");
        } else if (e[2] > iw * 1.05 || e[3] > ih * 1.05) {
            sb.append(String.format(
                    "The regions extend to %.0f×%.0f but this image is %.0f×%.0f — they probably "
                    + "come from a different image, so they will not line up.%n%n", e[2], e[3], iw, ih));
        }
        if (sb.length() > 0) {
            sb.append("Loaded anyway; check the mask overlay before running.");
            String msg = sb.toString();
            javafx.application.Platform.runLater(
                    () -> Dialogs.showWarningNotification("Exclusion regions", msg));
        }
    }

    /** True once exclusion is switched on AND a source has been loaded, so tiles will carry a mask. */
    /**
     * A terse one-line description of where the exclusion regions come from and how they are
     * applied, for the run log. Condensed on purpose: this is for reproducing a run, not reading.
     */
    public String describeSource() {
        StringBuilder b = new StringBuilder();
        if (srcFile != null && srcFile.isSelected()) {
            b.append("file:").append(fileField == null ? "?" : fileField.getText());
        } else {
            b.append("hierarchy:").append(objectTypeBox == null ? "?" : objectTypeBox.getValue());
            if (classBox != null && classBox.getValue() != null) {
                b.append('/').append(classBox.getValue());
            }
        }
        b.append(" roi=").append(roiBox == null ? "?" : roiBox.getValue());
        b.append(" grow=").append(dilateSpinner == null ? "?" : dilateSpinner.getValue());
        b.append(" maxFrac=").append(maxFrac());
        b.append(" trim=").append(trimEnds());
        b.append(" erase=").append(erase());
        return b.toString();
    }

    public boolean isActive() {
        return enableCheck.isSelected() && mask != null;
    }

    /**
     * Build the per-tile mask, or null when exclusion is off or nothing is loaded.
     *
     * <p>When it IS active this always returns an image, all-black for a tile no region touches,
     * rather than null: the meta flag that gates the extra wire block is built once for the whole
     * run, so every tile must agree with it. An empty mask costs a few KB of PNG.</p>
     */
    public BufferedImage maskForTile(
            int regionX, int regionY, double downsample, int outW, int outH, double pixelSizeMicrons) {
        if (!isActive()) {
            return null;
        }
        BufferedImage img = mask.rasterise(regionX, regionY, downsample, outW, outH,
                dilateAnalysedPx(downsample, pixelSizeMicrons));
        return img != null ? img : new BufferedImage(outW, outH, BufferedImage.TYPE_BYTE_GRAY);
    }
}
