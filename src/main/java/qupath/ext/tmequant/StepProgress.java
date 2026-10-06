package qupath.ext.tmequant;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;

/**
 * A vertical list of named processing steps, each with a status bubble that changes colour
 * (pending = grey, running = amber, done = green, error = red) and a tooltip describing what the
 * step does, plus a live detail line (e.g. "image 3 / 20"). All mutators are thread-safe — they
 * marshal onto the FX thread — so a background worker can drive it directly.
 */
public final class StepProgress extends VBox {

    private static final Color PENDING = Color.web("#b0b0b0");
    private static final Color RUNNING = Color.web("#b35900"); // amber
    private static final Color DONE = Color.web("#0a7d2c");     // green
    private static final Color ERROR = Color.web("#c1121f");    // red

    private final List<Step> steps = new ArrayList<>();

    public StepProgress() {
        setSpacing(8);
        setPadding(new Insets(4));
    }

    /** Add a step; returns its index (for later status updates). Safe to call before showing. */
    /** A tooltip that WRAPS long text instead of running off the screen edge. */
    private static Tooltip ttip(String text) {
        Tooltip t = new Tooltip(text);
        t.setWrapText(true);
        t.setMaxWidth(420);
        return t;
    }

    public int addStep(String name, String tooltip) {
        Step s = new Step(name, tooltip);
        steps.add(s);
        Runnable r = () -> getChildren().add(s.row);
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
        return steps.size() - 1;
    }

    public void running(int i, String detail) {
        set(i, RUNNING, detail, false);
    }

    public void done(int i, String detail) {
        set(i, DONE, detail, false);
    }

    public void error(int i, String detail) {
        set(i, ERROR, detail, true);
    }

    /** Update just the detail line of a step (e.g. progress within it), keeping its colour. */
    public void detail(int i, String detail) {
        if (i < 0 || i >= steps.size()) {
            return;
        }
        Step s = steps.get(i);
        Runnable r = () -> s.detail.setText(detail == null ? "" : detail);
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    private void set(int i, Color color, String detail, boolean bold) {
        if (i < 0 || i >= steps.size()) {
            return;
        }
        Step s = steps.get(i);
        Runnable r = () -> {
            s.bubble.setFill(color);
            if (detail != null) {
                s.detail.setText(detail);
            }
            s.name.setStyle("-fx-font-weight: " + (bold ? "bold" : "normal") + ";");
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** One step row: bubble + name + detail. */
    private static final class Step {
        final Circle bubble = new Circle(6, PENDING);
        final Label name;
        final Label detail = new Label();
        final HBox row;

        Step(String nameText, String tooltip) {
            name = new Label(nameText);
            detail.setStyle("-fx-font-size: 11; -fx-text-fill: #555;");
            row = new HBox(8, bubble, name, detail);
            row.setAlignment(Pos.CENTER_LEFT);
            if (tooltip != null && !tooltip.isBlank()) {
                Tooltip tip = ttip(tooltip);
                Tooltip.install(bubble, tip);
                name.setTooltip(tip);
            }
        }
    }
}
