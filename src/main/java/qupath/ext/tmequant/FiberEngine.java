package qupath.ext.tmequant;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * FX-free tiled FIRE detection engine — the headless equivalent of
 * {@code FiberAnalysisDialog.analyzeFull} (detect tiles, keep by core ownership, stitch seams),
 * with all the interactive UI concerns (spinners, live overlay, tickers, TACS) removed. Reproduces
 * the dialog's {@code computeTiles}/{@code detectTile}/{@code inCore}/{@code offsetFiber} and the
 * detection loop verbatim so a scripted run matches the interactive one.
 *
 * <p>All fibre coordinates returned are region-analysed pixels (region-local, at the run's
 * downsample), ready for {@link FiberObjectBuilder#build} with the region origin.</p>
 */
public final class FiberEngine {

    private static final Logger logger = LoggerFactory.getLogger(FiberEngine.class);

    private static final int TILE_CORE = 768;
    private static final int TILE_OVERLAP = 96;
    private static final int MAX_TILES = 64;

    private FiberEngine() {}

    /** Largest tile side (analysed px), from the Preferences value, clamped to a sane range. */
    private static int maxTileDim() {
        double v = FiberSocketPreferences.maxTileDimProperty().get();
        return (int) Math.max(256, Math.min(16384, Math.round(v)));
    }

    /**
     * Tile-side cap in ANALYSED px, adjusted for downsample so a tile never covers more than
     * {@code maxTileDim} full-res px per side. computeTiles caps by analysed px, so at downsample d a
     * tile would otherwise span maxTileDim×d full-res px — 4× the tissue at d=2 — giving 4× the fibres
     * and a fiberproc OOM. Mirrors the dialog's {@code effectiveMaxTileDim} so headless runs tile the
     * same way. (Floor 256 so a large downsample can't shrink tiles to nothing.)
     */
    private static int effectiveMaxTileDim(double d) {
        int pref = maxTileDim();
        if (d <= 1.0) {
            return pref;
        }
        return Math.max(256, Math.min(pref, (int) Math.ceil(pref / d)));
    }

    // --- Result -----------------------------------------------------------------------------------

    /** Detected fibres (region-analysed coords) plus diagnostics, mirroring the dialog's result. */
    public static final class AnalyzeResult {
        public final List<FiberSocketClient.Fiber> fibers;
        public final String backend;
        public final int tiles;
        public int failedTiles = 0;
        public int autoRaisedTiles = 0;
        public int nTraced = 0;
        public int nLengthDropped = 0;
        public int nWidthDropped = 0;
        public double minLengthPx = 0;

        AnalyzeResult(List<FiberSocketClient.Fiber> fibers, String backend, int tiles) {
            this.fibers = fibers;
            this.backend = backend;
            this.tiles = tiles;
        }
    }

    // --- Tiling (verbatim from FiberAnalysisDialog) ----------------------------------------------

    /** One tile: core bounds + read region (full-image px) + analysed-px offset. */
    static final class Tile {
        final int coreX0;
        final int coreX1;
        final int coreY0;
        final int coreY1;
        final double ox;
        final double oy;
        final int fullX;
        final int fullY;
        final int fullW;
        final int fullH;

        Tile(int coreX0, int coreX1, int coreY0, int coreY1, int readX0, int readY0,
                int fullX, int fullY, int fullW, int fullH) {
            this.coreX0 = coreX0;
            this.coreX1 = coreX1;
            this.coreY0 = coreY0;
            this.coreY1 = coreY1;
            this.ox = readX0;
            this.oy = readY0;
            this.fullX = fullX;
            this.fullY = fullY;
            this.fullW = fullW;
            this.fullH = fullH;
        }
    }

    /** Split the region (in analysed pixels) into overlapping tiles. */
    static List<Tile> computeTiles(int rx, int ry, int rwA, int rhA, int serverW, int serverH,
            double d) {
        int nCols = Math.max(1, (int) Math.ceil((double) rwA / TILE_CORE));
        int nRows = Math.max(1, (int) Math.ceil((double) rhA / TILE_CORE));
        int maxTileDim = effectiveMaxTileDim(d); // parity with the dialog: cap by full-res px per tile
        int minCols = Math.max(1, (int) Math.ceil((double) rwA / maxTileDim));
        int minRows = Math.max(1, (int) Math.ceil((double) rhA / maxTileDim));
        while ((long) nCols * nRows > MAX_TILES) {
            if (nCols >= nRows && nCols > minCols) {
                nCols--;
            } else if (nRows > minRows) {
                nRows--;
            } else if (nCols > minCols) {
                nCols--;
            } else {
                break;
            }
        }
        int coreW = (int) Math.ceil((double) rwA / nCols);
        int coreH = (int) Math.ceil((double) rhA / nRows);
        List<Tile> tiles = new ArrayList<>();
        for (int cy = 0; cy < nRows; cy++) {
            for (int cx = 0; cx < nCols; cx++) {
                int coreX0 = cx * coreW;
                int coreX1 = Math.min(rwA, (cx + 1) * coreW);
                int coreY0 = cy * coreH;
                int coreY1 = Math.min(rhA, (cy + 1) * coreH);
                if (coreX0 >= coreX1 || coreY0 >= coreY1) {
                    continue;
                }
                int readX0 = Math.max(0, coreX0 - TILE_OVERLAP);
                int readX1 = Math.min(rwA, coreX1 + TILE_OVERLAP);
                int readY0 = Math.max(0, coreY0 - TILE_OVERLAP);
                int readY1 = Math.min(rhA, coreY1 + TILE_OVERLAP);
                int fullX = rx + (int) Math.round(readX0 * d);
                int fullY = ry + (int) Math.round(readY0 * d);
                int fullW = Math.min(serverW - fullX, (int) Math.round((readX1 - readX0) * d));
                int fullH = Math.min(serverH - fullY, (int) Math.round((readY1 - readY0) * d));
                if (fullW <= 0 || fullH <= 0) {
                    continue;
                }
                tiles.add(new Tile(coreX0, coreX1, coreY0, coreY1, readX0, readY0,
                        fullX, fullY, fullW, fullH));
            }
        }
        return tiles;
    }

    private static boolean inCore(FiberSocketClient.Fiber f, Tile tl) {
        if (f.center == null || f.center.length < 2) {
            return false;
        }
        double cx = f.center[0];
        double cy = f.center[1];
        return cx >= tl.coreX0 && cx < tl.coreX1 && cy >= tl.coreY0 && cy < tl.coreY1;
    }

    private static void offsetFiber(FiberSocketClient.Fiber f, double ox, double oy) {
        for (double[] p : f.points) {
            if (p != null && p.length >= 2) {
                p[0] += ox;
                p[1] += oy;
            }
        }
        if (f.center != null && f.center.length >= 2) {
            f.center[0] += ox;
            f.center[1] += oy;
        }
    }

    /** Zero the gray samples where the mask is background (< 128); returns the in-ROI count. */
    private static int applyMask(BufferedImage gray, BufferedImage mask) {
        int w = gray.getWidth();
        int h = gray.getHeight();
        var graster = gray.getRaster();
        int[] gs = graster.getSamples(0, 0, w, h, 0, (int[]) null);
        int[] ms = mask.getRaster().getSamples(0, 0, w, h, 0, (int[]) null);
        int inside = 0;
        for (int i = 0; i < gs.length; i++) {
            if (ms[i] < 128) {
                gs[i] = 0;
            } else {
                inside++;
            }
        }
        graster.setSamples(0, 0, w, h, 0, gs);
        return inside;
    }

    private static FiberSocketClient.AnalyzeResponse detectTile(FiberSocketClient client,
            ImageServer<BufferedImage> server, Tile tile, int tileIdx, int channel, double d,
            String detMeta, String[] backendOut, double normLoNative, double serverNormHiNative, ROI restrictRoi)
            throws Exception {
        RegionRequest req = RegionRequest.createInstance(
                server.getPath(), d, tile.fullX, tile.fullY, tile.fullW, tile.fullH);
        BufferedImage region = server.readRegion(req);
        if (region == null) {
            throw new RuntimeException("server.readRegion returned null for a tile");
        }
        BufferedImage gray = ChannelExtractor.toGray(region, channel, normLoNative, serverNormHiNative);
        if (restrictRoi != null) {
            BufferedImage roiMask = MaskRasterizer.rasterise(
                    restrictRoi, tile.fullX, tile.fullY, d, gray.getWidth(), gray.getHeight());
            applyMask(gray, roiMask);
        }
        FiberSocketClient.AnalyzeResponse resp = client.analyze(gray, detMeta, null);
        if (resp == null || !resp.ok) {
            return resp;
        }
        if ("synthetic".equals(resp.backend)) {
            backendOut[0] = "synthetic";
        }
        if (resp.fibers != null) {
            List<FiberSocketClient.Fiber> kept = new ArrayList<>();
            for (FiberSocketClient.Fiber f : resp.fibers) {
                if (f.points == null || f.points.length < 2 || f.center == null) {
                    continue;
                }
                offsetFiber(f, tile.ox, tile.oy);
                f.tile = tileIdx;
                kept.add(f);
            }
            resp.fibers = kept;
        }
        return resp;
    }

    // --- Per-region normalization (verbatim from FiberAnalysisDialog.computeStats) ----------------

    private static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /** ~2.56 MP: below this a region is sampled at the selected downsample (matches the dialog). */
    private static final long PREVIEW_FAITHFUL_MAX_PX = 2_560_000L;

    /** {normLoNative, normHiNative} for a region; see {@link #computeRegionNorm(ImageServer, int, int, int, int, int, double, double)}. */
    public static double[] computeRegionNorm(ImageServer<BufferedImage> server, int channel,
            int rx, int ry, int rw, int rh, double d) throws Exception {
        double[] n = computeRegionNorm(server, channel, rx, ry, rw, rh, d, Double.NaN);
        return new double[] {n[0], n[1]};
    }

    /**
     * {normLoNative, normHiNative, collagenLevelNative} for a region, sampled exactly as
     * {@code FiberAnalysisDialog.computeStats} samples it: the whole region at the analysis
     * downsample when it fits a faithful preview, else a central ~1600 analysed-px patch at that
     * downsample (coarsening the whole region instead averages thin bright collagen into the dark
     * around it and pulls the white point down). normLoNative is the minimum, normHiNative the 99.5th
     * percentile, and collagenLevelNative the 99.9th percentile of the pixels above {@code threshold}
     * (0 when it is NaN or too few pixels are above it), which the white point must cover.
     */
    public static double[] computeRegionNorm(ImageServer<BufferedImage> server, int channel,
            int rx, int ry, int rw, int rh, double d, double thresholdNative) throws Exception {
        long analysedPx = (long) Math.ceil(rw / d) * (long) Math.ceil(rh / d);
        int statDown = Math.max(1, (int) Math.ceil(d));
        int srx = rx, sry = ry, srw = rw, srh = rh;
        if (analysedPx > PREVIEW_FAITHFUL_MAX_PX) {
            int patchFull = Math.max(1, (int) Math.round(1600.0 * d));
            srw = Math.min(rw, patchFull);
            srh = Math.min(rh, patchFull);
            srx = rx + (rw - srw) / 2;
            sry = ry + (rh - srh) / 2;
        }
        RegionRequest req = RegionRequest.createInstance(server.getPath(), statDown, srx, sry, srw, srh);
        BufferedImage cr = server.readRegion(req);
        if (cr == null) {
            return new double[] {0, 255, 0};
        }
        double[] s = ChannelExtractor.channelSamples(cr, channel);
        double[] f = java.util.Arrays.stream(s).filter(Double::isFinite).toArray();
        if (f.length == 0) {
            return new double[] {0, 255, 0};
        }
        double[] sorted = f.clone();
        java.util.Arrays.sort(sorted);
        double normLoNative = sorted[0];
        double statMaxNative = sorted[sorted.length - 1];
        double normHiNative = percentile(sorted, 0.995);
        if (normHiNative <= normLoNative) {
            normHiNative = statMaxNative > normLoNative ? statMaxNative : normLoNative + 1.0;
        }
        return new double[] {normLoNative, normHiNative, brightCollagenLevelNative(sorted, thresholdNative)};
    }

    /** 99.9th percentile of the samples above {@code threshold}; 0 when there are too few (dialog rule). */
    static double brightCollagenLevelNative(double[] sorted, double thresholdNative) {
        if (Double.isNaN(thresholdNative) || sorted.length < 64) {
            return 0.0;
        }
        double[] above = java.util.Arrays.stream(sorted).filter(v -> v > thresholdNative).toArray();
        return above.length < 32 ? 0.0 : percentile(above, 0.999);
    }

    // --- The detection loop (verbatim core of FiberAnalysisDialog.analyzeFull) --------------------

    /**
     * Detect fibres across one region and stitch seams. FX-free; {@code log} (nullable) receives
     * progress lines. No TACS/boundary — {@code has_mask} is always false. {@code restrictRoi}, when
     * non-null, zeroes pixels outside it so FIRE never nucleates outside the target object.
     */
    public static AnalyzeResult analyzeRegion(String host, int port,
            ImageServer<BufferedImage> server, int channel, int rx, int ry, int rw, int rh,
            FiberParams p, boolean calibrated, double pixelSizeMicrons, double normLoNative, double normHiNative,
            ROI restrictRoi, Consumer<String> log) throws Exception {
        return analyzeRegion(host, port, server, channel, rx, ry, rw, rh, p, calibrated,
                pixelSizeMicrons, normLoNative, normHiNative, 0.0, restrictRoi, log);
    }

    /** As above, with the region's bright-collagen level from {@link #computeRegionNorm} (index 2). */
    public static AnalyzeResult analyzeRegion(String host, int port,
            ImageServer<BufferedImage> server, int channel, int rx, int ry, int rw, int rh,
            FiberParams p, boolean calibrated, double pixelSizeMicrons, double normLoNative, double normHiNative,
            double collagenLevelNative, ROI restrictRoi, Consumer<String> log) throws Exception {
        double d = p.downsample;
        int rwA = (int) Math.ceil(rw / d);
        int rhA = (int) Math.ceil(rh / d);
        List<Tile> tiles = computeTiles(rx, ry, rwA, rhA, server.getWidth(), server.getHeight(), d);
        String detMeta = p.buildDetectMeta(rx, ry, rw, rh, d, calibrated, pixelSizeMicrons,
                normLoNative, normHiNative, collagenLevelNative, null, null);
        double serverNormHiNative = p.whitePoint(normLoNative, normHiNative, collagenLevelNative);
        logProgress(log, String.format(
                "TME-Quant: region %dx%d @(%d,%d) downsample=%s -> %d analysed px -> %d tile(s)",
                rw, rh, rx, ry, trim(d), rwA * rhA, tiles.size()));

        FiberSocketClient client = new FiberSocketClient(host, port);
        String[] backend = {"real"};
        List<FiberSocketClient.Fiber> all = new ArrayList<>();
        boolean multiTile = tiles.size() > 1;
        int failedTiles = 0;
        int autoRaisedTiles = 0;
        int totalTraced = 0;
        int totalLenDropped = 0;
        int totalWidDropped = 0;
        double minLenPx = 0;

        for (int t = 0; t < tiles.size(); t++) {
            if (tiles.size() > 1) {
                logProgress(log, String.format("Detecting tile %d/%d…", t + 1, tiles.size()));
            }
            Tile tl = tiles.get(t);
            FiberSocketClient.AnalyzeResponse resp;
            try {
                resp = detectTile(client, server, tl, t, channel, d, detMeta, backend,
                        normLoNative, serverNormHiNative, restrictRoi);
            } catch (Exception ex) {
                resp = null;
                logger.warn("  tile {}/{} read/IO error: {}", t + 1, tiles.size(), ex.getMessage());
            }
            // RESILIENCE: a single failed tile must NOT abort the whole run.
            if (resp == null || !resp.ok) {
                failedTiles++;
                logger.warn("  tile {}/{} skipped: {}", t + 1, tiles.size(),
                        resp == null ? "no response" : resp.error);
                continue;
            }
            if (Boolean.TRUE.equals(resp.seedAutoRaised)) {
                autoRaisedTiles++;
            }
            if (resp.nTraced != null) {
                totalTraced += resp.nTraced;
            }
            if (resp.nLengthDropped != null) {
                totalLenDropped += resp.nLengthDropped;
            }
            if (resp.nWidthDropped != null) {
                totalWidDropped += resp.nWidthDropped;
            }
            if (resp.minLengthPx != null) {
                minLenPx = resp.minLengthPx;
            }
            List<FiberSocketClient.Fiber> tileFibers =
                    resp.fibers != null ? resp.fibers : new ArrayList<>();
            // Core ownership (multi-tile only): keep each fibre once, by the tile whose core
            // contains its centre. Single tile's core spans the whole region -> keeps all.
            for (FiberSocketClient.Fiber f : tileFibers) {
                if (!multiTile || inCore(f, tl)) {
                    all.add(f);
                }
            }
        }

        List<FiberSocketClient.Fiber> finalFibers = all;
        if (tiles.size() > 1 && !all.isEmpty()) {
            logProgress(log, "Stitching seams… (" + all.size() + " fibres)");
            PostProcessBlocks.MetaBuilder meta = part ->
                    p.buildPostMeta(part, d, calibrated, pixelSizeMicrons, true, null, null);
            if (PostProcessBlocks.needsBlocking(all.size())) {
                // Too many fibres for one round-trip; stitch in spatial blocks instead.
                finalFibers = PostProcessBlocks.stitch(client, all, rwA, rhA, meta,
                        s -> logProgress(log, s));
            } else {
                FiberSocketClient.AnalyzeResponse pr = client.postprocess(meta.build(all), null);
                if (pr != null && pr.ok && pr.fibers != null) {
                    finalFibers = pr.fibers;
                } else {
                    logger.warn("Post-process failed: {}", pr == null ? "no response" : pr.error);
                }
            }
            logger.info("Post-process: {} raw -> {} fibers (stitch=true)",
                    all.size(), finalFibers.size());
        } else {
            for (int i = 0; i < all.size(); i++) {
                all.get(i).id = i;
            }
        }
        if (failedTiles > 0) {
            logger.warn("{} of {} tiles were too dense to analyse even after auto-coarsening seeds.",
                    failedTiles, tiles.size());
        }
        AnalyzeResult ar = new AnalyzeResult(finalFibers, backend[0], tiles.size());
        ar.failedTiles = failedTiles;
        ar.autoRaisedTiles = autoRaisedTiles;
        ar.nTraced = totalTraced;
        ar.nLengthDropped = totalLenDropped;
        ar.nWidthDropped = totalWidDropped;
        ar.minLengthPx = minLenPx;
        return ar;
    }

    private static void logProgress(Consumer<String> log, String msg) {
        logger.info(msg);
        if (log != null) {
            log.accept(msg);
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
