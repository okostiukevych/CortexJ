package io.cortexj.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;
import io.cortexj.error.InferenceException;
import io.cortexj.error.RuntimeUnavailableException;
import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.runtime.DeviceDescriptor;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Child-JVM host for isolated native execution (spec section 127, issue #88).
 *
 * <p>Hosts exactly one {@link RuntimeProvider} (selected by runtime id, loaded
 * via {@link ServiceLoader}) and serves a newline-delimited JSON protocol on
 * stdin/stdout (UTF-8, one request per line, one response per line, streaming
 * events interleaved as {@code {"event":...}} lines). Worker diagnostics go to
 * stderr - the parent process drains them. The worker never writes anything
 * else to stdout: hosted runtimes that print to stdout corrupt the protocol
 * (see docs/isolated-worker.md).</p>
 *
 * <p>Robustness contract: a malformed line yields an error response carrying
 * the request id (or id 0), never a crash loop; stdin EOF or the {@code
 * shutdown} op closes the hosted runtime and exits cleanly.</p>
 */
public final class WorkerHost {

    public static final String PROTOCOL = "1";
    public static final String HELLO_EVENT = "hello";

    private static final Logger LOG = Logger.getLogger(WorkerHost.class.getName());

    private final RuntimeProvider provider;
    private final Object outputLock = new Object();
    private final PrintSink out;
    private final AtomicLong modelCounter = new AtomicLong();
    private final Map<String, RuntimeModel> models =
            Collections.synchronizedMap(new LinkedHashMap<String, RuntimeModel>());
    private final Map<Long, ActiveStream> activeStreams = new ConcurrentHashMap<Long, ActiveStream>();
    private volatile ModelRuntime hosted;
    private volatile boolean stopped;

    WorkerHost(RuntimeProvider provider, PrintSink out) {
        this.provider = provider;
        this.out = out;
    }

    public static void main(String[] args) {
        if (args.length < 1 || args[0].trim().isEmpty()) {
            System.err.println("usage: WorkerHost <runtimeId>");
            System.exit(2);
            return;
        }
        String runtimeId = args[0].trim();
        PrintSink out = new StdoutSink();
        RuntimeProvider provider;
        try {
            provider = findProvider(runtimeId);
        } catch (CortexJException notFound) {
            emitHelloError(out, runtimeId, notFound);
            LOG.severe("worker cannot host runtime '" + runtimeId + "': " + notFound.getMessage());
            System.exit(1);
            return;
        }
        emitHello(out, provider.id().value());
        try {
            new WorkerHost(provider, out).serve(new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        } catch (Throwable fatal) {
            LOG.log(Level.SEVERE, "worker host loop failed: " + fatal, fatal);
            System.exit(1);
            return;
        }
        System.exit(0);
    }

    /**
     * Finds the RuntimeProvider with the requested id via ServiceLoader,
     * mirroring the resilient iteration pattern of core SpiRegistries (a
     * broken provider entry is skipped, not fatal).
     */
    public static RuntimeProvider findProvider(String runtimeId) {
        ServiceLoader<RuntimeProvider> loader =
                ServiceLoader.load(RuntimeProvider.class, WorkerHost.class.getClassLoader());
        Iterator<RuntimeProvider> iterator = loader.iterator();
        while (true) {
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                RuntimeProvider candidate = iterator.next();
                if (candidate != null && candidate.id() != null
                        && candidate.id().value().equalsIgnoreCase(runtimeId)) {
                    return candidate;
                }
            } catch (Throwable entryFailure) {
                LOG.warning("skipping unloadable RuntimeProvider entry: " + entryFailure);
            }
        }
        throw new RuntimeUnavailableException(ErrorCode.RUNTIME_NOT_INSTALLED,
                "no RuntimeProvider with id '" + runtimeId + "' is registered on the worker classpath",
                "Add the runtime module (and its META-INF/services registration) to the worker classpath.");
    }

    void serve(BufferedReader in) throws IOException {
        while (!stopped) {
            String line = in.readLine();
            if (line == null) {
                LOG.info("worker stdin closed - shutting down");
                break;
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            handleLine(line);
        }
        cleanup();
    }

    private void handleLine(String line) {
        JsonNode root;
        try {
            root = WorkerCodec.mapper().readTree(line);
        } catch (IOException malformed) {
            respondError(0L, workerError(ErrorCode.CONF_INVALID,
                    "malformed protocol line: " + malformed.getMessage()));
            return;
        }
        if (!root.isObject()) {
            respondError(0L, workerError(ErrorCode.CONF_INVALID, "protocol line must be a JSON object"));
            return;
        }
        long id = root.path("id").asLong(0L);
        String op = root.path("op").asText("");
        try {
            dispatch(id, op, root);
        } catch (CortexJException typed) {
            respondError(id, typed);
        } catch (Throwable unexpected) {
            LOG.log(Level.WARNING, "op '" + op + "' failed: " + unexpected, unexpected);
            respondError(id, workerError(ErrorCode.INFERENCE_FAILED,
                    "worker op '" + op + "' failed: " + unexpected));
        }
    }

    private void dispatch(long id, String op, JsonNode root) {
        if ("probe".equals(op)) {
            handleProbe(id, root);
        } else if ("open".equals(op)) {
            handleOpen(id, root);
        } else if ("load".equals(op)) {
            handleLoad(id, root);
        } else if ("execute".equals(op)) {
            handleExecute(id, root);
        } else if ("generate".equals(op)) {
            handleGenerate(id, root);
        } else if ("embed".equals(op)) {
            handleEmbed(id, root);
        } else if ("stream".equals(op)) {
            handleStream(id, root);
        } else if ("cancelStream".equals(op)) {
            handleCancelStream(id, root);
        } else if ("closeModel".equals(op)) {
            handleCloseModel(id, root);
        } else if ("close".equals(op)) {
            handleClose(id);
        } else if ("shutdown".equals(op)) {
            handleShutdown(id);
        } else {
            throw workerError(ErrorCode.CONF_INVALID, "unknown op '" + op + "'");
        }
    }

    // ------------------------------------------------------------------
    // ops
    // ------------------------------------------------------------------

    private void handleProbe(long id, JsonNode root) {
        boolean offline = root.path("offline").asBoolean(false);
        Map<String, String> configuration = stringMap(root.path("configuration"));
        RuntimeProbeContext context = new RuntimeProbeContext(
                localEnvironment(), localHardware(), offline, configuration);
        RuntimeProbeResult result = provider.probe(context);
        ObjectNode response = ok(id);
        response.set("descriptor", WorkerCodec.runtimeDescriptor(provider.descriptor()));
        response.put("available", result.isUsable());
        response.put("health", result.health().name());
        response.put("message", result.message());
        if (result.error() != null) {
            response.set("error", WorkerCodec.error(result.error()));
        }
        emit(response);
    }

    private void handleOpen(long id, JsonNode root) {
        ModelRuntime current = hosted;
        if (current != null) {
            throw workerError(ErrorCode.RUNTIME_LOAD_FAILED,
                    "worker already hosts an open runtime session; close it before opening another");
        }
        RuntimeOpenRequest request = new RuntimeOpenRequest(
                WorkerCodec.parseModelDescriptor(root.path("descriptor")),
                WorkerCodec.parseArtifact(root.path("artifact")),
                artifactPath(root),
                device(root),
                objectMap(root.path("options")),
                stringMap(root.path("headers")),
                localEnvironment(),
                localHardware(),
                null,
                io.cortexj.observability.PlatformEventListener.NOOP);
        hosted = provider.open(request);
        ObjectNode response = ok(id);
        response.put("runtimeOpened", true);
        emit(response);
    }

    private void handleLoad(long id, JsonNode root) {
        ModelRuntime runtime = hosted;
        if (runtime == null) {
            throw workerError(ErrorCode.RUNTIME_UNAVAILABLE,
                    "no runtime session is open; send 'open' before 'load'");
        }
        RuntimeModelRequest request = new RuntimeModelRequest(
                WorkerCodec.parseModelDescriptor(root.path("descriptor")),
                WorkerCodec.parseArtifact(root.path("artifact")),
                artifactPath(root),
                device(root),
                objectMap(root.path("options")));
        RuntimeModel model = runtime.load(request);
        String token = "m" + modelCounter.incrementAndGet();
        models.put(token, model);
        ObjectNode response = ok(id);
        response.put("modelToken", token);
        response.set("capabilities", WorkerCodec.modelCapabilities(model.capabilities()));
        response.put("textGeneration", model instanceof TextGenerationCapability);
        response.put("embedding", model instanceof EmbeddingCapability);
        emit(response);
    }

    private void handleExecute(long id, JsonNode root) {
        RuntimeModel model = model(root);
        InferenceRequest request = WorkerCodec.parseInferenceRequest(root.path("request"));
        InferenceResponse response = model.execute(request);
        ObjectNode reply = ok(id);
        reply.set("response", WorkerCodec.inferenceResponse(response));
        emit(reply);
    }

    private void handleGenerate(long id, JsonNode root) {
        RuntimeModel model = model(root);
        if (!(model instanceof TextGenerationCapability)) {
            throw unsupportedTask("generate");
        }
        GenerationRequest request = WorkerCodec.parseGenerationRequest(root.path("request"));
        GenerationResponse response = ((TextGenerationCapability) model).generate(request);
        ObjectNode reply = ok(id);
        reply.set("response", WorkerCodec.generationResponse(response));
        emit(reply);
    }

    private void handleEmbed(long id, JsonNode root) {
        RuntimeModel model = model(root);
        if (!(model instanceof EmbeddingCapability)) {
            throw unsupportedTask("embed");
        }
        EmbeddingRequest request = WorkerCodec.parseEmbeddingRequest(root.path("request"));
        EmbeddingResponse response = ((EmbeddingCapability) model).embed(request);
        ObjectNode reply = ok(id);
        reply.set("response", WorkerCodec.embeddingResponse(response));
        emit(reply);
    }

    private void handleStream(long id, JsonNode root) {
        RuntimeModel model = model(root);
        if (!(model instanceof TextGenerationCapability)) {
            throw unsupportedTask("stream");
        }
        GenerationRequest request = WorkerCodec.parseGenerationRequest(root.path("request"));
        TokenStream stream = ((TextGenerationCapability) model).stream(request);
        final long streamId = id;
        final String modelToken = root.path("modelToken").asText("");
        final ActiveStream active = new ActiveStream(modelToken);
        active.stream = stream;
        activeStreams.put(streamId, active);
        try {
            stream.subscribe(new TokenConsumer() {
                @Override
                public void onToken(Token token) {
                    ObjectNode event = WorkerCodec.object();
                    event.put("event", "token");
                    event.put("id", streamId);
                    event.put("text", token.text());
                    emit(event);
                }

                @Override
                public void onComplete(StreamCompletion completion) {
                    activeStreams.remove(streamId);
                    ObjectNode event = WorkerCodec.object();
                    event.put("event", "complete");
                    event.put("id", streamId);
                    event.put("finishReason", completion.finishReason().name());
                    event.set("usage", WorkerCodec.usage(completion.usage()));
                    emit(event);
                }

                @Override
                public void onError(io.cortexj.error.CortexJException error) {
                    activeStreams.remove(streamId);
                    ObjectNode event = WorkerCodec.object();
                    event.put("event", "error");
                    event.put("id", streamId);
                    event.put("code", error.code());
                    event.put("message", error.getMessage() == null ? "" : error.getMessage());
                    emit(event);
                }
            });
        } catch (Throwable subscribeFailure) {
            activeStreams.remove(streamId);
            throw subscribeFailure;
        }
        emit(ok(streamId));
    }

    private void handleCancelStream(long id, JsonNode root) {
        long streamId = root.path("streamId").asLong(-1L);
        ActiveStream active = activeStreams.remove(streamId);
        if (active != null) {
            active.stream.close();
        }
        emit(ok(id));
    }

    private void handleCloseModel(long id, JsonNode root) {
        String token = root.path("modelToken").asText("");
        RuntimeModel model = models.remove(token);
        if (model != null) {
            closeModelQuietly(token, model);
        }
        emit(ok(id));
    }

    private void handleClose(long id) {
        closeSessionQuietly();
        ObjectNode response = ok(id);
        response.put("runtimeClosed", true);
        emit(response);
    }

    private void handleShutdown(long id) {
        emit(ok(id));
        stopped = true;
    }

    // ------------------------------------------------------------------
    // lifecycle helpers
    // ------------------------------------------------------------------

    private void cleanup() {
        closeSessionQuietly();
    }

    private void closeSessionQuietly() {
        ModelRuntime runtime = hosted;
        hosted = null;
        for (Map.Entry<Long, ActiveStream> entry : activeStreams.entrySet()) {
            try {
                entry.getValue().stream.close();
            } catch (Throwable ignored) {
                // aborting an active stream must not block shutdown
            }
        }
        activeStreams.clear();
        synchronized (models) {
            for (Map.Entry<String, RuntimeModel> entry : models.entrySet()) {
                try {
                    entry.getValue().close();
                } catch (Throwable ignored) {
                }
            }
            models.clear();
        }
        if (runtime != null) {
            try {
                runtime.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void closeModelQuietly(String token, RuntimeModel model) {
        for (Map.Entry<Long, ActiveStream> entry : activeStreams.entrySet()) {
            if (entry.getValue().modelToken.equals(token)) {
                activeStreams.remove(entry.getKey());
                try {
                    entry.getValue().stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            model.close();
        } catch (Throwable ignored) {
        }
    }

    private RuntimeModel model(JsonNode root) {
        String token = root.path("modelToken").asText("");
        RuntimeModel model = models.get(token);
        if (model == null) {
            throw workerError(ErrorCode.LOAD_LIFECYCLE,
                    "unknown or closed model token '" + token + "'");
        }
        return model;
    }

    private static CortexJException unsupportedTask(String operation) {
        return new InferenceException(ErrorCode.INFERENCE_UNSUPPORTED_TASK,
                "the hosted model does not support '" + operation + "'",
                "Use a runtime/model that implements the requested capability.");
    }

    private static CortexJException workerError(String code, String message) {
        return new InferenceException(code, message);
    }

    private static ObjectNode ok(long id) {
        ObjectNode node = WorkerCodec.object();
        node.put("id", id);
        node.put("ok", true);
        return node;
    }

    private void respondError(long id, CortexJException failure) {
        ObjectNode node = WorkerCodec.object();
        node.put("id", id);
        node.put("ok", false);
        node.set("error", WorkerCodec.error(failure));
        emit(node);
    }

    private void emit(ObjectNode node) {
        synchronized (outputLock) {
            out.println(node.toString());
            if (out.checkError()) {
                // stdout is gone: the parent died - exit instead of spinning
                stopped = true;
                LOG.severe("worker stdout write failed (parent process gone) - exiting");
                closeSessionQuietly();
                System.exit(0);
            }
        }
    }

    private static void emitHello(PrintSink out, String runtimeId) {
        ObjectNode hello = WorkerCodec.object();
        hello.put("event", HELLO_EVENT);
        hello.put("protocol", PROTOCOL);
        hello.put("runtimeId", runtimeId);
        out.println(hello.toString());
    }

    private static void emitHelloError(PrintSink out, String runtimeId, CortexJException failure) {
        ObjectNode hello = WorkerCodec.object();
        hello.put("event", HELLO_EVENT);
        hello.put("protocol", PROTOCOL);
        hello.put("runtimeId", runtimeId);
        hello.set("error", WorkerCodec.error(failure));
        out.println(hello.toString());
    }

    // ------------------------------------------------------------------
    // payload helpers
    // ------------------------------------------------------------------

    private static Path artifactPath(JsonNode root) {
        String value = root.path("artifactPath").asText("");
        return value.isEmpty() ? null : Paths.get(value);
    }

    private static DeviceType device(JsonNode root) {
        DeviceType device = WorkerCodec.deviceType(root.path("device").asText(null));
        return device == null ? DeviceType.CPU : device;
    }

    private static Map<String, Object> objectMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                map.put(entry.getKey(), WorkerCodec.mapper().convertValue(entry.getValue(), Object.class));
            }
        }
        return map;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                map.put(entry.getKey(), entry.getValue().asText(""));
            }
        }
        return map;
    }

    /**
     * v1 probes with the child's own local machine profile: the worker runs
     * on the same host, so only the offline flag and the configuration map
     * are forwarded over the protocol (documented limitation).
     */
    private static EnvironmentProfile localEnvironment() {
        return new EnvironmentProfile(
                System.getProperty("java.vm.vendor", "unknown"),
                System.getProperty("java.vm.name", "unknown"),
                featureVersion(),
                System.getProperty("java.version", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory(),
                Runtime.getRuntime().maxMemory(),
                Runtime.getRuntime().maxMemory(),
                true,
                Collections.<String, String>emptyMap());
    }

    private static int featureVersion() {
        String specification = System.getProperty("java.vm.specification.version", "1.8");
        try {
            return Integer.parseInt(specification.replaceFirst("^1\\.", ""));
        } catch (NumberFormatException unrecognized) {
            return 8;
        }
    }

    private static HardwareProfile localHardware() {
        return new HardwareProfile(
                Collections.singletonList(DeviceDescriptor.of(DeviceType.CPU,
                        "worker-cpu", Runtime.getRuntime().maxMemory())),
                Runtime.getRuntime().maxMemory(),
                false, false, false, false,
                Collections.singletonList("worker-local profile (v1)"));
    }

    private static final class ActiveStream {
        final String modelToken;
        volatile TokenStream stream;

        ActiveStream(String modelToken) {
            this.modelToken = modelToken;
        }
    }

    // ------------------------------------------------------------------
    // output sinks (seam for in-process protocol tests)
    // ------------------------------------------------------------------

    interface PrintSink {
        void println(String line);

        boolean checkError();
    }

    private static final class StdoutSink implements PrintSink {
        private final java.io.PrintStream out = stdoutUtf8();

        private static java.io.PrintStream stdoutUtf8() {
            try {
                return new java.io.PrintStream(
                        new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 8192),
                        false, StandardCharsets.UTF_8.name());
            } catch (java.io.UnsupportedEncodingException impossible) {
                // UTF-8 is a required charset for every JVM
                throw new IllegalStateException(impossible);
            }
        }

        @Override
        public void println(String line) {
            out.println(line);
            out.flush();
        }

        @Override
        public boolean checkError() {
            return out.checkError();
        }
    }
}
