package io.cortexj.train.djl;

import io.cortexj.train.djl.models.GenericDecoderBlock;
import io.cortexj.train.djl.models.LoraLinear;
import io.cortexj.train.peft.LoRAAdapter;
import io.cortexj.train.peft.LoRAConfig;
import io.cortexj.train.peft.LoRASafetensors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports the trained LoRA wrappers as standard ADR-022 containers:
 * one file per (layer, module), module name = the llama-style short
 * name, metadata carries rank/alpha/targets. Returns file->checksum
 * for provenance.
 */
final class AdapterExport {

    static Map<String, String> writeAll(GenericDecoderBlock block, int rank, int alpha,
                                        List<String> targets, Path outputDirectory)
            throws IOException {
        Files.createDirectories(outputDirectory);
        Map<String, String> checksums = new LinkedHashMap<String, String>();
        for (Map.Entry<String, LoraLinear> entry : block.loraChildren().entrySet()) {
            String childName = entry.getKey();
            String moduleName = childName.substring(childName.lastIndexOf('.') + 1);
            LoRAConfig config = new LoRAConfig(rank, alpha, 0.0,
                    Collections.singletonList(moduleName));
            LoRAAdapter adapter = new LoRAAdapter(moduleName,
                    entry.getValue().inUnits(), entry.getValue().outUnits(),
                    config, entry.getValue().matrixA(), entry.getValue().matrixB());
            String fileName = "adapter_" + childName.replace('.', '_') + ".safetensors";
            Path file = outputDirectory.resolve(fileName);
            LoRASafetensors.write(adapter, file, metadata(rank, alpha, targets));
            checksums.put(fileName, sha256(file));
        }
        return checksums;
    }

    private static Map<String, String> metadata(int rank, int alpha, List<String> targets) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("lora.rank", String.valueOf(rank));
        metadata.put("lora.alpha", String.valueOf(alpha));
        metadata.put("lora.targets", targets.toString());
        return metadata;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private AdapterExport() {
    }
}
