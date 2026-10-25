package io.cortexj.kpi;

import io.cortexj.CortexJ;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TrustPolicy;
import io.cortexj.repository.local.LocalRepositoryProvider;
import io.cortexj.runtime.llamacpp.LlamaCppProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live runtime KPI harness (issue #235 / spec §124.1–§124.2) — measurements
 * with a REAL small model, downloaded into a {@code @TempDir} that JUnit
 * deletes when the test class finishes (nothing persists on the machine).
 *
 * <p>Model: TinyLlama 1.1B Chat, Q2_K (~483 MB) from Hugging Face.
 *
 * <p>Measures:
 * <ul>
 *   <li>download seconds (network leg of time-to-first-local-response);</li>
 *   <li>time-to-first-response: cold path — local manifest → plan → llama.cpp
 *       server launch → load → first response;</li>
 *   <li>latency + throughput: full platform path vs direct llama-server HTTP
 *       (same JVM, same prompt, same seed/temperature/token budget);</li>
 *   <li>framework overhead ratio + throughput degradation (spec targets §124.2:
 *       &lt;2% / &lt;5%);</li>
 *   <li>JVM heap growth from framework + model load (spec target §124.2:
 *       framework overhead &lt;100 MB without the model; model heap reported
 *       separately since it is model-owned, not framework-owned).</li>
 * </ul>
 *
 * <p>Requires {@code llama-server} on PATH or
 * {@code -Dcortexj.llamacpp.server=/path/to/llama-server}.
 *
 * <p>Gated with @Tag("perf") — never in default CI (needs a real download);
 * run explicitly: {@code mvn test -Pperf} or {@code -Dgroups=perf}.
 */
@Tag("perf")
class LiveRuntimeKpiTest {

    private static final String MODEL_REPO =
            "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/";
    private static final String MODEL_FILE = "tinyllama-1.1b-chat-v1.0.Q2_K.gguf";
    private static final String MODEL_ID = "tinyllama-1.1b-chat-v1.0-q2k";
    private static final String PROMPT = "Once upon a time, in a land far away,";
    private static final int MAX_TOKENS = 24;
    private static final int WARMUP = 3;
    private static final int RUNS = 20;

    @TempDir
    static Path modelsDir;
    @TempDir
    static Path workDir;

    private static long downloadMillis;
    private static Path modelArtifact;

    @BeforeAll
    static void downloadAndPrepare() throws Exception {
        modelArtifact = modelsDir.resolve(MODEL_FILE);
        long started = System.nanoTime();
        download(MODEL_REPO + MODEL_FILE, modelArtifact);
        downloadMillis = (System.nanoTime() - started) / 1_000_000L;

        String manifest = "{"
                + "\"id\":\"" + MODEL_ID + "\","
                + "\"revision\":\"q2_k\","
                + "\"architecture\":\"llama\","
                + "\"license\":\"llama2\","
                + "\"contextLength\":2048,"
                + "\"parameterCount\":1100000000,"
                + "\"tasks\":[\"text-generation\"],"
                + "\"artifacts\":[{\"file\":\"" + MODEL_FILE + "\","
                + "\"format\":\"GGUF\",\"quantization\":\"q2_k\","
                + "\"size\":" + Files.size(modelArtifact) + ","
                + "\"sha256\":\"" + sha256Hex(modelArtifact) + "\"}]}";
        Files.write(modelsDir.resolve("cortexj-model.json"),
                manifest.getBytes(StandardCharsets.UTF_8));
        System.out.println("[kpi] model downloaded: " + Files.size(modelArtifact) / 1024 / 1024
                + " MB in " + downloadMillis + " ms");
    }

    @Test
    void platformVersusDirectEngine() throws Exception {
        long heapBefore = heapUsed();

        // ---- platform leg (full CortexJ path) ----
        Leg platformLeg;
        long timeToFirstMillis;
        try (CortexJ platform = CortexJ.builder()
                .runtimeProvider(new LlamaCppProvider())
                .repositoryProvider(new LocalRepositoryProvider())
                .workingDirectory(workDir)
                .trustPolicy(TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING)
                .build()) {
            long cold = System.nanoTime();
            ModelHandle handle = platform.models().load(ModelReference.of(modelsDir.toAbsolutePath().toString()));
            TextGenerationCapability generation = handle.capability(TextGenerationCapability.class);
            GenerationResponse first = generation.generate(request());
            timeToFirstMillis = (System.nanoTime() - cold) / 1_000_000L;
            System.out.println("[kpi] first response after " + timeToFirstMillis
                    + " ms (load+plan+server+first tokens): \"" + snippet(first) + "\"");

            for (int i = 0; i < WARMUP; i++) {
                generation.generate(request());
            }
            List<Long> latencies = new ArrayList<Long>();
            List<Long> tokensPerRun = new ArrayList<Long>();
            for (int i = 0; i < RUNS; i++) {
                long t0 = System.nanoTime();
                GenerationResponse response = generation.generate(request());
                latencies.add(System.nanoTime() - t0);
                tokensPerRun.add(response.usage().isKnown()
                        ? response.usage().outputTokens() : -1L);
            }
            long heapAfterLoad = heapUsed();
            platformLeg = new Leg("platform", latencies, tokensPerRun,
                    heapAfterLoad - heapBefore);
            handle.close();
        }

        // ---- direct leg (same JVM, llama-server HTTP) ----
        int port = freePort();
        String binary = serverBinary();
        Process server = new ProcessBuilder(binary,
                "-m", modelArtifact.toString(),
                "-c", "2048",
                "--host", "127.0.0.1",
                "--port", String.valueOf(port))
                .redirectErrorStream(true)
                .start();
        try {
            waitForHealth(port);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
            for (int i = 0; i < WARMUP; i++) {
                postCompletion(client, port);
            }
            List<Long> latencies = new ArrayList<Long>();
            List<Long> tokensPerRun = new ArrayList<Long>();
            for (int i = 0; i < RUNS; i++) {
                long t0 = System.nanoTime();
                String body = postCompletion(client, port);
                latencies.add(System.nanoTime() - t0);
                tokensPerRun.add(extractLong(body, "tokens_predicted"));
            }
            Leg directLeg = new Leg("direct", latencies, tokensPerRun, 0L);

            // ---- report ----
            String report = report(platformLeg, directLeg, timeToFirstMillis,
                    heapBefore);
            Path reportFile = Path.of("target", "kpi-report.txt");
            Files.createDirectories(reportFile.getParent());
            Files.write(reportFile, report.getBytes(StandardCharsets.UTF_8));
            System.out.println(report);

            assertTrue(timeToFirstMillis < 600_000L,
                    "time-to-first-response must stay under the §124.1 target (10 min), was "
                            + timeToFirstMillis + " ms");
            double overhead = platformLeg.p50() / Math.max(1.0, directLeg.p50()) - 1.0;
            assertTrue(overhead <= 0.35,
                    "framework latency overhead too large on this machine: "
                            + String.format(Locale.ROOT, "%.1f%%", overhead * 100));
            double tpsRatio = platformLeg.medianTokensPerSecond()
                    / Math.max(1.0, directLeg.medianTokensPerSecond());
            assertTrue(tpsRatio >= 0.70,
                    "framework throughput collapsed relative to direct engine: ratio "
                            + String.format(Locale.ROOT, "%.2f", tpsRatio));
        } finally {
            server.destroyForcibly();
            server.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void noteModelCleanup() {
        System.out.println("[kpi] model lives in " + modelsDir
                + " — JUnit @TempDir deletes it when this class finishes;"
                + " no model or cache files persist on this machine.");
    }

    private static String report(Leg platform, Leg direct, long timeToFirstMillis,
            long heapBefore) {
        double overhead = platform.p50() / Math.max(1.0, direct.p50()) - 1.0;
        double tpsDegradation = 1.0 - platform.medianTokensPerSecond()
                / Math.max(1.0, direct.medianTokensPerSecond());
        StringBuilder sb = new StringBuilder();
        sb.append("=== CortexJ Runtime KPI report (issue #235, spec §124.1–§124.2) ===\n");
        sb.append("model:        TinyLlama-1.1B-Chat Q2_K, prompt \"").append(PROMPT).append("\", ")
                .append("maxTokens=").append(MAX_TOKENS)
                .append(", runs=").append(RUNS).append(" (warmup=").append(WARMUP).append(")\n");
        sb.append("download:     ").append(downloadMillis).append(" ms (")
                .append(modelSizeMb()).append(" MB)\n");
        sb.append(String.format(Locale.ROOT, "time-to-first-response: %.3f s (spec §124.1 target: <= 600 s)%n",
                timeToFirstMillis / 1000.0));
        sb.append(String.format(Locale.ROOT, "platform leg: p50=%d ms p95=%d ms medianTokens=%d tps=%.2f heapDelta=%d MB%n",
                platform.p50(), platform.p95(), platform.medianTokens(),
                platform.medianTokensPerSecond(), platform.heapDelta() / 1024 / 1024));
        sb.append(String.format(Locale.ROOT, "direct leg:   p50=%d ms p95=%d ms medianTokens=%d tps=%.2f%n",
                direct.p50(), direct.p95(), direct.medianTokens(),
                direct.medianTokensPerSecond()));
        sb.append(String.format(Locale.ROOT, "framework overhead (latency): %+.1f%%   (spec §124.2 target: < +2%%)%n",
                overhead * 100));
        sb.append(String.format(Locale.ROOT, "framework throughput degradation: %.1f%%   (spec §124.2 target: < 5%%)%n",
                tpsDegradation * 100));
        sb.append(String.format(Locale.ROOT, "platform JVM heap before framework: %d MB; after platform+model: +%d MB%n",
                heapBefore / 1024 / 1024, platform.heapDelta() / 1024 / 1024));
        sb.append("note: model memory is model-owned (GGUF weights in the engine process);\n");
        sb.append("      §124.2's <100MB framework-overhead figure targets the framework-own\n");
        sb.append("      allocation, measured by the fake-engine dispatch harness\n");
        sb.append("      (PlatformOverheadTest, @Tag perf) — this report bounds the live path.\n");
        sb.append("=== end ===\n");
        return sb.toString();
    }

    private static long modelSizeMb() {
        try {
            return Files.size(modelArtifact) / 1024 / 1024;
        } catch (IOException e) {
            return -1L;
        }
    }

    private static GenerationRequest request() {
        return GenerationRequest.builder()
                .prompt(PROMPT)
                .maxTokens(MAX_TOKENS)
                .temperature(0.0)
                .seed(42L)
                .build();
    }

    private static final class Leg {
        private final String name;
        private final List<Long> latenciesNanos;
        private final List<Long> tokensPerRun;
        private final long heapDelta;

        Leg(String name, List<Long> latenciesNanos, List<Long> tokensPerRun,
                long heapDelta) {
            this.name = name;
            this.latenciesNanos = latenciesNanos;
            this.tokensPerRun = tokensPerRun;
            this.heapDelta = heapDelta;
        }

        long p50() {
            List<Long> sorted = new ArrayList<Long>(latenciesNanos);
            java.util.Collections.sort(sorted);
            return sorted.get(sorted.size() / 2) / 1_000_000L;
        }

        long p95() {
            List<Long> sorted = new ArrayList<Long>(latenciesNanos);
            java.util.Collections.sort(sorted);
            return sorted.get((int) (sorted.size() * 0.95)) / 1_000_000L;
        }

        long medianTokens() {
            return median(tokensPerRun);
        }

        double medianTokensPerSecond() {
            double tps = medianTokens() / Math.max(0.001, p50() / 1000.0);
            return tps;
        }

        long heapDelta() {
            return heapDelta;
        }

        private static long median(List<Long> values) {
            if (values.isEmpty() || values.get(0) == null) {
                return 0L;
            }
            long sum = 0;
            int known = 0;
            for (Long value : values) {
                if (value > 0) {
                    sum += value;
                    known++;
                }
            }
            return known == 0 ? 0L : sum / known;
        }
    }

    private static String postCompletion(HttpClient client, int port) throws Exception {
        String body = "{\"prompt\":\"" + PROMPT + "\",\"n_predict\":" + MAX_TOKENS
                + ",\"temperature\":0.0,\"seed\":42,\"stream\":false}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/completion"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("llama-server returned "
                    + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static void waitForHealth(int port) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException retrying) {
                // server still starting up
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }
        throw new IllegalStateException("llama-server did not become healthy on port " + port);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String serverBinary() {
        String property = System.getProperty("cortexj.llamacpp.server");
        if (property != null && !property.trim().isEmpty()) {
            return property;
        }
        return "llama-server";
    }

    private static long extractLong(String json, String field) {
        String marker = "\"" + field + "\":";
        int at = json.indexOf(marker);
        if (at < 0) {
            return -1L;
        }
        int start = at + marker.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == 'e'
                || json.charAt(end) == '-' || json.charAt(end) == '+')) {
            end++;
        }
        if (start == end) {
            return -1L;
        }
        return (long) Double.parseDouble(json.substring(start, end));
    }

    private static String snippet(GenerationResponse response) {
        String text = response.text();
        return text == null ? "" : text.substring(0, Math.min(40, text.length()));
    }

    private static long heapUsed() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void download(String url, Path target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("download failed: HTTP "
                    + response.statusCode() + " for " + url);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, target);
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
