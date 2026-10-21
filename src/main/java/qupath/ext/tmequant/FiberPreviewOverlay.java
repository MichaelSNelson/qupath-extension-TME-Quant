package qupath.ext.tmequant;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.overlays.AbstractOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImageRegion;

/**
 * A transient, lightweight viewer overlay that paints in-progress fibers as plain
 * Graphics2D polylines — NOT as {@link qupath.lib.objects.PathObject}s. During a large
 * "Add to image" run tens of thousands of fibers accumulate; adding those as detections
 * per tile (then removing and re-adding the cleaned set) churns the QuPath hierarchy and
 * makes the app unresponsive. Painting them as an overlay keeps the "watch it fill in"
 * feedback with essentially no hierarchy cost; the dialog removes the overlay and commits
 * the real, cleaned objects once the run finishes.
 *
 * <p>Fibers are stored in full-resolution image pixel coordinates and drawn directly:
 * QuPath hands {@code paintOverlay} a Graphics2D already in the image coordinate space,
 * so we only scale the stroke by the downsample to keep a constant on-screen width.</p>
 */
final class FiberPreviewOverlay extends AbstractOverlay {

    /** One polyline in full-res image px, with a cached bounding box for cheap culling. */
    private static final class Poly {
        final float[] xy; // x0,y0,x1,y1,...
        final float widthFullResPx; // estimated fibre thickness in full-res image px (0 = unknown)
        final float minX;
        final float minY;
        final float maxX;
        final float maxY;

