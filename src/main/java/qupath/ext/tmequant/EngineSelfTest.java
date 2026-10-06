package qupath.ext.tmequant;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.Random;

/**
 * "Does the fiber engine actually work?" self-test.
 *
 * <p>{@code Ping} only reports whether the backend <em>imported</em> ({@code backend=real}). A
 * compiled FIRE backend that loaded but is subtly incompatible with the machine can run to
 * completion yet trace <em>zero</em> fibers on every input — a silent failure that looks, from
 * the dialog, like badly-tuned parameters. This test removes that ambiguity: it sends a built-in
 * synthetic image containing obvious straight fibers through the normal analyze path and checks
 * that FIRE traces some. A healthy engine returns several; a broken one returns 0.</p>
 *
 * <p>Entirely client-side: it needs no open image and no server changes, so it works against any
 * server build the user already has.</p>
 */
final class EngineSelfTest {

    private static final Logger logger = LoggerFactory.getLogger(EngineSelfTest.class);
    private static final Gson GSON = new Gson();
    private static final String NAME = "TME Quant";

    /** Deep link to the "engine returns 0 fibers" remedy in the README troubleshooting. */
    private static final String DOC_TROUBLESHOOTING =
            "https://github.com/MichaelSNelson/qupath-extension-TME-Quant#troubleshooting";

    private EngineSelfTest() {}

    /** Run the self-test off the FX thread and report the verdict in a dialog. */
    static void run(QuPathGUI qupath) {
        String host = FiberSocketPreferences.getHost();
        int port = FiberSocketPreferences.getPort();
        Dialogs.showInfoNotification(NAME, "Testing the fiber engine… (this runs one small FIRE "
                + "analysis; the first call can take ~30 s while the backend loads).");
        new Thread(() -> {
            try {
                BufferedImage test = syntheticFiberImage(256, 256);
                String meta = buildMeta(test.getWidth(), test.getHeight());
                FiberSocketClient client = new FiberSocketClient(host, port, 2000, 120_000);
                FiberSocketClient.AnalyzeResponse resp = client.analyze(test, meta, null);
                Platform.runLater(() -> report(qupath, resp));
            } catch (Exception ex) {
                logger.warn("Fiber engine self-test could not reach the server", ex);
                Platform.runLater(() -> Dialogs.showErrorMessage(NAME,
                        String.format("Could not reach the fiber server at %s:%d to run the "
                                + "self-test.%n%s%n%nStart the server (tmequant_server.bat) and try "
                                + "again.", host, port, ex.getMessage())));
            }
        }, "tmequant-selftest").start();
    }

