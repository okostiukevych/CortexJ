package io.cortexj.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.error.CortexJException;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.inference.TokenUsage;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ChatMessage;
import io.cortexj.model.ChatRole;
import io.cortexj.model.Checksum;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.TaskCapability;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.runtime.ThreadSafety;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON field mapping for the isolated-worker protocol (v1). Both sides of the
 * pipe use this class so request/response payloads cannot drift apart.
 *
 * <p>Cut from v1 (no clean wire mapping, see docs/isolated-worker.md):
 * ModelDescriptor modalities and artifact lists, GenerationRequest
 * {@code runtimeOptions}, EnvironmentProfile/HardwareProfile forwarding and
 * capability interfaces other than text generation and embedding.</p>
 */
public final class WorkerCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkerCodec() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    // ------------------------------------------------------------------
    // errors
    // ------------------------------------------------------------------

    public static ObjectNode error(CortexJException failure) {
        ObjectNode node = object();
        node.put("code", failure.code());
        node.put("message", failure.getMessage() == null ? "" : failure.getMessage());
        List<String> remediation = failure.remediation();
        if (!remediation.isEmpty()) {
            node.put("remediation", remediation.get(0));
        }
        ObjectNode details = object();
        for (Map.Entry<String, String> entry : failure.details().entrySet()) {
            details.put(entry.getKey(), entry.getValue());
        }
        node.set("details", details);
        return node;
    }

    public static CortexJException toException(JsonNode error, String fallbackCode, String fallbackWhat) {
        if (error == null || error.isMissingNode() || error.isNull()) {
            return new io.cortexj.error.InferenceException(fallbackCode, fallbackWhat);
        }
        String code = error.path("code").asText(fallbackCode);
        String message = error.path("message").asText(fallbackWhat);
        String remediation = error.path("remediation").isTextual()
                ? error.path("remediation").asText() : null;
        io.cortexj.error.CortexJException failure;
        if (code.startsWith("CORTEXJ-RUNTIME")) {
            failure = new io.cortexj.error.RuntimeUnavailableException(code, message);
        } else if (code.startsWith("CORTEXJ-LOAD")) {
            failure = new io.cortexj.error.ModelLoadException(code, message);
        } else if (code.startsWith("CORTEXJ-NATIVE")) {
            failure = new io.cortexj.error.NativeRuntimeException(code, message);
        } else {
            failure = new io.cortexj.error.InferenceException(code, message);
        }
        if (remediation != null) {
            failure.remediate(remediation);
        }
        JsonNode details = error.path("details");
        if (details.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = details.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                failure.detail(entry.getKey(), entry.getValue().asText());
            }
        }
        return failure;
    }

    // ------------------------------------------------------------------
    // inference request/response
    // ------------------------------------------------------------------

    public static ObjectNode inferenceRequest(InferenceRequest request) {
        ObjectNode node = object();
        ArrayNode inputs = node.putArray("inputs");
        for (String input : request.inputs()) {
            inputs.add(input);
        }
        ObjectNode parameters = object();
        for (Map.Entry<String, Object> entry : request.parameters().entrySet()) {
            parameters.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
        }
        node.set("parameters", parameters);
        ObjectNode metadata = object();
        for (Map.Entry<String, String> entry : request.metadata().entrySet()) {
            metadata.put(entry.getKey(), entry.getValue());
        }
        node.set("metadata", metadata);
        return node;
    }

    public static InferenceRequest parseInferenceRequest(JsonNode node) {
        InferenceRequest.Builder builder = InferenceRequest.builder();
        for (JsonNode input : node.path("inputs")) {
            builder.input(input.asText(""));
        }
        JsonNode parameters = node.path("parameters");
        if (parameters.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = parameters.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                builder.parameter(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class));
            }
        }
        JsonNode metadata = node.path("metadata");
        if (metadata.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = metadata.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                builder.meta(entry.getKey(), entry.getValue().asText(""));
            }
        }
        return builder.build();
    }

    public static ObjectNode inferenceResponse(InferenceResponse response) {
        ObjectNode node = object();
        ArrayNode outputs = node.putArray("outputs");
        for (String output : response.outputs()) {
            outputs.add(output);
        }
        ObjectNode metadata = object();
        for (Map.Entry<String, Object> entry : response.metadata().entrySet()) {
            metadata.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
        }
        node.set("metadata", metadata);
        return node;
    }

    public static InferenceResponse parseInferenceResponse(JsonNode node) {
        List<String> outputs = new ArrayList<String>();
        for (JsonNode output : node.path("outputs")) {
            outputs.add(output.asText(""));
        }
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        JsonNode metadataNode = node.path("metadata");
        if (metadataNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = metadataNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                metadata.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class));
            }
        }
        return new InferenceResponse(outputs, metadata);
    }

    // ------------------------------------------------------------------
    // generation request/response
    // ------------------------------------------------------------------

    public static ObjectNode generationRequest(GenerationRequest request) {
        ObjectNode node = object();
        node.put("prompt", request.prompt());
        ArrayNode messages = node.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode mapped = object();
            mapped.put("role", message.role().name());
            mapped.put("content", message.content());
            messages.add(mapped);
        }
        if (request.temperature() != null) {
            node.put("temperature", request.temperature().doubleValue());
        }
        if (request.maxTokens() != null) {
            node.put("maxTokens", request.maxTokens().intValue());
        }
        if (request.topP() != null) {
            node.put("topP", request.topP().doubleValue());
        }
        if (request.topK() != null) {
            node.put("topK", request.topK().intValue());
        }
        if (request.seed() != null) {
            node.put("seed", request.seed().longValue());
        }
        if (!request.stopSequences().isEmpty()) {
            ArrayNode stops = node.putArray("stopSequences");
            for (String stop : request.stopSequences()) {
                stops.add(stop);
            }
        }
        return node;
    }

    public static GenerationRequest parseGenerationRequest(JsonNode node) {
        GenerationRequest.Builder builder = GenerationRequest.builder();
        String prompt = node.path("prompt").asText("");
        for (JsonNode message : node.path("messages")) {
            builder.message(ChatMessage.of(chatRole(message.path("role").asText("USER")),
                    message.path("content").asText("")));
        }
        if (prompt.isEmpty() && !node.path("inputs").isEmpty()) {
            prompt = node.path("inputs").path(0).asText("");
        }
        builder.prompt(prompt);
        if (node.path("temperature").isNumber()) {
            builder.temperature(node.path("temperature").asDouble());
        }
        if (node.path("maxTokens").isNumber()) {
            builder.maxTokens(node.path("maxTokens").asInt());
        }
        if (node.path("topP").isNumber()) {
            builder.topP(node.path("topP").asDouble());
        }
        if (node.path("topK").isNumber()) {
            builder.topK(node.path("topK").asInt());
        }
        if (node.path("seed").isNumber()) {
            builder.seed(node.path("seed").asLong());
        }
        for (JsonNode stop : node.path("stopSequences")) {
            if (stop.isTextual() && !stop.asText().isEmpty()) {
                builder.stopSequence(stop.asText());
            }
        }
        return builder.build();
    }

    private static ChatRole chatRole(String name) {
        try {
            return ChatRole.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return ChatRole.USER;
        }
    }

    public static ObjectNode generationResponse(GenerationResponse response) {
        ObjectNode node = object();
        node.put("text", response.text());
        node.put("finishReason", response.finishReason().name());
        node.set("usage", usage(response.usage()));
        ObjectNode metadata = object();
        for (Map.Entry<String, String> entry : response.metadata().entrySet()) {
            metadata.put(entry.getKey(), entry.getValue());
        }
        node.set("metadata", metadata);
        return node;
    }

    public static GenerationResponse parseGenerationResponse(JsonNode node) {
        String text = node.path("text").asText("");
        FinishReason finishReason = finishReason(node.path("finishReason").asText("OTHER"));
        TokenUsage usage = parseUsage(node.path("usage"));
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        JsonNode metadataNode = node.path("metadata");
        if (metadataNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = metadataNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                metadata.put(entry.getKey(), entry.getValue().asText(""));
            }
        }
        return new GenerationResponse(text, finishReason, usage, metadata);
    }

    public static FinishReason finishReason(String name) {
        try {
            return FinishReason.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return FinishReason.OTHER;
        }
    }

    public static ObjectNode usage(TokenUsage usage) {
        ObjectNode node = object();
        if (usage == null) {
            usage = TokenUsage.unknown();
        }
        node.put("inputTokens", usage.inputTokens());
        node.put("outputTokens", usage.outputTokens());
        return node;
    }

    public static TokenUsage parseUsage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return TokenUsage.unknown();
        }
        return new TokenUsage(node.path("inputTokens").asLong(-1L), node.path("outputTokens").asLong(-1L));
    }

    // ------------------------------------------------------------------
    // embedding request/response
    // ------------------------------------------------------------------

    public static ObjectNode embeddingRequest(EmbeddingRequest request) {
        ObjectNode node = object();
        ArrayNode texts = node.putArray("texts");
        for (String text : request.texts()) {
            texts.add(text);
        }
        return node;
    }

    public static EmbeddingRequest parseEmbeddingRequest(JsonNode node) {
        List<String> texts = new ArrayList<String>();
        for (JsonNode text : node.path("texts")) {
            texts.add(text.asText(""));
        }
        if (texts.isEmpty() && !node.path("inputs").isEmpty()) {
            for (JsonNode text : node.path("inputs")) {
                texts.add(text.asText(""));
            }
        }
        return EmbeddingRequest.texts(texts);
    }

    public static ObjectNode embeddingResponse(EmbeddingResponse response) {
        ObjectNode node = object();
        ArrayNode vectors = node.putArray("vectors");
        for (float[] vector : response.vectors()) {
            ArrayNode values = vectors.addArray();
            for (float value : vector) {
                values.add(value);
            }
        }
        return node;
    }

    public static EmbeddingResponse parseEmbeddingResponse(JsonNode node) {
        List<float[]> vectors = new ArrayList<float[]>();
        for (JsonNode vector : node.path("vectors")) {
            float[] values = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                values[i] = (float) vector.get(i).asDouble();
            }
            vectors.add(values);
        }
        return new EmbeddingResponse(vectors);
    }

    // ------------------------------------------------------------------
    // model descriptor / artifact
    // ------------------------------------------------------------------

    public static ObjectNode modelDescriptor(ModelDescriptor descriptor) {
        ObjectNode node = object();
        if (descriptor == null) {
            return node;
        }
        node.put("canonicalId", descriptor.canonicalId());
        putIfTextual(node, "revision", descriptor.revision());
        putIfTextual(node, "architecture", descriptor.architecture());
        putIfTextual(node, "license", descriptor.license());
        node.put("parameterCount", descriptor.parameterCount());
        node.put("contextLength", descriptor.contextLength());
        ArrayNode tasks = node.putArray("tasks");
        for (TaskCapability task : descriptor.tasks()) {
            tasks.add(task.name());
        }
        putIfTextual(node, "trustNote", descriptor.trustNote());
        return node;
    }

    public static ModelDescriptor parseModelDescriptor(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String canonicalId = node.path("canonicalId").asText("");
        if (canonicalId.trim().isEmpty()) {
            canonicalId = "worker/unknown-model";
        }
        ModelDescriptor.Builder builder = ModelDescriptor.builder(canonicalId);
        if (node.path("revision").isTextual()) {
            builder.revision(node.path("revision").asText());
        }
        if (node.path("architecture").isTextual()) {
            builder.architecture(node.path("architecture").asText());
        }
        if (node.path("license").isTextual()) {
            builder.license(node.path("license").asText());
        }
        builder.parameterCount(node.path("parameterCount").asLong(0L));
        builder.contextLength(node.path("contextLength").asInt(0));
        for (JsonNode task : node.path("tasks")) {
            TaskCapability capability = taskCapability(task.asText(null));
            if (capability != null) {
                builder.task(capability);
            }
        }
        if (node.path("trustNote").isTextual()) {
            builder.trustNote(node.path("trustNote").asText());
        }
        return builder.build();
    }

    public static ObjectNode artifact(ModelArtifact artifact) {
        ObjectNode node = object();
        if (artifact == null) {
            return node;
        }
        node.put("id", artifact.id());
        node.put("format", artifact.format().name());
        node.put("sizeBytes", artifact.sizeBytes());
        if (artifact.checksum() != null) {
            ObjectNode checksum = object();
            checksum.put("algorithm", artifact.checksum().algorithm());
            checksum.put("value", artifact.checksum().value());
            node.set("checksum", checksum);
        }
        ObjectNode metadata = object();
        for (Map.Entry<String, String> entry : artifact.metadata().entrySet()) {
            metadata.put(entry.getKey(), entry.getValue());
        }
        node.set("metadata", metadata);
        return node;
    }

    public static ModelArtifact parseArtifact(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.has("id")) {
            return null;
        }
        String id = node.path("id").asText("");
        if (id.trim().isEmpty()) {
            return null;
        }
        ArtifactFormat format = artifactFormat(node.path("format").asText(null));
        long sizeBytes = node.path("sizeBytes").asLong(0L);
        Checksum checksum = null;
        JsonNode checksumNode = node.path("checksum");
        if (checksumNode.isObject()
                && checksumNode.path("algorithm").isTextual()
                && checksumNode.path("value").isTextual()) {
            checksum = Checksum.of(checksumNode.path("algorithm").asText(), checksumNode.path("value").asText());
        }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        JsonNode metadataNode = node.path("metadata");
        if (metadataNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = metadataNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                metadata.put(entry.getKey(), entry.getValue().asText(""));
            }
        }
        return new JsonArtifact(id, format, sizeBytes, checksum, metadata);
    }

    public static final class JsonArtifact extends ModelArtifact.Abstract {
        public JsonArtifact(String id, ArtifactFormat format, long sizeBytes, Checksum checksum, Map<String, String> metadata) {
            super(id, format, sizeBytes, checksum, metadata);
        }
    }

    // ------------------------------------------------------------------
    // runtime descriptor / model capabilities
    // ------------------------------------------------------------------

    public static ObjectNode runtimeDescriptor(RuntimeDescriptor descriptor) {
        ObjectNode node = object();
        if (descriptor == null) {
            return node;
        }
        node.put("runtimeId", descriptor.id().value());
        node.put("version", descriptor.version());
        ArrayNode formats = node.putArray("formats");
        for (ArtifactFormat format : descriptor.formats()) {
            formats.add(format.name());
        }
        ArrayNode devices = node.putArray("devices");
        for (DeviceType device : descriptor.devices()) {
            devices.add(device.name());
        }
        ArrayNode tasks = node.putArray("tasks");
        for (TaskCapability task : descriptor.tasks()) {
            tasks.add(task.name());
        }
        node.put("minJavaFeatureVersion", descriptor.minJavaFeatureVersion());
        node.put("streamingSupported", descriptor.isStreamingSupported());
        node.put("structuredOutputNative", descriptor.isStructuredOutputNative());
        node.put("nativeRuntime", descriptor.isNativeRuntime());
        node.put("threadSafety", descriptor.threadSafety().name());
        node.put("maturity", descriptor.maturity().name());
        ObjectNode metadata = object();
        for (Map.Entry<String, String> entry : descriptor.metadata().entrySet()) {
            metadata.put(entry.getKey(), entry.getValue());
        }
        node.set("metadata", metadata);
        return node;
    }

    public static RuntimeDescriptor parseRuntimeDescriptor(JsonNode node) {
        RuntimeId id = RuntimeId.of(node.path("runtimeId").asText("unknown"));
        RuntimeDescriptor.Builder builder = RuntimeDescriptor.builder(id);
        builder.version(node.path("version").asText("0.0.0"));
        for (JsonNode format : node.path("formats")) {
            ArtifactFormat parsed = artifactFormat(format.asText(null));
            if (parsed != null) {
                builder.format(parsed);
            }
        }
        for (JsonNode device : node.path("devices")) {
            DeviceType parsed = deviceType(device.asText(null));
            if (parsed != null) {
                builder.device(parsed);
            }
        }
        for (JsonNode task : node.path("tasks")) {
            TaskCapability parsed = taskCapability(task.asText(null));
            if (parsed != null) {
                builder.task(parsed);
            }
        }
        builder.minJavaFeatureVersion(node.path("minJavaFeatureVersion").asInt(8));
        builder.streamingSupported(node.path("streamingSupported").asBoolean(false));
        builder.structuredOutputNative(node.path("structuredOutputNative").asBoolean(false));
        builder.nativeRuntime(node.path("nativeRuntime").asBoolean(false));
        ThreadSafety threadSafety = threadSafety(node.path("threadSafety").asText(null));
        builder.threadSafety(threadSafety == null ? ThreadSafety.SESSION_SCOPED : threadSafety);
        builder.maturity(maturity(node.path("maturity").asText(null)));
        JsonNode metadata = node.path("metadata");
        if (metadata.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = metadata.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                builder.metadata(entry.getKey(), entry.getValue().asText(""));
            }
        }
        return builder.build();
    }

    public static ObjectNode modelCapabilities(ModelCapabilities capabilities) {
        ObjectNode node = object();
        if (capabilities == null) {
            capabilities = ModelCapabilities.ofTasks(TaskCapability.EMBEDDING);
        }
        ArrayNode tasks = node.putArray("tasks");
        for (TaskCapability task : capabilities.tasks()) {
            tasks.add(task.name());
        }
        node.put("streamingSupported", capabilities.isStreamingSupported());
        node.put("structuredOutputSupported", capabilities.isStructuredOutputSupported());
        node.put("maxConcurrentRequests", capabilities.maxConcurrentRequests());
        return node;
    }

    public static ModelCapabilities parseModelCapabilities(JsonNode node) {
        java.util.Set<TaskCapability> tasks = new java.util.LinkedHashSet<TaskCapability>();
        for (JsonNode task : node.path("tasks")) {
            TaskCapability parsed = taskCapability(task.asText(null));
            if (parsed != null) {
                tasks.add(parsed);
            }
        }
        if (tasks.isEmpty()) {
            tasks.add(TaskCapability.EMBEDDING);
        }
        return ModelCapabilities.of(tasks,
                node.path("streamingSupported").asBoolean(false),
                node.path("structuredOutputSupported").asBoolean(false),
                node.path("maxConcurrentRequests").asInt(1));
    }

    // ------------------------------------------------------------------
    // enums
    // ------------------------------------------------------------------

    public static ArtifactFormat artifactFormat(String name) {
        if (name == null) {
            return null;
        }
        try {
            return ArtifactFormat.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    public static DeviceType deviceType(String name) {
        if (name == null) {
            return null;
        }
        try {
            return DeviceType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    public static TaskCapability taskCapability(String name) {
        if (name == null) {
            return null;
        }
        try {
            return TaskCapability.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    public static ThreadSafety threadSafety(String name) {
        if (name == null) {
            return null;
        }
        try {
            return ThreadSafety.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    public static RuntimeDescriptor.Maturity maturity(String name) {
        if (name == null) {
            return RuntimeDescriptor.Maturity.EXPERIMENTAL;
        }
        try {
            return RuntimeDescriptor.Maturity.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return RuntimeDescriptor.Maturity.EXPERIMENTAL;
        }
    }

    public static io.cortexj.runtime.RuntimeHealth runtimeHealth(String name) {
        if (name == null) {
            return io.cortexj.runtime.RuntimeHealth.UNAVAILABLE;
        }
        try {
            return io.cortexj.runtime.RuntimeHealth.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return io.cortexj.runtime.RuntimeHealth.UNAVAILABLE;
        }
    }

    public static boolean isTextGeneration(Object model) {
        return model instanceof TextGenerationCapability;
    }

    private static void putIfTextual(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