        Poly(float[] xy, float widthFullResPx) {
            this.xy = xy;
            this.widthFullResPx = widthFullResPx;
            float a = Float.POSITIVE_INFINITY;
            float b = Float.POSITIVE_INFINITY;
            float c = Float.NEGATIVE_INFINITY;
            float d = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < xy.length; i += 2) {
                a = Math.min(a, xy[i]);
                c = Math.max(c, xy[i]);
                b = Math.min(b, xy[i + 1]);
                d = Math.max(d, xy[i + 1]);
            }
            this.minX = a;
            this.minY = b;
            this.maxX = c;
            this.maxY = d;
        }
    }

    private final List<Poly> polys = new ArrayList<>();
    private final Color color;
    /**
     * Dark stroke drawn UNDER each fibre, a little wider, so the fibre carries its own contrast
     * onto whatever it crosses -- mask, bright tissue or black background. The cartographic
     * "casing" trick. Null leaves fibres as a single flat stroke.
     */
    private final Color casingColor;
    // When true, each fibre is stroked at its estimated thickness (distance-map width) instead of a
    // constant on-screen hairline. Toggled live from the dialog.
    private volatile boolean widthByThickness = false;

    // Optional threshold mask (ARGB, red where foreground) drawn UNDER the fibres, scaled from its
    // own resolution onto the given full-res image region. Used by "Show tile in viewer".
    private volatile BufferedImage maskImg;
    private volatile int maskX;
    private volatile int maskY;
    private volatile int maskW;
    private volatile int maskH;

    FiberPreviewOverlay(OverlayOptions options, Color color) {
        this(options, color, null);
    }

    FiberPreviewOverlay(OverlayOptions options, Color color, Color casingColor) {
        super(options);
        this.color = color;
        this.casingColor = casingColor;
    }

    /** Stroke each fibre at its estimated thickness (true) or a constant on-screen width (false). */
    void setWidthByThickness(boolean on) {
        this.widthByThickness = on;
    }

    /** Set (or clear, with a null image) the threshold mask drawn under the fibres, at a full-res region. */
    void setMask(BufferedImage img, int x, int y, int w, int h) {
        this.maskImg = img;
        this.maskX = x;
        this.maskY = y;
        this.maskW = w;
        this.maskH = h;
    }

    /**
     * Append a batch of just-completed fibers (region-analysed coords) as full-res polylines.
     *
     * @param fibers    fibers whose {@code points} are region-local px at downsample {@code d}
     * @param regionX   region origin x (full-res px)
     * @param regionY   region origin y (full-res px)
     * @param d         downsample the region was analysed at
     */
    void addFibers(List<FiberSocketClient.Fiber> fibers, double regionX, double regionY, double d) {
        if (fibers == null || fibers.isEmpty()) {
            return;
        }
        List<Poly> batch = new ArrayList<>(fibers.size());
        for (FiberSocketClient.Fiber f : fibers) {
            double[][] pts = f == null ? null : f.points;
            if (pts == null || pts.length < 2) {
                continue;
            }
            float[] xy = new float[pts.length * 2];
            int k = 0;
            for (double[] p : pts) {
                xy[k++] = (float) (regionX + p[0] * d);
                xy[k++] = (float) (regionY + p[1] * d);
            }
            // f.width is the estimated thickness in ANALYSED px; scale to full-res image px.
            float widthFullResPxF = (float) (f.width > 0 ? f.width * d : 0.0);
            batch.add(new Poly(xy, widthFullResPxF));
        }
        if (!batch.isEmpty()) {
            synchronized (polys) {
                polys.addAll(batch);
            }
        }
    }

    @Override
    public boolean isVisible() {
        OverlayOptions opts = getOverlayOptions();
        return super.isVisible() && (opts == null || opts.getShowPixelClassification());
    }

    @Override
    public void paintOverlay(Graphics2D g2d, ImageRegion imageRegion, double downsampleFactor,
                             ImageData<BufferedImage> imageData, boolean paintCompletely) {
        OverlayOptions opts = getOverlayOptions();
        if (opts != null && !opts.getShowPixelClassification()) {
            return; // honour QuPath's overlay toggle ("C") — draw nothing while it's off
        }
        BufferedImage mask = maskImg;
        Poly[] snapshot;
        synchronized (polys) {
            snapshot = polys.isEmpty() ? new Poly[0] : polys.toArray(new Poly[0]);
        }
        if (mask == null && snapshot.length == 0) {
            return;
        }
        // Threshold mask first (red foreground), so fibres draw on top of it.
        if (mask != null) {
            Graphics2D gm = (Graphics2D) g2d.create();
            try {
                setAlphaComposite(gm);
                gm.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                gm.drawImage(mask, maskX, maskY, maskW, maskH, null);
            } finally {
                gm.dispose();
            }
        }
        if (snapshot.length == 0) {
            return;
        }
        double rx0 = imageRegion == null ? Double.NEGATIVE_INFINITY : imageRegion.getX();
        double ry0 = imageRegion == null ? Double.NEGATIVE_INFINITY : imageRegion.getY();
        double rx1 = imageRegion == null ? Double.POSITIVE_INFINITY : rx0 + imageRegion.getWidth();
        double ry1 = imageRegion == null ? Double.POSITIVE_INFINITY : ry0 + imageRegion.getHeight();

        Graphics2D g = (Graphics2D) g2d.create();
        try {
            setAlphaComposite(g); // honour the overlay opacity slider
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            final boolean byThick = widthByThickness;
            // Constant on-screen width, in image px: image px = screen px × downsample. This is
            // deliberately NOT a hairline: at ~1 screen px every pixel of the line is an
            // antialiased blend of line, casing and background, so the colour never reads as itself
            // -- bright yellow looks like a dull olive. ~2.5 px leaves a solid core of true colour,
            // which is what makes the traces legible on a projector.
            final float constW = (float) Math.max(downsampleFactor * 2.5, 1.0);
            // Floor so a thin fibre stays visible (~0.9 screen px) when stroking by true thickness.
            final float minW = (float) Math.max(downsampleFactor * 0.9, 0.5);
            if (!byThick) {
                g.setStroke(new BasicStroke(constW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            }
            // Two passes: the dark casing first for every fibre, then the bright colour on top.
            // Per-fibre interleaving would let a later fibre's casing erase an earlier fibre's core
            // where they cross, so the casing must be fully laid down before any colour is drawn.
            // The casing is wider by a constant SCREEN amount, hence the downsample scaling.
            final float casingPad = (float) Math.max(downsampleFactor * 1.6, 1.0);
            for (int pass = (casingColor == null ? 1 : 0); pass < 2; pass++) {
                g.setColor(pass == 0 ? casingColor : color);
                float pad = pass == 0 ? casingPad : 0f;
                if (!byThick) {
                    g.setStroke(new BasicStroke(constW + pad,
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                }
                for (Poly p : snapshot) {
                    if (p.maxX < rx0 || p.minX > rx1 || p.maxY < ry0 || p.minY > ry1) {
                        continue; // fully outside the painted region
                    }
                    if (byThick) {
                        // Stroke at the fibre's real thickness (image px); fall back to the hairline
                        // when the width is unknown. Thickness scales with zoom, like the real fibre.
                        float sw = p.widthFullResPx > 0 ? Math.max(p.widthFullResPx, minW) : constW;
                        g.setStroke(new BasicStroke(sw + pad,
                                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    }
                    Path2D.Float path = new Path2D.Float();
                    path.moveTo(p.xy[0], p.xy[1]);
                    for (int i = 2; i < p.xy.length; i += 2) {
                        path.lineTo(p.xy[i], p.xy[i + 1]);
                    }
                    g.draw(path);
                }
            }
        } finally {
            g.dispose();
        }
    }
}
