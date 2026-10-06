package qupath.ext.tmequant;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Image-scope control for project-wide runs: which images (Current / All project / Specific…) and,
 * within each image, WHERE FIRE runs — the whole image, or inside area annotations of chosen
 * class(es). Adapted from the QPSC/QP-CAT ScopeSection and extended with the region-mode picker.
 */
public final class ScopeSection extends VBox {

    /** Where, within each image, analysis runs. */
    public enum RegionMode { WHOLE_IMAGE, CLASS_ANNOTATIONS, ANY_ANNOTATION }

    private final QuPathGUI qupath;
    private final String pickerTitle;

    private final RadioButton scopeCurrentImage;
    private final RadioButton scopeAllImages;
    private final RadioButton scopeSpecificImages;
    private final Button chooseImagesButton;
    private final Label specificImagesLabel;
    private final List<ProjectImageEntry<BufferedImage>> selectedSubset = new ArrayList<>();

    private final RadioButton regionWholeImage;
    private final RadioButton regionAnyAnnotation;
    private final RadioButton regionClassAnnotations;
    private final VBox classBox = new VBox(2);
    private final List<CheckBox> classChecks = new ArrayList<>();

    public ScopeSection(QuPathGUI qupath, String pickerTitle) {
        this.qupath = qupath;
        this.pickerTitle = pickerTitle;
        setSpacing(8);

        // --- Image scope ---
        ToggleGroup group = new ToggleGroup();
        scopeCurrentImage = new RadioButton("Current image");
        scopeCurrentImage.setToggleGroup(group);
        scopeCurrentImage.setSelected(true);
        scopeAllImages = new RadioButton("All project images");
        scopeAllImages.setToggleGroup(group);
        scopeSpecificImages = new RadioButton("Specific images...");
        scopeSpecificImages.setToggleGroup(group);

        chooseImagesButton = new Button("Choose images...");
        chooseImagesButton.setOnAction(e -> openImageChooser());
        specificImagesLabel = new Label("(none chosen)");
        specificImagesLabel.setStyle("-fx-text-fill: #666;");

        Project<BufferedImage> project = qupath.getProject();
        boolean multiImage = project != null && project.getImageList().size() > 1;
        if (!multiImage) {
            scopeAllImages.setDisable(true);
            scopeSpecificImages.setDisable(true);
            String hint = " (needs a project with multiple images)";
            scopeAllImages.setText("All project images" + hint);
            scopeSpecificImages.setText("Specific images..." + hint);
        } else {
            scopeAllImages.setText("All project images (" + project.getImageList().size() + ")");
        }

        chooseImagesButton.disableProperty().bind(scopeSpecificImages.selectedProperty().not());
        scopeSpecificImages.selectedProperty().addListener((obs, was, now) -> {
            if (now && selectedSubset.isEmpty()) {
                openImageChooser();
            }
        });

        HBox radios = new HBox(15, new Label("Images:"), scopeCurrentImage, scopeAllImages,
                scopeSpecificImages);
        radios.setAlignment(Pos.CENTER_LEFT);
        HBox chooseRow = new HBox(8, chooseImagesButton, specificImagesLabel);
        chooseRow.setAlignment(Pos.CENTER_LEFT);
        chooseRow.setPadding(new Insets(0, 0, 0, 55));

        // --- Region mode ---
        ToggleGroup regionGroup = new ToggleGroup();
        regionWholeImage = new RadioButton("Whole image");
        regionWholeImage.setToggleGroup(regionGroup);
        regionWholeImage.setSelected(true);
        regionWholeImage.setTooltip(ttip("Run FIRE on the entire image of every selected "
                + "image (ignores annotations)."));
        regionAnyAnnotation = new RadioButton("Inside every area annotation");
        regionAnyAnnotation.setToggleGroup(regionGroup);
        regionAnyAnnotation.setTooltip(ttip("Run inside EVERY area annotation of each image, "
                + "regardless of its class (including unclassified). Images with no area annotation "
                + "are skipped."));
        regionClassAnnotations = new RadioButton("Inside area annotations of class:");
        regionClassAnnotations.setToggleGroup(regionGroup);
        regionClassAnnotations.setTooltip(ttip("Run only inside area annotations whose class "
                + "you tick below. Images with no matching annotation are skipped."));

        for (PathClass pc : qupath.getAvailablePathClasses()) {
            if (pc == null || pc.getName() == null) {
                continue; // skip the null/"unclassified" placeholder
            }
            CheckBox cb = new CheckBox(pc.toString());
            cb.setUserData(pc);
            classChecks.add(cb);
            classBox.getChildren().add(cb);
        }
        ScrollPane classScroll = new ScrollPane(classBox);
        classScroll.setFitToWidth(true);
        classScroll.setPrefHeight(110);
        classScroll.setPadding(new Insets(0));
        VBox classWrap = new VBox(4, classScroll);
        classWrap.setPadding(new Insets(0, 0, 0, 25));
        // Class list is only relevant in class-annotation mode.
        classWrap.visibleProperty().bind(regionClassAnnotations.selectedProperty());
        classWrap.managedProperty().bind(regionClassAnnotations.selectedProperty());

        Label regionHdr = new Label("Analyze:");
        regionHdr.setStyle("-fx-font-weight: bold; -fx-font-size: 11;");
        HBox regionRow = new HBox(15, regionWholeImage, regionAnyAnnotation, regionClassAnnotations);
        regionRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(radios, chooseRow, sep(), regionHdr, regionRow, classWrap);
    }

