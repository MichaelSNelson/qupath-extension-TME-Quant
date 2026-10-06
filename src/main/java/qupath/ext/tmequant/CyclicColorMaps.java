package qupath.ext.tmequant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.color.ColorMaps;

/**
 * Installs CYCLIC colormaps into QuPath, for measurement maps of an ANGLE.
 *
 * <p>Fibre angle is an orientation, not a quantity: it lives on a circle of period 180°, where 1°
 * and 179° are two degrees apart. Every colormap QuPath ships is a line — one colour at the low end,
 * a different colour at the high end — so a measurement map of "Angle (deg)" paints those two nearly
 * identical fibres at opposite ends of the scale. A field of near-horizontal collagen then reads as
 * two populations instead of one, which is an artifact of the colouring, not of the tissue.</p>
 *
 * <p>A cyclic map closes the loop: its last colour is its first, so equal orientations get equal
 * colours however the range is scaled. Two are installed:</p>
 *
 * <ul>
 *   <li><b>Angle (cyclic)</b> — a circle of constant lightness in CIELAB (L*=65, C*=32). Hue alone
 *       carries the angle, so no orientation looks brighter or more important than another, and
 *       every colour sits at the same 5.7:1 contrast against the violet threshold mask. This is the
 *       honest one: use it to judge orientation.</li>
 *   <li><b>Angle (cyclic vivid)</b> — the familiar saturated hue wheel. Easier to read at a glance
 *       and to name a direction from, but its lightness swings (yellow is bright, blue is dark), so
 *       some orientations shout louder than others. Use it to show, not to judge.</li>
 * </ul>
 *
 * <p>Set the measurement map's range to <b>0–180</b> to pin the colours to absolute angles; QuPath
 * otherwise auto-scales to the data, which rotates the whole wheel and makes two images
 * incomparable. The cycle is still closed either way.</p>
 */
final class CyclicColorMaps {

    private static final Logger logger = LoggerFactory.getLogger(CyclicColorMaps.class);

    /** Entries in each lookup table; the last repeats the first, which is what closes the cycle. */
    private static final int N = 256;

    /** Lightness and chroma of the isoluminant map: the most chroma that stays inside sRGB at L*=65. */
    private static final double LAB_L = 65.0;
    private static final double LAB_C = 32.0;

    private CyclicColorMaps() {}

    /** Register the cyclic maps so they appear in Measure &gt; Show measurement maps. */
    static void install() {
        try {
            ColorMaps.installColorMaps(labCircle("Angle (cyclic)"), hueWheel("Angle (cyclic vivid)"));
            logger.debug("Installed cyclic angle colormaps");
        } catch (Exception | LinkageError ex) {
            // A missing colormap is a cosmetic loss; it must never stop the extension loading.
            logger.warn("Could not install the cyclic angle colormaps: {}", ex.getMessage());
        }
    }

    /** Constant-lightness circle in CIELAB: hue carries the angle, luminance is flat. */
    private static ColorMaps.ColorMap labCircle(String name) {
        double[] r = new double[N];
        double[] g = new double[N];
        double[] b = new double[N];
        for (int i = 0; i < N; i++) {
            double h = 2 * Math.PI * i / (N - 1.0); // 180 deg of orientation over the full hue circle
            double[] rgb = labToSrgb(LAB_L, LAB_C * Math.cos(h), LAB_C * Math.sin(h));
            r[i] = rgb[0];
            g[i] = rgb[1];
            b[i] = rgb[2];
        }
        return ColorMaps.createColorMap(name, r, g, b);
    }

    /** Fully saturated HSV hue wheel. */
    private static ColorMaps.ColorMap hueWheel(String name) {
        double[] r = new double[N];
        double[] g = new double[N];
        double[] b = new double[N];
        for (int i = 0; i < N; i++) {
            int argb = java.awt.Color.HSBtoRGB((float) (i / (N - 1.0)), 1f, 1f);
            r[i] = ((argb >> 16) & 0xFF) / 255.0;
            g[i] = ((argb >> 8) & 0xFF) / 255.0;
            b[i] = (argb & 0xFF) / 255.0;
        }
        return ColorMaps.createColorMap(name, r, g, b);
    }

    /**
     * CIELAB (D65) to sRGB, each channel in 0-1. Values outside the sRGB gamut are clamped, but at
     * L*=65 / C*=32 the whole circle is inside it, so nothing clamps and the map stays isoluminant.
     */
    private static double[] labToSrgb(double lStar, double aStar, double bStar) {
        double fy = (lStar + 16.0) / 116.0;
        double fx = fy + aStar / 500.0;
        double fz = fy - bStar / 200.0;
        double x = 0.95047 * labInv(fx);
        double y = 1.00000 * labInv(fy);
        double z = 1.08883 * labInv(fz);
        return new double[] {
                gamma(3.2406 * x - 1.5372 * y - 0.4986 * z),
                gamma(-0.9689 * x + 1.8758 * y + 0.0415 * z),
                gamma(0.0557 * x - 0.2040 * y + 1.0570 * z),
        };
    }

    private static double labInv(double t) {
        return t > 6.0 / 29.0 ? t * t * t : 3.0 * (6.0 / 29.0) * (6.0 / 29.0) * (t - 4.0 / 29.0);
    }

    private static double gamma(double c) {
        double v = Math.min(1.0, Math.max(0.0, c));
        return v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
    }
}
