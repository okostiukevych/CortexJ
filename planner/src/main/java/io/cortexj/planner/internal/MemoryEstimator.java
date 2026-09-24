package io.cortexj.planner.internal;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.planner.MemoryConfidence;
import io.cortexj.planner.MemoryEstimate;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MemoryEstimator {

    private static final long MIB = 1024L * 1024L;

    public MemoryEstimate estimate(ModelArtifact artifact, int contextLengthHint) {
        if (artifact == null) {
            return MemoryEstimate.unknown();
        }
        ArtifactFormat format = artifact.format();
        Map<String, Long> components = new LinkedHashMap<String, Long>();

        switch (format) {
            case REMOTE:
                components.put("clientBuffers", 16L * MIB);
                return MemoryEstimate.of(16L * MIB, MemoryConfidence.HIGH, components, 0L);
            case GGUF: {
                long weights = artifact.sizeBytes();
                components.put("weights", weights);
                long kvCache = kvCacheEstimate(weights, contextLengthHint);
                components.put("kvCache", kvCache);
                long runtimeOverhead = 256L * MIB;
                components.put("runtimeOverhead", runtimeOverhead);
                long margin = (long) ((weights + kvCache) * 0.15d);
                components.put("safetyMargin", margin);
                long total = weights + kvCache + runtimeOverhead + margin;
                return MemoryEstimate.of(total, MemoryConfidence.MEDIUM, components, margin);
            }
            case ONNX: {
                long modelSize = artifact.sizeBytes();
                components.put("modelSize", modelSize);
                long activations = (long) (modelSize * 0.25d);
                components.put("activationEstimate", activations);
                long runtimeOverhead = 128L * MIB;
                components.put("runtimeOverhead", runtimeOverhead);
                long margin = (long) ((modelSize + activations) * 0.15d);
                components.put("safetyMargin", margin);
                long total = modelSize + activations + runtimeOverhead + margin;
                return MemoryEstimate.of(total, MemoryConfidence.MEDIUM, components, margin);
            }
            case SAFETENSORS: {
                long weights = artifact.sizeBytes();
                components.put("weights", weights);
                long kvCache = kvCacheEstimate(weights, contextLengthHint);
                components.put("kvCache", kvCache);
                long runtimeOverhead = 384L * MIB;
                components.put("runtimeOverhead", runtimeOverhead);
                long margin = (long) ((weights + kvCache) * 0.35d);
                components.put("safetyMargin", margin);
                long total = weights + kvCache + runtimeOverhead + margin;
                return MemoryEstimate.of(total, MemoryConfidence.LOW, components, margin);
            }
            default:
                if (artifact.sizeBytes() > 0) {
                    components.put("artifact", artifact.sizeBytes());
                    return MemoryEstimate.of((long) (artifact.sizeBytes() * 1.5d) + 128L * MIB,
                            MemoryConfidence.LOW, components, artifact.sizeBytes() / 2);
                }
                return MemoryEstimate.unknown();
        }
    }

    private static long kvCacheEstimate(long weightsBytes, int contextLengthHint) {
        int effectiveContext = contextLengthHint > 0 ? contextLengthHint : 4096;
        double bytesPerTokenKv = Math.max(64.0d, weightsBytes / 400_000.0d);
        return (long) (effectiveContext * 4L * bytesPerTokenKv);
    }
}
