package io.cortexj.train.djl.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.convert.safetensors.SafetensorsTensorReader;
import io.cortexj.error.CortexJException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Loads HF safetensors base weights from a model directory: a single
 * model.safetensors or a model.safetensors.index.json shard set. The
 * trained-base gate (no quantized GGUF) lives in the backend; this
 * loader only reads safetensors.
 */
public final class HfWeightLoader {

    public static LoadedWeights load(Path modelDirectory) {
        Path single = modelDirectory.resolve("model.safetensors");
        if (Files.isRegularFile(single)) {
            try {
                return new LoadedWeights(SafetensorsTensorReader.readAllFloats(single));
            } catch (IOException e) {
                throw new CortexJException("CORTEXJ-TRAIN-0103",
                        "model.safetensors is not readable: " + e.getMessage(),
                        "Re-download the base model or point modelPath at an intact directory.");
            }
        }
        Path index = modelDirectory.resolve("model.safetensors.index.json");
        if (Files.isRegularFile(index)) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode weightMap;
                try (java.io.InputStream in = Files.newInputStream(index)) {
                    weightMap = mapper.readTree(in).path("weight_map");
                }
                Map<String, float[]> all = new LinkedHashMap<String, float[]>();
                TreeSet<String> shardFiles = new TreeSet<String>();
                Iterator<String> fields = weightMap.fieldNames();
                while (fields.hasNext()) {
                    shardFiles.add(weightMap.path(fields.next()).asText());
                }
                Path normalizedRoot = modelDirectory.toAbsolutePath().normalize();
                for (String shard : shardFiles) {
                    if (shard == null || shard.isEmpty() || shard.contains("..")) {
                        throw new CortexJException("CORTEXJ-TRAIN-0103",
                                "invalid shard path in model.safetensors.index.json: " + shard,
                                "Ensure all shard paths in model.safetensors.index.json stay within modelDirectory.");
                    }
                    Path resolved = normalizedRoot.resolve(shard).normalize();
                    if (!resolved.startsWith(normalizedRoot)) {
                        throw new CortexJException("CORTEXJ-TRAIN-0103",
                                "shard path escapes model directory: " + shard,
                                "Ensure all shard paths in model.safetensors.index.json stay within modelDirectory.");
                    }
                    all.putAll(SafetensorsTensorReader.readAllFloats(resolved));
                }
                return new LoadedWeights(all);
            } catch (IOException e) {
                throw new CortexJException("CORTEXJ-TRAIN-0103",
                        "sharded safetensors load failed: " + e.getMessage(),
                        "Re-download the base model shards.");
            }
        }
        CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0103",
                "no safetensors weights (model.safetensors or model.safetensors.index.json) in "
                        + modelDirectory,
                "Ship model.safetensors or a model.safetensors.index.json shard set.");
        failure.detail("directory", modelDirectory.toString());
        throw failure;
    }

    private HfWeightLoader() {
    }
}