    private static Label sep() {
        Label l = new Label();
        l.setPrefHeight(2);
        return l;
    }

    /** A tooltip that WRAPS long text instead of running off the screen edge. */
    private static Tooltip ttip(String text) {
        Tooltip t = new Tooltip(text);
        t.setWrapText(true);
        t.setMaxWidth(420);
        return t;
    }

    private void openImageChooser() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("TME Quant", "No project is open.");
            return;
        }
        ProjectImageSelector.showDialog(qupath.getStage(), project, pickerTitle,
                        selectedSubset.isEmpty() ? null : selectedSubset)
                .ifPresent(chosen -> {
                    selectedSubset.clear();
                    selectedSubset.addAll(chosen);
                    int n = selectedSubset.size();
                    specificImagesLabel.setText(n == 0 ? "(none chosen)"
                            : n + " image" + (n == 1 ? "" : "s") + " chosen");
                });
    }

    public boolean isCurrentImage() {
        return scopeCurrentImage.isSelected();
    }

    public boolean isSpecificButEmpty() {
        return scopeSpecificImages.isSelected() && selectedSubset.isEmpty();
    }

    public void addScopeChangeListener(Runnable r) {
        scopeCurrentImage.selectedProperty().addListener((o, a, b) -> r.run());
        scopeAllImages.selectedProperty().addListener((o, a, b) -> r.run());
        scopeSpecificImages.selectedProperty().addListener((o, a, b) -> r.run());
    }

    /** Chosen project images, or null for current-image scope (empty only if specific-but-empty). */
    public List<ProjectImageEntry<BufferedImage>> resolveEntries() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            return null;
        }
        if (scopeAllImages.isSelected()) {
            return new ArrayList<>(project.getImageList());
        }
        if (scopeSpecificImages.isSelected()) {
            return new ArrayList<>(selectedSubset);
        }
        return null; // current image
    }

    public RegionMode getRegionMode() {
        if (regionClassAnnotations.isSelected()) {
            return RegionMode.CLASS_ANNOTATIONS;
        }
        if (regionAnyAnnotation.isSelected()) {
            return RegionMode.ANY_ANNOTATION;
        }
        return RegionMode.WHOLE_IMAGE;
    }

    /** The ticked annotation classes (only meaningful in CLASS_ANNOTATIONS mode). */
    public Set<PathClass> getSelectedClasses() {
        Set<PathClass> out = new LinkedHashSet<>();
        for (CheckBox cb : classChecks) {
            if (cb.isSelected() && cb.getUserData() instanceof PathClass pc) {
                out.add(pc);
            }
        }
        return out;
    }

    /** True when class-annotation mode is chosen but no class is ticked. */
    public boolean isClassModeButEmpty() {
        return getRegionMode() == RegionMode.CLASS_ANNOTATIONS && getSelectedClasses().isEmpty();
    }
}
