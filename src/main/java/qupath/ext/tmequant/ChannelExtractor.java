package qupath.ext.tmequant;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

/**
 * Pulls a single channel out of a (possibly multi-channel / non-RGB) region as
 * an 8-bit grayscale image, reading raw raster samples so it works for ANY
 * color model.
 *
 * <p>The previous code PNG-encoded the region directly, which threw
 * {@code UnsupportedOperationException: This method is not supported by this
 * color model} for fluorescence / multi-channel images (their ColorModel has no
 * {@code getRGB}). The FIRE pipeline only uses one grayscale channel anyway, so
 * we extract the user-chosen channel here and send that.</p>
 */
public final class ChannelExtractor {

    private ChannelExtractor() {}

    /** Number of sample bands (channels) in the region's raster. */
    public static int numBands(BufferedImage region) {
        return region.getRaster().getNumBands();
    }

    /** Raw sample values of one channel (native units), for computing global stats. */
    public static double[] channelSamples(BufferedImage region, int channel) {
        var raster = region.getRaster();
        int w = region.getWidth();
        int h = region.getHeight();
        int nb = raster.getNumBands();
        int band = Math.min(Math.max(channel, 0), nb - 1);
        return raster.getSamples(0, 0, w, h, band, (double[]) null);
    }

    /**
     * Extract one channel as 8-bit grayscale using a GLOBAL reference range
     * [lo, hi] (native units), the SAME for every tile. This keeps the intensity
     * scale -- and therefore the threshold -- consistent across tiles, so a
     * near-black border tile maps to ~0 instead of having its noise amplified
     * into spurious fibers. Handles 8/16/32-bit and negative (unmixed) values.
     */
    public static BufferedImage toGray(BufferedImage region, int channel, double lo, double hi) {
        var raster = region.getRaster();
        int w = region.getWidth();
        int h = region.getHeight();
        int nb = raster.getNumBands();
        int band = Math.min(Math.max(channel, 0), nb - 1);
        double[] s = raster.getSamples(0, 0, w, h, band, (double[]) null);
        double range = hi > lo ? hi - lo : 1.0;
        double scale = 255.0 / range;
        int[] out = new int[w * h];
        for (int i = 0; i < s.length; i++) {
            double v = Double.isFinite(s[i]) ? s[i] : lo;
            int g = (int) Math.round((v - lo) * scale);
            out[i] = g < 0 ? 0 : (g > 255 ? 255 : g);
        }
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        gray.getRaster().setSamples(0, 0, w, h, 0, out);
        return gray;
    }

    /**
     * Extract one channel as an 8-bit grayscale image. Robust to 8-bit, 16-bit
     * and float rasters, and to bright outliers: the white point is the 99.5th
     * percentile rather than the absolute max, so a few hot pixels don't crush
     * the contrast of the real (dimmer) collagen signal.
     */
    public static BufferedImage toGray(BufferedImage region, int channel) {
        Raster raster = region.getRaster();
        int w = region.getWidth();
        int h = region.getHeight();
        int nb = raster.getNumBands();
        int band = Math.min(Math.max(channel, 0), nb - 1);

        // getSamples(double[]) handles 8-bit (byte), 16-bit (ushort/short) and
        // 32-bit (int/float) rasters uniformly. Float images may carry NaN or
        // negative values, so guard for finiteness throughout.
        double[] samples = raster.getSamples(0, 0, w, h, band, (double[]) null);

        // Robust white point via a coarse histogram (O(n), no sort).
        double max = 0.0;
        for (double v : samples) {
            if (Double.isFinite(v) && v > max) {
                max = v;
            }
        }
        double white = max;
        if (max > 0) {
            int bins = 2048;
            long[] hist = new long[bins];
            double inv = bins / max;
            long finiteCount = 0;
            for (double v : samples) {
                if (!Double.isFinite(v) || v <= 0) {
                    continue;
                }
                int b = (int) (v * inv);
                if (b >= bins) {
                    b = bins - 1;
                }
                hist[b]++;
                finiteCount++;
            }
            long target = (long) Math.ceil(finiteCount * 0.995);
            long cum = 0;
            int pbin = bins - 1;
            for (int b = 0; b < bins; b++) {
                cum += hist[b];
                if (cum >= target) {
                    pbin = b;
                    break;
                }
            }
            double pct = (pbin + 1) * (max / bins);
            // Guard against a degenerate (near-zero) percentile.
            white = pct > max * 0.02 ? pct : max;
        }
        double scale = white > 0 ? 255.0 / white : 0.0;

        int[] out = new int[w * h];
        for (int i = 0; i < samples.length; i++) {
            double s = samples[i];
            int v = Double.isFinite(s) ? (int) Math.round(s * scale) : 0;
            out[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }

        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster gr = gray.getRaster();
        gr.setSamples(0, 0, w, h, 0, out);
        return gray;
    }

    /** Convert an 8-bit grayscale BufferedImage to a JavaFX Image for preview. */
    public static Image toFxImage(BufferedImage gray) {
        int w = gray.getWidth();
        int h = gray.getHeight();
        int[] samples = gray.getRaster().getSamples(0, 0, w, h, 0, (int[]) null);
        WritableImage img = new WritableImage(w, h);
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int g = samples[row + x] & 0xFF;
                int argb = 0xFF000000 | (g << 16) | (g << 8) | g;
                pw.setArgb(x, y, argb);
            }
        }
        return img;
    }
}
