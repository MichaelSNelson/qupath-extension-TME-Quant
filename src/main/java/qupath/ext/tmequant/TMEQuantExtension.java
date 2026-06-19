package qupath.ext.tmequant;

import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the TME Quant extension.
 *
 * <p>Adds an <b>Extensions &gt; TME Quant</b> menu that opens an interactive
 * dialog: pick the collagen channel, tune the FIRE thresholding/segmentation
 * settings, preview the detected fibers over the region, then commit them as
 * line/polyline annotations or detections (optionally TACS-classified against a
 * selected boundary). Talks to the Python FIRE server over a socket, mirroring
 * the qpsc &lt;-&gt; microscope_command_server pattern.</p>
 */
public final class TMEQuantExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(TMEQuantExtension.class);
    private static final String EXTENSION_NAME = "TME Quant";

    private boolean installed = false;

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return "Runs the TMEQuant FIRE fiber pipeline on a region via a Python socket "
                + "server, with an interactive preview, and draws the fibers as objects.";
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) {
            return;
        }
        installed = true;
        addMenuItems(qupath);
        registerPreferences(qupath);
    }

    private void addMenuItems(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

        MenuItem analyzeItem = new MenuItem("Analyze fibers in selected region...");
        analyzeItem.disableProperty().bind(qupath.imageDataProperty().isNull());
        analyzeItem.setOnAction(e -> FiberAnalysisDialog.show(qupath, false));

        MenuItem tacsItem = new MenuItem("Analyze fibers + TACS (boundary = selection)...");
        tacsItem.disableProperty().bind(qupath.imageDataProperty().isNull());
        tacsItem.setOnAction(e -> FiberAnalysisDialog.show(qupath, true));

        MenuItem pingItem = new MenuItem("Ping fiber server");
        pingItem.setOnAction(e -> pingServer());

        MenuItem launcherItem = new MenuItem("Configure fiber server launcher...");
        launcherItem.setOnAction(e -> ServerLauncher.configureLauncher(qupath));

        menu.getItems().addAll(analyzeItem, tacsItem, pingItem, launcherItem);
    }

    /** Surface the server launcher + auto-launch settings in QuPath's Preferences pane. */
    private void registerPreferences(QuPathGUI qupath) {
        try {
            var pane = qupath.getPreferencePane();
            pane.addPropertyPreference(
                    FiberSocketPreferences.serverLauncherProperty(), String.class,
                    "Fiber server launcher", EXTENSION_NAME,
                    "Path to the launcher (e.g. tmequant_server.bat) QuPath runs to start the "
                            + "Python FIRE server when it isn't already running.");
            pane.addPropertyPreference(
                    FiberSocketPreferences.autoLaunchProperty(), Boolean.class,
                    "Auto-launch fiber server", EXTENSION_NAME,
                    "When the TME Quant dialog opens and the server isn't reachable, automatically "
                            + "run the configured launcher above and wait for it to start.");
        } catch (Exception ex) {
            // Non-fatal: the prefs are still persisted and settable via the menu item.
            logger.warn("Could not register TME Quant preferences in the Preferences pane", ex);
        }
    }

    private void pingServer() {
        String host = FiberSocketPreferences.getHost();
        int port = FiberSocketPreferences.getPort();
        new Thread(() -> {
            try {
                var resp = new FiberSocketClient(host, port).ping();
                String backend = resp == null ? "?" : resp.backend;
                StringBuilder msg = new StringBuilder(
                        String.format("Connected to %s:%d%nbackend = %s", host, port, backend));
                if (resp != null && "synthetic".equals(resp.backend) && resp.reason != null) {
                    msg.append("\n\nWARNING: running the SYNTHETIC fallback (FIRE params have no "
                            + "effect). Reason:\n").append(resp.reason);
                }
                boolean warn = resp != null && "synthetic".equals(resp.backend);
                Platform.runLater(() -> {
                    if (warn) {
                        Dialogs.showWarningNotification(EXTENSION_NAME, msg.toString());
                    } else {
                        Dialogs.showInfoNotification(EXTENSION_NAME, msg.toString());
                    }
                });
            } catch (Exception ex) {
                logger.warn("Ping failed", ex);
                Platform.runLater(() -> Dialogs.showErrorMessage(
                        EXTENSION_NAME,
                        String.format("Could not reach fiber server at %s:%d%n%s",
                                host, port, ex.getMessage())));
            }
        }, "tmequant-ping").start();
    }
}
