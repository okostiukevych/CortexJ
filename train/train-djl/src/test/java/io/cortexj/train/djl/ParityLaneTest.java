package io.cortexj.train.djl;

import ai.djl.ndarray.types.Shape;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.train.djl.models.ArchitectureConfig;
import io.cortexj.train.djl.models.ArchitectureConfigs;
import io.cortexj.train.djl.models.DjlHfTokenizer;
import io.cortexj.train.djl.models.GenericDecoderBlock;
import io.cortexj.train.djl.models.HfWeightLoader;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The mathematical honesty gate (design doc): our Java blocks must
 * reproduce the reference runtime's greedy continuations on the real
 * model BEFORE any trained-adapter claims. Runs only when the operator
 * provides the model directory and the llama-server binary (artifacts
 * are operator-owned and stay).
 */
class ParityLaneTest {

    private static final List<String> PROMPTS = Arrays.asList(
            "The capital of France is",
            "One plus one equals",
            "Water boils at",
            "The first president of the United States was",
            "Cats are",
            "The sun rises in the",
            "Two times two is",
            "The largest planet is",
            "Ice melts when",
            "Java is a");

    @Test
    void greedyContinuationsAgreeWithTheReferenceRuntime() throws Exception {
        String dirEnv = System.getenv("CORTEXJ_PARITY_MODEL_DIR");
        String binEnv = System.getenv("CORTEXJ_LLAMA_SERVER_BIN");
        assumeTrue(dirEnv != null && Files.isDirectory(Path.of(dirEnv)),
                "CORTEXJ_PARITY_MODEL_DIR not set - parity lane skipped loudly");
        assumeTrue(binEnv != null && Files.isRegularFile(Path.of(binEnv)),
                "CORTEXJ_LLAMA_SERVER_BIN not set - parity lane skipped loudly");
        assumeTrue(MaskedCausalCrossEntropyTest.enginePresent(),
                "no DJL PyTorch engine - parity lane skipped loudly");

        Path dir = Path.of(dirEnv);
        Path gguf = dir.resolve("reference.gguf");
        assumeTrue(Files.isRegularFile(gguf),
                "reference.gguf missing - convert the F16 base with the existing "
                        + "safetensors->GGUF converter first");

        ArchitectureConfig config = ArchitectureConfigs.parse(dir.resolve("config.json"));
        DjlHfTokenizer tokenizer = DjlHfTokenizer.load(dir.resolve("tokenizer.json"));
        GenericDecoderBlock block = GenericDecoderBlock.build(config,
                Collections.<String>emptySet(), 1, 1.0f);
        LmTrainingLoop loop = LmTrainingLoop.create(block, 1e-4f, 1, 0.0f);
        try {
            loop.initialize(512);
            block.loadWeights(HfWeightLoader.load(dir), loop.modelManager());

            Process server = new ProcessBuilder(binEnv, "-m", gguf.toString(),
                    "--host", "127.0.0.1", "--port", "18099", "--threads",
                    String.valueOf(Runtime.getRuntime().availableProcessors()))
                    .redirectErrorStream(true)
                    .start();
            try {
                assumeTrue(waitHealthy(Duration.ofMinutes(5)),
                        "llama-server did not become healthy - parity lane skipped loudly");
                int matched = 0;
                StringBuilder report = new StringBuilder("parity report:\n");
                for (String prompt : PROMPTS) {
                    int[] continuation = greedy(loop, tokenizer, prompt, 16);
                    String ours = tokenizer.decode(continuation).trim();
                    String reference = referenceCompletion(prompt, 16).trim();
                    boolean same = ours.equals(reference);
                    if (same) {
                        matched++;
                    } else {
                        report.append("  DIFF prompt=\"").append(prompt)
                                .append("\" ours=\"").append(ours)
                                .append("\" reference=\"").append(reference).append("\"\n");
                    }
                }
                System.out.println(report);
                assertTrue(matched >= PROMPTS.size() - 1,
                        "at most one prompt may diverge (fp drift tolerance), matched="
                                + matched + " of " + PROMPTS.size() + "\n" + report);
            } finally {
                server.destroy();
                server.waitFor();
            }
        } finally {
            loop.close();
        }
    }

    private static int[] greedy(LmTrainingLoop loop, DjlHfTokenizer tokenizer,
                                String prompt, int steps) {
        int[] promptIds = tokenizer.encode(prompt);
        List<Integer> ids = new ArrayList<Integer>();
        for (int id : promptIds) {
            ids.add(id);
        }
        int[] continuation = new int[steps];
        for (int s = 0; s < steps; s++) {
            int[] current = new int[ids.size()];
            for (int i = 0; i < current.length; i++) {
                current[i] = ids.get(i);
            }
            ai.djl.ndarray.NDList logits = loop.evalForward(current);
            float[] lastPosition = logits.singletonOrThrow()
                    .get("0, " + (current.length - 1) + ", :").toFloatArray();
            int best = 0;
            for (int v = 1; v < lastPosition.length; v++) {
                if (lastPosition[v] > lastPosition[best]) {
                    best = v;
                }
            }
            continuation[s] = best;
            ids.add(best);
        }
        return continuation;
    }

    private static String referenceCompletion(String prompt, int tokens) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String body = mapper.writeValueAsString(java.util.Map.of(
                "prompt", prompt,
                "n_predict", tokens,
                "temperature", 0.0,
                "seed", 1,
                "stream", false,
                "cache_prompt", false));
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:18099/completion"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode content = mapper.readTree(response.body());
        return content.path("content").asText("");
    }

    private static boolean waitHealthy(Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        HttpClient client = HttpClient.newHttpClient();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:18099/health"))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build();
                if (client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return true;
                }
            } catch (Exception stillLoading) {
                Thread.sleep(1000);
            }
        }
        return false;
    }
}
