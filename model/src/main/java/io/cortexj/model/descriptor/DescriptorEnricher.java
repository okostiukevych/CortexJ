package io.cortexj.model.descriptor;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.Quantization;
import io.cortexj.spi.artifact.ArtifactInspection;

import java.util.Locale;
import java.util.Map;

public final class DescriptorEnricher {

    private DescriptorEnricher() {
    }

    public static ModelDescriptor enrich(ModelDescriptor descriptor, ArtifactInspection inspection, String artifactFileName) {
        if (inspection == null) {
            return descriptor;
        }
        ModelDescriptor.Builder builder = descriptor.toBuilder();
        boolean changed = false;
        if (descriptor.architecture() == null && inspection.architecture() != null) {
            builder.architecture(inspection.architecture());
            changed = true;
        }
        if (inspection.contextLength() > 0 && inspection.contextLength() != descriptor.contextLength()) {
            builder.contextLength(inspection.contextLength());
            changed = true;
        }
        if (inspection.parameterCount() > 0 && inspection.parameterCount() != descriptor.parameterCount()) {
            builder.parameterCount(inspection.parameterCount());
            changed = true;
        }
        String quantization = inspection.quantization() != null
                ? inspection.quantization()
                : quantizationFromFileName(artifactFileName);
        // audit 2026-10-08: never overwrite a trust marker (e.g. "community
        // upload") with a quantization label - an absent note used to mean
        // "trusted" for TRUSTED_ONLY, and clobbering it here would silently
        // reclassify the model once enrich() gains callers
        if (quantization != null && (descriptor.trustNote() == null
                || descriptor.trustNote().trim().isEmpty())) {
            builder.trustNote(quantization);
            changed = true;
        }
        return changed ? builder.build() : descriptor;
    }

    public static String quantizationFromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String base = dot > 0 ? lower.substring(0, dot) : lower;
        String[] parts = base.split("-");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (part.startsWith("q") && part.length() >= 2 && part.length() <= 6) {
                return Quantization.parse(part).label();
            }
        }
        for (String part : parts) {
            if (part.equals("fp16") || part.equals("bf16") || part.equals("f16") || part.equals("fp32")) {
                return part;
            }
        }
        return null;
    }

    public static ArtifactFormat dominantExecutableFormat(ModelDescriptor descriptor) {
        for (io.cortexj.model.ModelArtifact artifact : descriptor.artifacts()) {
            if (artifact.format().isExecutableWeights()) {
                return artifact.format();
            }
        }
        return ArtifactFormat.UNKNOWN;
    }

    public static long estimatedParameterBytes(Map<String, ArtifactInspection> inspections) {
        long total = 0L;
        for (ArtifactInspection inspection : inspections.values()) {
            if (inspection.parameterCount() > 0) {
                total = Math.max(total, inspection.parameterCount());
            }
        }
        return total;
    }
}
