package qupath.ext.tmequant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedup + seam-stitch a large assembled fibre set in SPATIAL BLOCKS, so no single POSTPROC
 * round-trip has to carry a whole slide.
 *
 * <p>A whole-slide run assembles far more fibres than one call can carry: 1620 tiles produced
 * 153,675 fibres, whose stitched JSON came back as a 379 MB response — past the client's sanity
 * cap, so the run failed at the very last step and threw away two and a half hours of tracing.
 * Raising the cap would not fix the shape of it either: the whole set is serialised twice (request
 * tree, response string) and parsed into one object graph, all live at once.</p>
 *
 * <p>Dedup and stitch are both LOCAL — they only ever compare fibres whose endpoints are a few
 * pixels apart — so the set is split by fibre centre into disjoint blocks and each block stitched
 * on its own. Disjoint means no fibre is processed twice and none is lost; the only thing blocking
 * can cost is a join whose two pieces fall either side of a block line. To get those back the pass
 * runs TWICE, the second time on a grid offset by half a block, so the first pass's block lines sit
 * mid-block the second time. The stitch only ever joins pieces from DIFFERENT tiles, so a fibre
 * already joined in pass 1 is not re-joined to itself in pass 2.</p>
 *
 * <p>TACS is deliberately not done this way: it measures each fibre's distance to the boundary,
 * which needs the whole mask, and a block-local mask would mis-measure every fibre near a block
 * edge. Callers that need TACS chunk the fibre LIST instead and send the same full mask with each
 * chunk, stitching off.</p>
 */
final class PostProcessBlocks {

    private static final Logger logger = LoggerFactory.getLogger(PostProcessBlocks.class);

    /**
     * Fibres per round-trip. A whole-slide run measured ~2.5 kB of JSON per fibre, so this caps a
     * response at roughly 50 MB even when a dense block runs well over target.
     */
    static final int CHUNK_FIBERS = 20_000;

    private PostProcessBlocks() {}

    /** Builds the POSTPROC meta JSON for one piece of the set (callers differ in how). */
    @FunctionalInterface
    interface MetaBuilder {
        String build(List<FiberSocketClient.Fiber> part);
    }

    /** True when {@code n} fibres are too many for a single round-trip. */
    static boolean needsBlocking(int n) {
        return n > CHUNK_FIBERS;
    }

    /**
     * Dedup + stitch {@code all} in two offset passes of disjoint spatial blocks.
     *
     * @param rwA region width in ANALYSED px (the coordinate space fibre centres are in)
     * @param rhA region height in analysed px
     * @param meta builds the POSTPROC meta for one block (stitch on, no mask)
     * @param status progress sink; may be a no-op but not null
     */
    static List<FiberSocketClient.Fiber> stitch(
            FiberSocketClient client, List<FiberSocketClient.Fiber> all, int rwA, int rhA,
            MetaBuilder meta, Consumer<String> status) throws Exception {
        int nBlocks = (int) Math.ceil(all.size() / (double) CHUNK_FIBERS);
        // Near-square blocks: a square grid has less internal boundary than strips, and boundary
        // length is exactly what costs us joins.
        double cell = Math.sqrt((double) rwA * (double) rhA / nBlocks);
        int gx = Math.max(1, (int) Math.round(rwA / cell));
        int gy = Math.max(1, (int) Math.ceil(nBlocks / (double) gx));
        int bw = Math.max(1, (int) Math.ceil(rwA / (double) gx));
        int bh = Math.max(1, (int) Math.ceil(rhA / (double) gy));
        logger.info("Post-process in blocks: {} fibres, {}x{} grid of {}x{} analysed px, 2 passes",
                all.size(), gx, gy, bw, bh);
        List<FiberSocketClient.Fiber> cur = all;
        for (int pass = 1; pass <= 2; pass++) {
            // Pass 2 shifts the grid half a block, so a join that pass 1's block line cut is
            // interior this time. The shift needs one more column/row to cover the edges.
            int offX = pass == 1 ? 0 : bw / 2;
            int offY = pass == 1 ? 0 : bh / 2;
            int cols = gx + (pass == 1 ? 0 : 1);
            int rows = gy + (pass == 1 ? 0 : 1);
            Map<Integer, List<FiberSocketClient.Fiber>> blocks = new LinkedHashMap<>();
            for (FiberSocketClient.Fiber f : cur) {
                int cx = clamp((int) ((centreX(f) + offX) / bw), 0, cols - 1);
                int cy = clamp((int) ((centreY(f) + offY) / bh), 0, rows - 1);
                blocks.computeIfAbsent(cy * cols + cx, k -> new ArrayList<>()).add(f);
            }
            List<FiberSocketClient.Fiber> next = new ArrayList<>(cur.size());
            int before = cur.size();
            int done = 0;
            for (var e : blocks.entrySet()) {
                done++;
                List<FiberSocketClient.Fiber> part = e.getValue();
                status.accept(String.format(
                        "Stitching seams — pass %d of 2, block %d/%d (%d fibres)…",
                        pass, done, blocks.size(), part.size()));
                if (part.size() < 2) {
                    next.addAll(part);
                    continue;
                }
                FiberSocketClient.AnalyzeResponse pr = client.postprocess(meta.build(part), null);
                if (pr != null && pr.ok && pr.fibers != null) {
                    next.addAll(pr.fibers);
                } else {
                    // Keep the block's fibres as traced rather than lose them to a failed clean-up.
                    logger.warn("  block {}/{} post-process failed ({}); kept unstitched",
                            done, blocks.size(), pr == null ? "no response" : pr.error);
                    next.addAll(part);
                }
            }
            logger.info("Post-process pass {}/2: {} -> {} fibres over {} block(s)",
                    pass, before, next.size(), blocks.size());
            cur = next;
        }
        // Each block renumbered from 0, so "Fiber ID" would otherwise repeat across them.
        for (int i = 0; i < cur.size(); i++) {
            cur.get(i).id = i;
        }
        return cur;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Fibre centre (analysed px), falling back to the middle vertex if the server sent none. */
    private static double centreX(FiberSocketClient.Fiber f) {
        if (f.center != null && f.center.length > 0) {
            return f.center[0];
        }
        return f.points != null && f.points.length > 0 ? f.points[f.points.length / 2][0] : 0;
    }

    private static double centreY(FiberSocketClient.Fiber f) {
        if (f.center != null && f.center.length > 1) {
            return f.center[1];
        }
        return f.points != null && f.points.length > 0 ? f.points[f.points.length / 2][1] : 0;
    }
}
