package io.cortexj.runtime.isolated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;
import io.cortexj.error.InferenceException;
import io.cortexj.error.RuntimeUnavailableException;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.TokenUsage;
import io.cortexj.worker.WorkerCodec;
import io.cortexj.worker.WorkerHost;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Parent-side handle to one spawned worker child JVM: owns the process, the
 * handshake, request/response demultiplexing (by request id) and stream event
 * fan-out, and the destroy ladder (destroy -> waitFor -> destroyForcibly, the
 * process hygiene pattern of ProcessLlamaServerLauncher).
 *
 * <p>A channel is single-use: once the child dies (crash, EOF, write failure)
 * the channel is dead and the provider must spawn a fresh one. Requests in
 * flight when the child dies fail with a typed {@link InferenceException}
 * whose remediation names the worker process.</p>
 */
final class WorkerChannel {

    static final String CLASSPATH_PROPERTY = "cortexj.worker.classpath";
    static final String JAVA_PROPERTY = "cortexj.worker.java";
    static final String REQUEST_TIMEOUT_PROPERTY = "cortexj.worker.request.timeout.millis";
    static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 300_000L;
    static final long HANDSHAKE_TIMEOUT_MILLIS = 60_000L;
    static final long CANCEL_TIMEOUT_MILLIS = 5_000L;
    // audit #99: a broken or hostile child writing bytes without a newline
    // must not buffer an unbounded line in the parent - that is the exact
    // failure mode crash isolation exists to contain
    static final int MAX_PROTOCOL_LINE_CHARS = 4 * 1024 * 1024;
    static final int MAX_STDERR_LINE_CHARS = 256 * 1024;

    private static final Logger LOG = Logger.getLogger(WorkerChannel.class.getName());

    private final String targetRuntimeId;
    private final Process process;
    private final java.nio.file.Path workDirectory;
    private final PrintStream writer;
    private final Map<Long, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<Long, CompletableFuture<JsonNode>>();
    private final Map<Long, StreamEvents> activeStreams = new ConcurrentHashMap<Long, StreamEvents>();
    private final AtomicLong requestIds = new AtomicLong(1);
    private final CompletableFuture<JsonNode> handshake = new CompletableFuture<JsonNode>();
    private final AtomicBoolean dead = new AtomicBoolean(false);
    private final Thread readerThread;
    private volatile String deathSummary;

    interface StreamEvents {
        void onToken(String text);

        void onComplete(FinishReason finishReason, TokenUsage usage);

        void onError(CortexJException failure);
    }

