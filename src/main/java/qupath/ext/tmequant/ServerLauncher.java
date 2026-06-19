package qupath.ext.tmequant;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Brings the Python FIRE socket server up when QuPath needs it.
 *
 * <p>On opening the TME Quant dialog we ping the server; if it isn't reachable we either run the
 * user-configured launcher (e.g. {@code tmequant_server.bat}) and poll until it's ready, or — if
 * no launcher is configured — prompt the user to locate it (and remember it). All Swing/JavaFX
 * interaction happens on the FX thread; the blocking pings/polls happen on background threads.</p>
 */
final class ServerLauncher {

    private static final Logger logger = LoggerFactory.getLogger(ServerLauncher.class);
    private static final String TITLE = "TME Quant server";

    /** First start loads the C++ FIRE backend (~30-40 s); give generous head-room. */
    private static final long POLL_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    private ServerLauncher() {}

    /** True if a quick PING to the configured host/port succeeds within {@code timeoutMs}. */
    static boolean isReachable(int timeoutMs) {
        String host = FiberSocketPreferences.getHost();
        int port = FiberSocketPreferences.getPort();
        try {
            FiberSocketClient.PingResponse resp =
                    new FiberSocketClient(host, port, timeoutMs, timeoutMs).ping();
            return resp != null && resp.ok;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ensure the server is reachable, then run {@code onReady} on the FX thread. If it isn't
     * reachable, launch it (or guide the user to configure a launcher) first.
     */
    static void ensureReachable(QuPathGUI qupath, Runnable onReady) {
        new Thread(() -> {
            if (isReachable(800)) {
                Platform.runLater(onReady);
            } else {
                Platform.runLater(() -> decideAndAct(qupath, onReady));
            }
        }, "tmequant-server-check").start();
    }

    /** Server is down: launch via the configured launcher, or prompt to locate it. (FX thread.) */
    private static void decideAndAct(QuPathGUI qupath, Runnable onReady) {
        String launcher = FiberSocketPreferences.getServerLauncher();
        boolean haveLauncher = launcher != null && !launcher.isBlank() && new File(launcher).isFile();

        if (!haveLauncher) {
            ButtonType locate = new ButtonType("Locate launcher…", ButtonBar.ButtonData.OK_DONE);
            ButtonType openAnyway = new ButtonType("Open anyway", ButtonBar.ButtonData.OTHER);
            ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            Alert a = new Alert(Alert.AlertType.WARNING);
            a.setTitle(TITLE);
            a.setHeaderText("The FIRE server isn't running.");
            a.setContentText("No server launcher is configured. Locate your tmequant_server.bat so "
                    + "QuPath can start the server (it'll be remembered for next time), open the "
                    + "dialog anyway (preview/analysis won't work until the server is up), or cancel.");
            a.getButtonTypes().setAll(locate, openAnyway, cancel);
            initOwner(a, qupath);
            ButtonType res = a.showAndWait().orElse(cancel);
            if (res == cancel) {
                return;
            }
            if (res == openAnyway) {
                onReady.run();
                return;
            }
            File picked = chooseLauncher(qupath);
            if (picked == null) {
                return;
            }
            FiberSocketPreferences.setServerLauncher(picked.getAbsolutePath());
            launcher = picked.getAbsolutePath();
        } else if (!FiberSocketPreferences.getAutoLaunch()) {
            boolean start = Dialogs.showConfirmDialog(TITLE,
                    "The FIRE server isn't running. Start it now using the configured launcher?");
            if (!start) {
                onReady.run(); // open without the server; the user opted not to launch
                return;
            }
        }
        launchAndPoll(qupath, launcher, onReady);
    }

    /** Run the launcher, then poll until the server answers (or time out). */
    private static void launchAndPoll(QuPathGUI qupath, String launcher, Runnable onReady) {
        try {
            launch(launcher);
        } catch (IOException ex) {
            logger.error("Failed to launch server via {}", launcher, ex);
            Dialogs.showErrorMessage(TITLE, "Could not start the launcher:\n" + ex.getMessage());
            return;
        }
        Dialogs.showInfoNotification(TITLE,
                "Starting the FIRE server… watch its console window (first start ~30-40 s). "
                + "The dialog will open when it's ready.");
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                sleep(POLL_INTERVAL_MS);
                if (isReachable(800)) {
                    logger.info("Fiber server became reachable; opening dialog.");
                    Platform.runLater(onReady);
                    return;
                }
            }
            logger.warn("Fiber server did not become reachable within {} ms", POLL_TIMEOUT_MS);
            Platform.runLater(() -> Dialogs.showErrorMessage(TITLE,
                    "The server didn't become ready in time. Check its console window for errors, "
                    + "then reopen the TME Quant dialog."));
        }, "tmequant-server-poll").start();
    }

    /**
     * Start the launcher process. On Windows it runs in its own console window (so the user sees
     * the startup banner) and is detached from QuPath; elsewhere (dev) the script runs directly.
     */
    static void launch(String launcher) throws IOException {
        File f = new File(launcher);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder pb;
        if (os.contains("win")) {
            // `start "" "<path>"` opens a new console window; the empty title is required because
            // the path is quoted. ProcessBuilder handles the per-argument quoting.
            pb = new ProcessBuilder("cmd", "/c", "start", "", f.getAbsolutePath());
        } else {
            pb = new ProcessBuilder("bash", f.getAbsolutePath());
        }
        File dir = f.getParentFile();
        if (dir != null) {
            pb.directory(dir);
        }
        pb.start();
        logger.info("Launched fiber server: {}", f.getAbsolutePath());
    }

    /** Menu action: pick a launcher script and save it to preferences. */
    static void configureLauncher(QuPathGUI qupath) {
        File picked = chooseLauncher(qupath);
        if (picked != null) {
            FiberSocketPreferences.setServerLauncher(picked.getAbsolutePath());
            Dialogs.showInfoNotification(TITLE, "Saved server launcher: " + picked.getName());
        }
    }

    private static File chooseLauncher(QuPathGUI qupath) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Locate the TME Quant server launcher (tmequant_server.bat)");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Server launcher", "*.bat", "*.cmd", "*.sh", "*.ps1"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        String existing = FiberSocketPreferences.getServerLauncher();
        if (existing != null && !existing.isBlank()) {
            File dir = new File(existing).getParentFile();
            if (dir != null && dir.isDirectory()) {
                fc.setInitialDirectory(dir);
            }
        }
        Window owner = qupath == null ? null : qupath.getStage();
        return fc.showOpenDialog(owner);
    }

    private static void initOwner(Alert a, QuPathGUI qupath) {
        if (qupath != null && qupath.getStage() != null) {
            a.initOwner(qupath.getStage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