    /** Turn the analyze response into a clear pass/fail verdict with remediation. */
    private static void report(QuPathGUI qupath, FiberSocketClient.AnalyzeResponse resp) {
        if (resp == null || !resp.ok) {
            String err = resp == null ? "no response" : (resp.error == null ? "unknown error" : resp.error);
            Dialogs.showErrorMessage(NAME, "The self-test analysis failed on the server:\n" + err);
            return;
        }
        // Prefer the raw trace count (before the Min-length filter) — that isolates the engine
        // itself from any filtering. Fall back to the final count on older servers.
        int traced = resp.nTraced != null ? resp.nTraced : resp.nFibers;

        // Always log the verdict so it's copyable from QuPath's log (View > Show log) — the
        // pop-up notification is transient and can't be copied.
        logger.info("Fiber engine self-test: backend={} traced={} nFibers={} reason={}",
                resp.backend, resp.nTraced, resp.nFibers, resp.reason);

        if ("synthetic".equals(resp.backend)) {
            String reason = resp.reason != null ? "\n\nReason:\n" + resp.reason : "";
            boolean open = Dialogs.showConfirmDialog(NAME,
                    "The real FIRE backend is NOT loaded — the server is running the SYNTHETIC "
                    + "fallback, so FIRE parameters have no effect and results are not real fibers."
                    + reason + "\n\nOpen the troubleshooting guide (how to load / rebuild the real "
                    + "backend)?");
            if (open) {
                QuPathGUI.openInBrowser(DOC_TROUBLESHOOTING);
            }
            return;
        }

        if (traced > 0) {
            Dialogs.showInfoNotification(NAME, String.format(
                    "Fiber engine OK ✓%nbackend = real, traced %d fibers on the built-in test image."
                    + "%nIf a real image still finds nothing, it's a settings issue — set the "
                    + "Background threshold so the red mask outlines collagen, not noise.", traced));
            return;
        }

        // backend=real but ZERO traces on an image full of obvious fibers => the compiled engine
        // is present but not working on this machine. This is the case Ping cannot detect.
        logger.warn("Fiber engine self-test FAILED: backend=real but 0 fibers traced on the "
                + "built-in test image (compiled backend likely incompatible / needs rebuild).");
        boolean open = Dialogs.showConfirmDialog(NAME,
                "Fiber engine PROBLEM ✗\n\n"
                + "The backend loaded (backend = real) but traced 0 fibers on a built-in image "
                + "that is full of obvious fibers. That means the compiled FIRE engine is present "
                + "but not actually working on this machine — usually the prebuilt binary doesn't "
                + "match your system's libraries.\n\n"
                + "FIX (in the MSYS2 UCRT64 shell, in the fiber_socket_bridge folder):\n"
                + "    ./build_backend.sh\n"
                + "    ./setup_fire_server.sh\n"
                + "then restart the server and run this self-test again.\n\n"
                + "Open the troubleshooting guide?");
        if (open) {
            QuPathGUI.openInBrowser(DOC_TROUBLESHOOTING);
        }
    }

    /** Minimal, permissive analyze request matched to the synthetic image. */
    private static String buildMeta(int w, int h) {
        JsonObject o = new JsonObject();
        // Background ~12, fibers ~235 in a 0-255 image: threshold at 90 separates them cleanly.
        o.addProperty("thresh_im2", 90);
        o.addProperty("LL1", 15);            // min length (analysed px)
        o.addProperty("thresh_LMPdist", 3);  // seed spacing
        o.addProperty("thresh_LMP", 0.2);    // seed sensitivity (FIRE default)
        o.addProperty("sigma_im", 1.0);
        o.addProperty("sigma_d", 0.3);
        o.addProperty("thresh_ext", 0.5);    // permissive bend (cos 60°)
        o.addProperty("thresh_linka", -0.9);
        o.addProperty("widMAX", 0.0);        // no width filter

        JsonObject region = new JsonObject();
        region.addProperty("x", 0);
        region.addProperty("y", 0);
        region.addProperty("width", w);
        region.addProperty("height", h);
        region.addProperty("downsample", 1.0);

        JsonObject meta = new JsonObject();
        meta.add("region", region);
        meta.addProperty("fiber_mode", 2);
        meta.addProperty("use_ct_reconstruction", false);
        meta.addProperty("has_mask", false);
        meta.addProperty("prescaled", true); // already a 0-255 gray image
        meta.add("overrides", o);
        return GSON.toJson(meta);
    }

    /**
     * A dark grayscale image with several long, bright, straight fibers of a few px width — the
     * easiest possible input for FIRE. Any working engine traces these; a broken one returns 0.
     */
    private static BufferedImage syntheticFiberImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(12, 12, 12));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(235, 235, 235));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // A fan of long strokes at varied angles, all comfortably longer than LL1.
        double[][] lines = {
                {20, 40, 230, 70}, {30, 120, 220, 150}, {40, 210, 235, 180},
                {60, 30, 90, 230}, {150, 25, 190, 235}, {235, 40, 200, 220},
                {25, 90, 210, 235}, {45, 235, 235, 60},
        };
        for (double[] l : lines) {
            g.draw(new Line2D.Double(l[0], l[1], l[2], l[3]));
        }
        g.dispose();
        // Light noise so the background isn't perfectly flat (more representative input).
        WritableRaster r = img.getRaster();
        Random rnd = new Random(42);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = r.getSample(x, y, 0) + (int) Math.round(rnd.nextGaussian() * 4);
                r.setSample(x, y, 0, Math.max(0, Math.min(255, v)));
            }
        }
        return img;
    }
}
