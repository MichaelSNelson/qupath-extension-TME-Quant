package qupath.ext.tmequant;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import qupath.lib.roi.interfaces.ROI;

/**
 * Rasterises a QuPath ROI's shape into a region-sized binary mask, used as the
 * tumour boundary for TACS classification.
 *
 * <p>The mask is white (255) inside the ROI shape and black (0) outside, with
 * the ROI's full-image coordinates translated into region-local pixels.
 * Mirrors {@code FiberAnalysisWorkflow.rasterisePolygonMask} from
 * qupath-extension-fiber-analysis.</p>
 */
public final class MaskRasterizer {

    private MaskRasterizer() {}

    public static BufferedImage rasterise(ROI roi, int regionX, int regionY, int regionW, int regionH) {
        return rasterise(roi, regionX, regionY, 1.0, regionW, regionH);
    }

    /**
     * Rasterise at a given downsample, producing a mask sized {@code outW x outH}
     * (i.e. the size of the downsampled region image). Full-image coordinates are
     * mapped to downsampled region-local pixels: {@code local = (full - origin) / d}.
     */
    public static BufferedImage rasterise(
            ROI roi, int regionX, int regionY, double downsample, int outW, int outH) {
        BufferedImage mask = new BufferedImage(outW, outH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = mask.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, outW, outH);

            // local = (full - origin) / downsample
            AffineTransform at = AffineTransform.getScaleInstance(1.0 / downsample, 1.0 / downsample);
            at.translate(-regionX, -regionY);
            g.setTransform(at);
            // Crisp binary edges (no anti-aliased greys).
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Color.WHITE);
            Shape shape = roi.getShape();
            if (shape != null) {
                g.fill(shape);
            }
        } finally {
            g.dispose();
        }
        return mask;
    }
}
