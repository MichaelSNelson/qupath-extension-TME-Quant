package qupath.ext.tmequant;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.projects.Project;

/**
 * A lightweight, append-as-you-go run log written into the project directory, so a long
 * TME-Quant run's progress survives even if the server console scrolls away or the run
 * crashes. Each line is timestamped and flushed immediately.
 *
 * <p>Logs live under {@code <projectDir>/logs/fiber-analysis/}. The dialog pairs a
 * client-side log ({@code fiber-run-<stamp>-client.txt}, written here) with the FIRE
 * server's own transcript ({@code fiber-run-<stamp>-server.log}, written Python-side)
 * by sharing one {@link #stamp()} per run.</p>
 *
 * <p>{@link #open} returns a shared NO-OP (writes nothing) when the target directory is
 * null — i.e. there is no project, or the user turned logging off — so callers never
 * have to null-check.</p>
 */
final class RunLog {

    private static final Logger logger = LoggerFactory.getLogger(RunLog.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter RUN_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Shared no-op used whenever there is nothing to write into. */
    static final RunLog NOOP = new RunLog(null, null);

    private final Writer writer; // null => no-op
    private final Path file;     // null => no-op
    private boolean broken = false;

    private RunLog(Writer writer, Path file) {
        this.writer = writer;
        this.file = file;
    }

    /** A run identifier (timestamp) shared by a run's client + server log filenames. */
    static String stamp() {
        return LocalDateTime.now().format(RUN_STAMP);
    }

    private static final DateTimeFormatter DAY_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Append one run to {@code runs.json} beside the logs: a machine-readable index from the
     * numeric "Run ID" measurement stamped on every object to that run's settings and log files.
     *
     * <p>Without this, a detection in the hierarchy cannot be traced back to the settings that
     * produced it -- which is how a question like "was Two-pass on for these?" becomes
     * unanswerable. Written as one JSON object per line (JSON Lines) so appending never has to
     * re-read or re-parse what is already there, and a half-written line cannot corrupt the rest.</p>
     */
    static void appendRunIndex(Path dir, String stamp, double runId, String settings) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            String rec = "{\"run_id\":" + String.format("%.0f", runId)
                    + ",\"stamp\":\"" + stamp + "\""
                    + ",\"client_log\":\"fiber-run-" + stamp + "-client.txt\""
                    + ",\"server_log\":\"fiber-run-" + stamp + "-server.log\""
                    + ",\"settings\":\"" + settings.replace("\\","\\\\").replace("\"","\\\"")
                        .replace("\n","\\n") + "\"}\n";
            Files.writeString(dir.resolve("runs.json"), rec, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Could not append to runs.json: {}", e.getMessage());
        }
    }

    /** A date-only stamp for rolling logs that append all day into one file (e.g. previews). */
    static String dayStamp() {
        return LocalDateTime.now().format(DAY_STAMP);
    }

    /**
     * The directory logs for {@code project} belong in ({@code <projectDir>/logs/fiber-analysis}),
     * or null when there is no project / its path can't be resolved. Not created here.
     */
    static Path projectLogDir(Project<?> project) {
        if (project == null) {
            return null;
        }
        try {
            Path projPath = project.getPath();
            if (projPath == null || projPath.getParent() == null) {
                return null;
            }
            return projPath.getParent().resolve("logs").resolve("fiber-analysis");
        } catch (RuntimeException ex) {
            logger.warn("Could not resolve project log dir: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Open a run log at {@code dir/fileName}. Returns the shared no-op (writes nothing)
     * when {@code dir} is null or the file can't be created.
     *
     * @param dir      target directory, or null to disable logging
     * @param fileName log file name
     * @param kind     short tag for the run, e.g. "Add to image" or "Batch"
     * @param header   free-form header lines describing the run (image, region, params)
     */
    static RunLog open(Path dir, String fileName, String kind, String header) {
        if (dir == null) {
            return NOOP;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            RunLog log = new RunLog(w, file);
            log.raw("=== TME-Quant " + kind + " (client) — "
                    + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " ===");
            if (header != null && !header.isBlank()) {
                for (String line : header.split("\n")) {
                    log.raw(line);
                }
            }
            logger.info("Run log: writing to {}", file);
            return log;
        } catch (IOException | RuntimeException ex) {
            logger.warn("Could not open run log (continuing without one): {}", ex.getMessage());
            return NOOP;
        }
    }

    /** The log file, or null for a no-op log. */
    Path file() {
        return file;
    }

    /** Append a timestamped line and flush, so the log is current mid-run and survives a crash. */
    synchronized void line(String message) {
        raw(LocalDateTime.now().format(STAMP) + "  " + (message == null ? "" : message));
    }

    private synchronized void raw(String text) {
        if (writer == null || broken) {
            return;
        }
        try {
            writer.write(text);
            writer.write(System.lineSeparator());
            writer.flush();
        } catch (IOException ex) {
            broken = true; // stop trying; don't spam the QuPath log
            logger.warn("Run log write failed; disabling it for this run: {}", ex.getMessage());
        }
    }

    /** Close the log; safe to call on a no-op log or more than once. */
    synchronized void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException ex) {
            logger.warn("Run log close failed: {}", ex.getMessage());
        }
    }
}
