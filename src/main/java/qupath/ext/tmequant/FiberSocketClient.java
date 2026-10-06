package qupath.ext.tmequant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Socket client for the TMEQuant FIRE fiber server (fiber_socket_server.py).
 *
 * <p>Wire protocol (mirrors the qpsc / microscope_command_server convention):
 * an 8-byte ASCII command, then big-endian 4-byte length-prefixed payloads.</p>
 *
 * <pre>
 *   PING____                                  -&gt; &lt;len&gt;&lt;json&gt;
 *   ANALYZE_ &lt;metaLen&gt;&lt;metaJson&gt; &lt;pngLen&gt;&lt;png&gt; -&gt; &lt;len&gt;&lt;json&gt;
 *   SHUTDOWN                                   -&gt; (no response)
 * </pre>
 *
 * Each call opens a fresh connection (the analysis is a one-shot request);
 * this keeps the client simple and stateless, matching the request/response
 * nature of the server.
 */
public final class FiberSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(FiberSocketClient.class);
    // Tolerate NaN (a fibre width can be NaN); plain Gson throws on NaN. Matches the Python side.
    private static final Gson GSON =
            new GsonBuilder().serializeSpecialFloatingPointValues().create();

    // 8-byte commands (must match the Python server constants exactly).
    private static final byte[] CMD_PING = "PING____".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CMD_ANALYZE = "ANALYZE_".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CMD_POSTPROC = "POSTPROC".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CMD_GRIDSEARCH = "GRIDSRCH".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CMD_SHUTDOWN = "SHUTDOWN".getBytes(StandardCharsets.US_ASCII);

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public FiberSocketClient(String host, int port) {
        // Read timeout must exceed the server's per-tile FIRE timeout (180 s) plus response transfer,
        // so the server can fail a too-dense tile GRACEFULLY (and the client skip it) instead of the
        // client aborting mid-analysis. 300 s leaves margin for the large debug-export payload.
        this(host, port, 3000, 300000);
    }

    public FiberSocketClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    private Socket openSocket() throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        s.setSoTimeout(readTimeoutMs);
        return s;
    }

    /** Health check. Returns the parsed JSON (with {@code ok} and {@code backend}). */
    public PingResponse ping() throws IOException {
        try (Socket s = openSocket()) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.write(CMD_PING);
            out.flush();
            String json = readLengthPrefixedString(in);
            return GSON.fromJson(json, PingResponse.class);
        }
    }

    /** Politely ask the server to stop (used for testing / teardown). */
    public void shutdown() throws IOException {
        try (Socket s = openSocket()) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.write(CMD_SHUTDOWN);
            out.flush();
        }
    }

    /**
     * Send a region image plus metadata and return the detected fibers.
     *
     * @param region the pixels of a RegionRequest (PNG-encoded on the wire)
     * @param metaJson the JSON header describing the region/params. If it sets
     *     {@code "has_mask":true}, {@code mask} must be non-null and is sent as
     *     an extra block for TACS classification.
     * @param mask optional boundary mask (region-sized); may be null
     */
    public AnalyzeResponse analyze(BufferedImage region, String metaJson, BufferedImage mask)
            throws IOException {
        return analyze(region, metaJson, mask, null);
    }

    /**
     * As {@link #analyze(BufferedImage, String, BufferedImage)}, with an additional exclusion mask.
     *
     * @param exclude optional "don't trace here" mask (region-sized). When non-null the meta must
     *     set {@code "has_exclude_mask":true}; it is sent as a further block AFTER the boundary
     *     mask, so a request with neither, or with only the boundary, is unchanged on the wire.
     */
    public AnalyzeResponse analyze(
            BufferedImage region, String metaJson, BufferedImage mask, BufferedImage exclude)
            throws IOException {
        byte[] meta = metaJson.getBytes(StandardCharsets.UTF_8);
        byte[] png = encodePng(region);
        byte[] maskPng = mask == null ? null : encodePng(mask);
        byte[] excludePng = exclude == null ? null : encodePng(exclude);

        try (Socket s = openSocket()) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());

            out.write(CMD_ANALYZE);
            out.writeInt(meta.length); // big-endian, matches struct.pack("!I")
            out.write(meta);
            out.writeInt(png.length);
            out.write(png);
            if (maskPng != null) {
                out.writeInt(maskPng.length);
                out.write(maskPng);
            }
            if (excludePng != null) {
                out.writeInt(excludePng.length);
                out.write(excludePng);
            }
            out.flush();

            String json = readLengthPrefixedString(in);
            return GSON.fromJson(json, AnalyzeResponse.class);
        }
    }

    /**
     * Post-process an assembled fiber set: dedup + seam-stitch + global TACS.
     *
     * @param metaJson JSON with a {@code fibers} array (region-local coords) plus
     *     {@code stitch}, {@code has_mask}, {@code distance_threshold}
     * @param mask full-region boundary mask (region size), or null
     */
    public AnalyzeResponse postprocess(String metaJson, BufferedImage mask) throws IOException {
        byte[] meta = metaJson.getBytes(StandardCharsets.UTF_8);
        byte[] maskPng = mask == null ? null : encodePng(mask);
        try (Socket s = openSocket()) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.write(CMD_POSTPROC);
            out.writeInt(meta.length);
            out.write(meta);
            if (maskPng != null) {
                out.writeInt(maskPng.length);
                out.write(maskPng);
            }
            out.flush();
            return GSON.fromJson(readLengthPrefixedString(in), AnalyzeResponse.class);
        }
    }

    /**
     * Run a parameter grid search on one (small) region and return ranked results.
     *
     * @param region the region pixels (PNG-encoded on the wire), prescaled 0-255
     * @param metaJson JSON with {@code grid}, {@code base_overrides},
     *     {@code gt_lines} (optional ground-truth polylines in region-local px),
     *     {@code prescaled}, and scoring params
     */
    public GridSearchResponse gridSearch(BufferedImage region, String metaJson)
            throws IOException {
        byte[] meta = metaJson.getBytes(StandardCharsets.UTF_8);
        byte[] png = encodePng(region);
        try (Socket s = openSocket()) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.write(CMD_GRIDSEARCH);
            out.writeInt(meta.length);
            out.write(meta);
            out.writeInt(png.length);
            out.write(png);
            out.flush();
            return GSON.fromJson(readLengthPrefixedString(in), GridSearchResponse.class);
        }
    }

    private static byte[] encodePng(BufferedImage img) throws IOException {
        BufferedImage toWrite = img;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(toWrite, "PNG", baos)) {
            // TYPE_CUSTOM rasters (some Bio-Formats pixel types) have no PNG
            // writer; convert via a standard ARGB image first.
            BufferedImage argb =
                    new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(img, 0, 0, null);
            baos = new ByteArrayOutputStream();
            if (!ImageIO.write(argb, "PNG", baos)) {
                throw new IOException("PNG encoder rejected the region image");
            }
        }
        return baos.toByteArray();
    }

    private String readLengthPrefixedString(DataInputStream in) throws IOException {
        int len = in.readInt(); // big-endian
        if (len < 0 || len > 256 * 1024 * 1024) {
            throw new IOException("Unreasonable response length: " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // JSON data model (matches fiber_socket_server.py responses)
    // ------------------------------------------------------------------

    /** Response to PING____. */
    public static final class PingResponse {
        public boolean ok;
        public String backend;
        public Boolean tacs;
        public String reason; // why backend is synthetic, if so
    }

    /** Response to ANALYZE_. */
    public static final class AnalyzeResponse {
        public boolean ok;
        public String backend;
        public String error;
        public String reason; // why backend is synthetic, if so

        @SerializedName("n_fibers")
        public int nFibers;

        // Diagnostics (real backend) for explaining a low/zero fiber count.
        @SerializedName("n_traced")
        public Integer nTraced; // fibers FIRE traced, before the Min-length filter
        @SerializedName("n_length_dropped")
        public Integer nLengthDropped; // removed by Min fiber length (LL1)
        @SerializedName("min_length_px")
        public Double minLengthPx;
        @SerializedName("n_width_dropped")
        public Integer nWidthDropped; // removed by Max fiber width (widMAX)
        @SerializedName("seed_spacing_used")
        public Double seedSpacingUsed;
        @SerializedName("seed_auto_raised")
        public Boolean seedAutoRaised; // a dense tile was recovered by coarsening seeds
        @SerializedName("frac_above")
        public Double fracAbove;

        /** Present only for a single-tile debug export (meta.debug_export). */
        public DebugExport debug;

        public List<Fiber> fibers;
    }

    /** Base64 PNGs + scalars of what FIRE actually traced on, for the FULL TEST-EXPORT. */
    public static final class DebugExport {
        @SerializedName("gray_png")
        public String grayPng;      // the prescaled/ridge image FIRE received
        @SerializedName("mask_png")
        public String maskPng;      // threshold foreground
        @SerializedName("dist_png")
        public String distPng;      // cityblock distance map (drives search radius / jumps)
        @SerializedName("overlay_png")
        public String overlayPng;   // traced fibres on the grey
        public Double thresh;
        @SerializedName("dist_max")
        public Double distMax;
        @SerializedName("foreground_frac")
        public Double foregroundFrac;
        public Integer width;
        public Integer height;
        public String error;
    }

    /** Response to GRIDSRCH: ranked parameter combos. */
    public static final class GridSearchResponse {
        public boolean ok;
        public String error;
        public boolean supervised;

        @SerializedName("n_combos")
        public int nCombos;

        @SerializedName("score_mode")
        public String scoreMode;

        /** Overrides of every result within 5% of the best score (the "good band"). */
        @SerializedName("best_band")
        public List<java.util.Map<String, Double>> bestBand;

        public List<GridResult> results;
    }

    /** One scored parameter combo from a grid search (best first). */
    public static final class GridResult {
        public double score;
        /** FIRE overrides for this combo (e.g. thresh_im2, thresh_LMPdist, sigma_im, LL1). */
        public java.util.Map<String, Double> overrides;

        // Supervised metrics.
        public Double recall;
        @SerializedName("orient_err")
        public Double orientErr;
        public Integer matched;
        @SerializedName("n_gt")
        public Integer nGt;
        /** Indices (into this piece's gt_lines) of the GT fibers this combo actually detected. */
        @SerializedName("matched_gt")
        public List<Integer> matchedGt;

        // Count-primary metrics.
        @SerializedName("n_detected")
        public Integer nDetected;
        @SerializedName("count_score")
        public Double countScore;
        @SerializedName("count_ratio")
        public Double countRatio;
        @SerializedName("len_factor")
        public Double lenFactor;
        @SerializedName("in_band")
        public Boolean inBand;

        // Unsupervised metrics.
        public Double coverage;
        public Double straightness;
        public Boolean degenerate;

        @SerializedName("n_fibers")
        public int nFibers;

        /** Detected fibers (only present for the top-K results). */
        public List<Fiber> fibers;
    }

    /** A single detected fiber. {@code points} are region-local (x=col, y=row). */
    public static final class Fiber {
        public int id;

        /** Ordered polyline vertices; each is [x, y] in region-local pixels. */
        public double[][] points;

        /** Centre [x, y] in region-local pixels. */
        public double[] center;

        public double angle;

        @SerializedName("length_arc")
        public double lengthArc;

        @SerializedName("length_end")
        public double lengthEnd;

        public double width; // MEAN width (analysed px); also drives the widMAX filter
        @SerializedName("width_max")
        public double widthMax = Double.NaN; // max width along the fibre (analysed px)
        @SerializedName("width_std")
        public double widthStd = Double.NaN; // width std-dev along the fibre (analysed px)
        public double straightness;

        // --- Crimp / waviness (computed on the traced centreline, both tracers) -------------------
        /** Sharpest single direction change along the fibre (degrees). */
        @SerializedName("kink_max_deg")
        public double kinkMaxDeg = Double.NaN;
        /** Number of turns at or above the kink threshold (30 deg). */
        @SerializedName("kink_count")
        public double kinkCount = Double.NaN;
        /** Total turning per unit length (deg per ANALYSED px; converted to deg/unit for output). */
        public double curvature = Double.NaN;
        /** RMS perpendicular deviation from the fibre's own smoothed centreline (analysed px). */
        @SerializedName("crimp_amp")
        public double crimpAmp = Double.NaN;
        /** Mean crimp wavelength between turn reversals (analysed px). */
        @SerializedName("crimp_period")
        public double crimpPeriod = Double.NaN;

        /**
         * Angle (deg) between this fibre and an INDEPENDENT estimate of the local collagen flow,
         * from the optional anti-flow check; NaN when that estimate had no confident opinion here.
         * ~0 means the fibre runs along the collagen, toward 90 means across it. Independent
         * because it uses the structure tensor, not the field the flow tracer followed -- checking
         * a tracer against its own field only confirms it.
         */
        @SerializedName("anti_flow_deg")
        public double antiFlowDeg = Double.NaN;

        /** Index of the tile this fiber came from (for cross-tile dedup/stitch). */
        public Integer tile;

        /** TACS-1 / TACS-2 / TACS-3, or null when no boundary mask was sent. */
        public String tacs;

        @SerializedName("angle_to_tangent")
        public Double angleToTangent;

        @SerializedName("distance_to_boundary")
        public Double distanceToBoundary;
    }
}