    private WorkerChannel(String targetRuntimeId, Process process, java.nio.file.Path workDirectory) throws IOException {
        this.targetRuntimeId = targetRuntimeId;
        this.process = process;
        this.workDirectory = workDirectory;
        this.writer = new PrintStream(process.getOutputStream(), false, StandardCharsets.UTF_8.name());
        this.readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "cortexj-worker-reader-" + targetRuntimeId);
        this.readerThread.setDaemon(true);
    }

    // ------------------------------------------------------------------
    // spawn + handshake
    // ------------------------------------------------------------------

    static WorkerChannel start(String targetRuntimeId) {
        String classpath = classpath();
        if (classpath == null || classpath.trim().isEmpty()) {
            throw workerUnavailable(ErrorCode.RUNTIME_UNAVAILABLE,
                    "cannot spawn an isolated worker: java.class.path is empty "
                            + "(the isolated runtime requires a classpath-launched JVM; "
                            + "modular image launches are not supported in v1)",
                    "Run the application with a standard -cp classpath, or set "
                            + CLASSPATH_PROPERTY + " to the worker classpath explicitly.");
        }
        final Process process;
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("cortexj-worker-" + targetRuntimeId + "-");
            ProcessBuilder builder = new ProcessBuilder(
                    javaExecutable(),
                    "-cp",
                    classpath,
                    WorkerHost.class.getName(),
                    targetRuntimeId);
            builder.environment().clear();
            builder.environment().putAll(allowlistedEnvironment(System.getenv()));
            builder.directory(workDir.toFile());
            builder.redirectErrorStream(false);
            process = builder.start();
        } catch (IOException e) {
            deleteRecursively(workDir);
            throw new RuntimeUnavailableException(ErrorCode.RUNTIME_UNAVAILABLE,
                    "cannot spawn an isolated worker JVM for runtime '" + targetRuntimeId + "': " + e.getMessage(),
                    "Verify that a JDK is available at java.home and the worker classpath is valid.",
                    e);
        }
        final WorkerChannel channel;
        try {
            channel = new WorkerChannel(targetRuntimeId, process, workDir);
        } catch (IOException e) {
            killProcess(process);
            deleteRecursively(workDir);
            throw new RuntimeUnavailableException(ErrorCode.RUNTIME_UNAVAILABLE,
                    "cannot attach to the worker JVM stdio for runtime '" + targetRuntimeId + "': " + e.getMessage(),
                    null, e);
        }
        Thread stderrDrain = new Thread(new Runnable() {
            @Override
            public void run() {
                drainStderr(process);
            }
        }, "cortexj-worker-stderr-" + targetRuntimeId);
        stderrDrain.setDaemon(true);
        stderrDrain.start();
        channel.readerThread.start();
        try {
            channel.awaitHandshake();
        } catch (CortexJException handshakeFailure) {
            channel.markDead("worker handshake failed: " + handshakeFailure.getMessage());
            throw handshakeFailure;
        }
        return channel;
    }

    private void awaitHandshake() {
        try {
            JsonNode hello = handshake.get(HANDSHAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            String childRuntimeId = hello.path("runtimeId").asText("");
            if (!childRuntimeId.equalsIgnoreCase(targetRuntimeId)) {
                throw workerUnavailable(ErrorCode.RUNTIME_NOT_INSTALLED,
                        "worker child hosts runtime '" + childRuntimeId
                                + "' but runtime '" + targetRuntimeId + "' was requested",
                        "Check the META-INF/services registrations on the worker classpath.");
            }
        } catch (TimeoutException timedOut) {
            throw workerUnavailable(ErrorCode.RUNTIME_UNAVAILABLE,
                    "worker child for runtime '" + targetRuntimeId
                            + "' did not complete the handshake within " + HANDSHAKE_TIMEOUT_MILLIS + " ms",
                    "Check the worker stderr output; the child may be failing to boot.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw workerUnavailable(ErrorCode.RUNTIME_UNAVAILABLE,
                    "interrupted while waiting for the worker handshake", null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof CortexJException) {
                throw (CortexJException) cause;
            }
            throw workerUnavailable(ErrorCode.RUNTIME_UNAVAILABLE,
                    "worker handshake failed: " + cause.getMessage(), null, cause);
        }
    }

    // ------------------------------------------------------------------
    // reader demux
    // ------------------------------------------------------------------

    private void readLoop() {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        try {
            String first = readCappedLine(reader, MAX_PROTOCOL_LINE_CHARS);
            if (first == CAPPED_LINE_MARKER) {
                handshake.completeExceptionally(workerUnavailable(ErrorCode.RUNTIME_UNAVAILABLE,
                        "worker child emitted an oversized handshake line (over "
                                + MAX_PROTOCOL_LINE_CHARS + " chars)",
                        "A hosted runtime is writing to stdout - stdout is reserved for the protocol."));
                return;
            }
            if (first == null) {
                handshake.completeExceptionally(workerUnavailable(ErrorCode.RUNTIME_UNAVAILABLE,
                        "worker child for runtime '" + targetRuntimeId
                                + "' exited before the handshake (exit code " + exitCode(process) + ")",
                        "Check that io.cortexj.worker.WorkerHost and the target runtime are on the "
                                + "worker classpath; worker stderr explains why the child refused to boot."));
                return;
            }
            JsonNode hello;
            try {
                hello = WorkerCodec.mapper().readTree(first);
            } catch (IOException malformed) {
                handshake.completeExceptionally(workerUnavailable(ErrorCode.RUNTIME_UNAVAILABLE,
                        "worker child emitted a malformed handshake: " + abbreviate(first),
                        "A hosted runtime is writing to stdout - stdout is reserved for the protocol."));
                return;
            }
            if (!"hello".equals(hello.path("event").asText(""))) {
                handshake.completeExceptionally(workerUnavailable(ErrorCode.RUNTIME_UNAVAILABLE,
                        "worker child did not send the hello handshake: " + abbreviate(first),
                        "A hosted runtime is writing to stdout - stdout is reserved for the protocol."));
                return;
            }
            if (hello.has("error")) {
                handshake.completeExceptionally(
                        WorkerCodec.toException(hello.path("error"),
                                ErrorCode.RUNTIME_NOT_INSTALLED,
                                "worker child could not host runtime '" + targetRuntimeId + "'"));
                return;
            }
            handshake.complete(hello);
            while (true) {
                String line = readCappedLine(reader, MAX_PROTOCOL_LINE_CHARS);
                if (line == CAPPED_LINE_MARKER) {
                    markDead("worker emitted a protocol line over " + MAX_PROTOCOL_LINE_CHARS
                            + " chars - the child is misbehaving or a hosted runtime is writing to stdout");
                    return;
                }
                if (line == null) {
                    markDead("worker stdout closed (worker exited with code " + exitCode(process) + ")");
                    return;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = WorkerCodec.mapper().readTree(line);
                } catch (IOException malformed) {
                    LOG.warning("worker emitted a non-JSON line on stdout (skipped, stdout is "
                            + "reserved for the protocol): " + abbreviate(line));
                    continue;
                }
                dispatch(node);
            }
        } catch (IOException pipeBroken) {
            markDead("worker stdout read failed: " + pipeBroken.getMessage());
        }
    }

    private void dispatch(JsonNode node) {
        if (node.has("event")) {
            long streamId = node.path("id").asLong(-1L);
            StreamEvents handler = streamId >= 0 ? activeStreams.get(streamId) : null;
            String event = node.path("event").asText("");
            if ("token".equals(event)) {
                if (handler != null) {
                    handler.onToken(node.path("text").asText(""));
                }
            } else if ("complete".equals(event)) {
                if (activeStreams.remove(streamId) != null && handler != null) {
                    handler.onComplete(WorkerCodec.finishReason(node.path("finishReason").asText("OTHER")),
                            WorkerCodec.parseUsage(node.path("usage")));
                }
            } else if ("error".equals(event)) {
                if (activeStreams.remove(streamId) != null && handler != null) {
                    handler.onError(new InferenceException(
                            node.path("code").asText(ErrorCode.INFERENCE_STREAM_FAILED),
                            node.path("message").asText("worker stream failed")));
                }
            } else {
                LOG.warning("worker emitted an unknown event (ignored): " + abbreviate(node.toString()));
            }
            return;
        }
        long id = node.path("id").asLong(-1L);
        CompletableFuture<JsonNode> future = id >= 0 ? pending.remove(id) : null;
        if (future == null) {
            LOG.fine("worker response without a pending request id " + id + " (dropped)");
            return;
        }
        future.complete(node);
    }

    // ------------------------------------------------------------------
    // requests
    // ------------------------------------------------------------------

    long nextId() {
        return requestIds.incrementAndGet();
    }

    JsonNode call(long id, String op, ObjectNode payload) {
        requireAlive();
        payload.put("id", id);
        payload.put("op", op);
        CompletableFuture<JsonNode> future = new CompletableFuture<JsonNode>();
        pending.put(Long.valueOf(id), future);
        try {
            write(payload);
        } catch (RuntimeException writeFailure) {
            // audit 2026-10-08: when the channel was already dead, markDead
            // is a no-op and the entry above would strand forever - remove it
            // (a fresh markDead already failed every other pending future)
            pending.remove(Long.valueOf(id));
            throw writeFailure;
        }
        JsonNode reply;
        try {
            reply = future.get(requestTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(Long.valueOf(id));
            throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                    "interrupted while waiting for worker op '" + op + "'");
        } catch (TimeoutException e) {
            pending.remove(Long.valueOf(id));
            throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                    "worker did not answer op '" + op + "' within "
                            + requestTimeoutMillis() + " ms (request id " + id + ")",
                    "The worker may be stuck in a long native call; raise "
                            + REQUEST_TIMEOUT_PROPERTY + " or probe the child's health.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof CortexJException) {
                throw (CortexJException) cause;
            }
            throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                    "worker request '" + op + "' failed: " + cause.getMessage(), cause);
        }
        if (!reply.path("ok").asBoolean(false)) {
            throw WorkerCodec.toException(reply.path("error"), ErrorCode.INFERENCE_FAILED,
                    "worker op '" + op + "' failed");
        }
        return reply;
    }

    long requestTimeoutMillis() {
        String configured = System.getProperty(REQUEST_TIMEOUT_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            try {
                return Long.parseLong(configured.trim());
            } catch (NumberFormatException ignored) {
                LOG.warning("ignoring invalid " + REQUEST_TIMEOUT_PROPERTY + " value: " + configured);
            }
        }
        return DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    // ------------------------------------------------------------------
    // streams
    // ------------------------------------------------------------------

    void registerStream(long streamId, StreamEvents handler) {
        activeStreams.put(Long.valueOf(streamId), handler);
    }

    void unregisterStream(long streamId) {
        activeStreams.remove(Long.valueOf(streamId));
    }

    /**
     * Fire-and-forget cancel: the worker acknowledges with an unclaimed
     * response line. Idempotent for unknown or already-finished streams.
     */
    void cancelStream(long streamId) {
        if (dead.get()) {
            return;
        }
        // audit 2026-10-10: remove the registration BEFORE writing the
        // cancel, so a complete event dispatched concurrently for the same
        // stream can never reach the subscriber after it observed
        // cancellation (the terminal races the remove, and the remove must win)
        activeStreams.remove(Long.valueOf(streamId));
        ObjectNode payload = WorkerCodec.object();
        payload.put("id", nextId());
        payload.put("op", "cancelStream");
        payload.put("streamId", streamId);
        try {
            write(payload);
        } catch (RuntimeException alreadyGone) {
            // audit 2026-10-08: dying between the check and the write is a
            // benign race - the stream is dead either way, stay silent so
            // cancel keeps its idempotent contract
        }
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    boolean isDead() {
        return dead.get();
    }

    /**
     * Graceful teardown: shutdown op, then the destroy ladder. Idempotent.
     */
    void shutdown() {
        if (dead.compareAndSet(false, true)) {
            deathSummary = "channel shut down by the provider";
            try {
                ObjectNode payload = WorkerCodec.object();
                payload.put("id", nextId());
                payload.put("op", "shutdown");
                write(payload);
            } catch (RuntimeException alreadyDead) {
                LOG.fine("worker shutdown op could not be delivered: " + alreadyDead);
            }
            try {
                // let the child exit cleanly on the shutdown op first
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    LOG.warning("worker child for runtime '" + targetRuntimeId
                            + "' ignored the shutdown op; applying the destroy ladder");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            teardownAfterDeath();
        }
    }

    void markDead(String summary) {
        if (dead.compareAndSet(false, true)) {
            deathSummary = summary;
            LOG.warning("isolated worker for runtime '" + targetRuntimeId + "' is dead: " + summary);
            InferenceException failure = childDeadFailure(summary);
            for (CompletableFuture<JsonNode> future : pending.values()) {
                future.completeExceptionally(failure);
            }
            pending.clear();
            for (Map.Entry<Long, StreamEvents> entry : activeStreams.entrySet()) {
                entry.getValue().onError(childDeadFailure(summary));
            }
            activeStreams.clear();
            teardownAfterDeath();
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean tornDown =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void teardownAfterDeath() {
        // audit 2026-10-08: shutdown() and markDead() both funnel here - run
        // the ladder once so concurrent deaths cannot double-close/kill
        if (!tornDown.compareAndSet(false, true)) {
            return;
        }
        writer.close();
        killProcess(process);
        // audit #104: the child working directory is created per worker start
        // (including crash respawns) and must not leak in $TMPDIR forever
        deleteRecursively(workDirectory);
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            java.nio.file.Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                                                                java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    java.nio.file.Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException failure)
                        throws IOException {
                    java.nio.file.Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException bestEffortIgnored) {
            // best-effort cleanup; a leaked empty temp dir beats a failed teardown
        }
    }

    private void requireAlive() {
        if (dead.get()) {
            throw childDeadFailure(deathSummary == null ? "worker channel is closed" : deathSummary);
        }
    }

    private InferenceException childDeadFailure(String summary) {
        return new InferenceException(ErrorCode.INFERENCE_FAILED,
                "the isolated worker process for runtime '" + targetRuntimeId + "' died"
                        + (summary == null ? "" : ": " + summary),
                "The worker child crashed; the next probe/open respawns it automatically. "
                        + "If this repeats, inspect the worker stderr output (drained into this "
                        + "logger) and consider updating or replacing the native runtime.");
    }

    private void write(ObjectNode payload) {
        // audit 2026-10-07 (#145): only the pipe write itself is guarded -
        // markDead() runs user callbacks and killProcess (waitFor up to 10 s)
        // and must never execute under this monitor, or every concurrent
        // call() piles up behind one dead worker (plus carrier pinning on
        // Java 21 from blocking IO under synchronized)
        boolean broken;
        synchronized (this) {
            writer.println(payload.toString());
            writer.flush();
            broken = writer.checkError();
        }
        if (broken) {
            markDead("worker stdin write failed");
            throw new RuntimeUnavailableException(ErrorCode.RUNTIME_UNAVAILABLE,
                    "cannot write to the isolated worker stdin (worker process gone?)");
        }
    }

    // ------------------------------------------------------------------
    // process plumbing (llama-server hygiene, adapted)
    // ------------------------------------------------------------------

    static String javaExecutable() {
        String explicit = System.getProperty(JAVA_PROPERTY);
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }
        String javaHome = System.getProperty("java.home", ".");
        StringBuilder path = new StringBuilder(javaHome);
        path.append(File.separatorChar).append("bin").append(File.separatorChar).append("java");
        if (File.separatorChar == '\\') {
            path.append(".exe");
        }
        return path.toString();
    }

    static String classpath() {
        String explicit = System.getProperty(CLASSPATH_PROPERTY);
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }
        return System.getProperty("java.class.path");
    }

    static Map<String, String> allowlistedEnvironment(Map<String, String> parentEnvironment) {
        Map<String, String> child = new LinkedHashMap<String, String>();
        String[] directNames = {"PATH", "HOME", "TMPDIR", "TEMP", "TMP", "LANG"};
        for (String name : directNames) {
            String value = parentEnvironment.get(name);
            if (value != null) {
                child.put(name, value);
            }
        }
        for (Map.Entry<String, String> entry : parentEnvironment.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("LC_") || name.startsWith("JAVA_")) {
                child.put(name, entry.getValue());
            }
        }
        return child;
    }

    private static final String CAPPED_LINE_MARKER = "capped";

    /**
     * audit #99: readLine() buffers a line of unbounded length - a broken or
     * hostile child writing bytes without a newline would OOM the parent,
     * which is the exact failure mode crash isolation must contain. Reads at
     * most {@code capChars} characters; returns {@link #CAPPED_LINE_MARKER}
     * (identity, not equals) when the cap was exceeded, or null on EOF.
     */
    private static String readCappedLine(BufferedReader reader, int capChars) throws IOException {
        StringBuilder line = new StringBuilder(128);
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                return line.toString();
            }
            if (c == '\r') {
                continue;
            }
            if (line.length() >= capChars) {
                // drain until the newline (bounded discard) so the stream can
                // still be used afterwards on the stderr path
                while ((c = reader.read()) != -1 && c != '\n') {
                    // discard
                }
                return CAPPED_LINE_MARKER;
            }
            line.append((char) c);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void drainStderr(Process process) {
        try {
            BufferedReader stderr = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
            // audit #99: stderr lines are capped too - a runaway native runtime
            // must not OOM the parent through the diagnostic drain
            while (true) {
                String line = readCappedLine(stderr, MAX_STDERR_LINE_CHARS);
                if (line == null) {
                    break;
                }
                if (line == CAPPED_LINE_MARKER) {
                    LOG.warning("[worker-stderr] oversized line (over " + MAX_STDERR_LINE_CHARS
                            + " chars) truncated");
                    continue;
                }
                // worker logs are drained into the parent logger, never the
                // parent environment/secrets
                LOG.fine("[worker-stderr] " + line);
            }
        } catch (IOException ignored) {
            // process exited
        }
    }

    private static void killProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static String exitCode(Process process) {
        try {
            return String.valueOf(process.exitValue());
        } catch (IllegalThreadStateException stillRunning) {
            return "unknown";
        }
    }

    private static CortexJException workerUnavailable(String code, String message, String remediation) {
        return new RuntimeUnavailableException(code, message, remediation);
    }

    private static CortexJException workerUnavailable(String code, String message,
                                                     String remediation, Throwable cause) {
        return new RuntimeUnavailableException(code, message, remediation, cause);
    }

    private static String abbreviate(String line) {
        if (line == null) {
            return "";
        }
        return line.length() <= 200 ? line : line.substring(0, 200) + "...";
    }

    // ------------------------------------------------------------------
    // test handles (package-private, used by the isolated tests only)
    // ------------------------------------------------------------------

    Process processForTesting() {
        return process;
    }

    Thread readerThreadForTesting() {
        return readerThread;
    }

    Iterator<Long> pendingIdsForTesting() {
        return pending.keySet().iterator();
    }
}
