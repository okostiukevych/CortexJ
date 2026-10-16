package io.cortexj.convert.lora;

import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.train.peft.LoRAAdapter;
import io.cortexj.train.peft.LoRASafetensors;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime-side LoRA composition, first half (ADR-029 follow-up):
 * exports standard safetensors adapters (ADR-022) as ONE llama.cpp
 * adapter GGUF. The container contract is pinned from llama.cpp
 * src/llama-adapter.cpp: general.type=adapter, general.architecture
 * matching the base model, adapter.type=lora, adapter.lora.alpha
 * (f32), tensors named {@code <base>.lora_a} / {@code <base>.lora_b}
 * with F32 data. GGUF dims are innermost-first, so the byte payload
 * is the HF row-major A (rank x in) and B (out x rank) verbatim:
 * lora_a declares [in, rank], lora_b declares [rank, out].
 * Embedding targets and rank/alpha disagreement fail typed.
 */
public final class LoraAdapterGguf {

    /** GGUF metadata value types (subset used here). */
    private static final int KV_FLOAT32 = 6;
    private static final int KV_STRING = 8;
    private static final int TENSOR_F32 = 0;

    private static final Pattern TRAINING_LAYOUT = Pattern.compile(
            "^adapter_layers_(\\d+)_self_attn_(q_proj|k_proj|v_proj|o_proj)\\.safetensors$"
                    + "|^adapter_layers_(\\d+)_mlp_(gate_proj|up_proj|down_proj)\\.safetensors$");

    private static final Map<String, String> GGUF_TENSORS = new LinkedHashMap<String, String>();

    static {
        GGUF_TENSORS.put("q_proj", "attn_q");
        GGUF_TENSORS.put("k_proj", "attn_k");
        GGUF_TENSORS.put("v_proj", "attn_v");
        GGUF_TENSORS.put("o_proj", "attn_o");
        GGUF_TENSORS.put("gate_proj", "ffn_gate");
        GGUF_TENSORS.put("up_proj", "ffn_up");
        GGUF_TENSORS.put("down_proj", "ffn_down");
    }

    /** One explicit mapping: which adapter file targets which base GGUF tensor. */
    public static final class Entry {

        private final Path adapterFile;
        private final String baseTensor;

        public Entry(Path adapterFile, String baseTensor) {
            if (adapterFile == null || baseTensor == null || baseTensor.isEmpty()) {
                throw new IllegalArgumentException("adapter file and base tensor are required");
            }
            this.adapterFile = adapterFile;
            this.baseTensor = baseTensor;
        }

        public Path adapterFile() {
            return adapterFile;
        }

        public String baseTensor() {
            return baseTensor;
        }
    }

    public static final class Request {

        private final Path baseModel;
        private final List<Entry> entries;
        private final Path output;

        public Request(Path baseModel, List<Entry> entries, Path output) {
            if (baseModel == null || entries == null || entries.isEmpty() || output == null) {
                throw new IllegalArgumentException(
                        "base model, entries and output are required");
            }
            this.baseModel = baseModel;
            this.entries = List.copyOf(entries);
            this.output = output;
        }

        public Path baseModel() {
            return baseModel;
        }

        public List<Entry> entries() {
            return entries;
        }

        public Path output() {
            return output;
        }
    }

    /**
     * Convenience over the training export layout: parses
     * {@code adapter_layers_N_self_attn_q_proj.safetensors} style file
     * names into base tensor names ({@code blk.N.attn_q.weight}). Files
     * outside the convention fail typed naming the file - never guessed
     * (ADR-029).
     */
    public static List<Entry> trainingLayoutEntries(Path adaptersDirectory)
            throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(adaptersDirectory, "*.safetensors")) {
            for (Path file : stream) {
                files.add(file);
            }
        }
        files.sort(java.util.Comparator.naturalOrder());
        if (files.isEmpty()) {
            throw new IOException("no adapter files under " + adaptersDirectory);
        }
        List<Entry> entries = new ArrayList<>();
        for (Path file : files) {
            Matcher matcher = TRAINING_LAYOUT.matcher(file.getFileName().toString());
            if (!matcher.matches()) {
                throw new IOException("adapter file does not follow the training export"
                        + " naming convention (adapter_layers_N_self_attn_q_proj.safetensors): "
                        + file.getFileName());
            }
            String module = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
            String layer = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            entries.add(new Entry(file,
                    "blk." + layer + "." + GGUF_TENSORS.get(module) + ".weight"));
        }
        return entries;
    }

    public static Path export(Request request) throws IOException {
        GgufDocument base = GgufDocument.read(request.baseModel());
        String architecture = metadataString(base, "general.architecture");
        if (architecture == null) {
            throw new IOException("base GGUF carries no general.architecture: "
                    + request.baseModel());
        }
        Map<String, GgufDocument.TensorInfo> byName =
                new LinkedHashMap<String, GgufDocument.TensorInfo>();
        for (GgufDocument.TensorInfo tensor : base.tensors) {
            byName.put(tensor.name, tensor);
        }

        GgufDocument doc = new GgufDocument();
        doc.version = base.version;
        doc.upsert("general.type", KV_STRING, "adapter");
        doc.upsert("general.architecture", KV_STRING, architecture);
        doc.upsert("adapter.type", KV_STRING, "lora");

        Map<String, byte[]> payload = new LinkedHashMap<String, byte[]>();
        int rank = -1;
        int alpha = -1;
        for (Entry entry : request.entries()) {
            if (entry.baseTensor().endsWith("token_embd.weight")) {
                throw new IOException("LoRA targeting the embedding (token_embd.weight)"
                        + " is not supported in 0.x: " + entry.baseTensor());
            }
            GgufDocument.TensorInfo target = byName.get(entry.baseTensor());
            if (target == null) {
                throw new IOException("adapter targets a tensor that does not exist in the"
                        + " base model: " + entry.baseTensor());
            }
            if (target.dimensions.length != 2) {
                throw new IOException("base tensor is not 2-D, cannot attach a LoRA: "
                        + entry.baseTensor());
            }
            LoRAAdapter adapter;
            try {
                adapter = LoRASafetensors.read(entry.adapterFile());
            } catch (IOException e) {
                throw new IOException("adapter for " + entry.baseTensor()
                        + " is unreadable: " + e, e);
            }
            if (rank < 0) {
                rank = adapter.config().rank();
                alpha = adapter.config().alpha();
                doc.upsert("adapter.lora.alpha", KV_FLOAT32, (float) alpha);
            } else if (adapter.config().rank() != rank || adapter.config().alpha() != alpha) {
                throw new IOException("adapters disagree on rank/alpha: "
                        + adapter.config().rank() + "/" + adapter.config().alpha()
                        + " vs " + rank + "/" + alpha);
            }
            int in = adapter.inputSize();
            int out = adapter.outputSize();
            if (target.dimensions[0] != in || target.dimensions[1] != out) {
                throw new IOException("adapter shape does not match base tensor "
                        + entry.baseTensor() + ": adapter " + out + "x" + in
                        + " (out x in) vs base dims [" + target.dimensions[0] + ", "
                        + target.dimensions[1] + "] (innermost-first)");
            }
            // byte payload = HF row-major A then B, verbatim (dims reversed in the header)
            byte[] aBytes = f32(adapter.matrixA());
            byte[] bBytes = f32(adapter.matrixB());
            doc.tensors.add(new GgufDocument.TensorInfo(entry.baseTensor() + ".lora_a",
                    new long[]{in, rank}, TENSOR_F32, 0, aBytes.length));
            doc.tensors.add(new GgufDocument.TensorInfo(entry.baseTensor() + ".lora_b",
                    new long[]{rank, out}, TENSOR_F32, 0, bBytes.length));
            payload.put(entry.baseTensor() + ".lora_a", aBytes);
            payload.put(entry.baseTensor() + ".lora_b", bBytes);
        }

        Path output = request.output().toAbsolutePath();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream stream = Files.newOutputStream(output)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(stream);
            doc.writeHeader(writer);
            for (GgufDocument.TensorInfo tensor : doc.tensors) {
                writer.write(payload.get(tensor.name));
            }
        }
        return request.output();
    }

    private static String metadataString(GgufDocument doc, String key) {
        for (GgufDocument.KeyValue kv : doc.metadata) {
            if (kv.key.equals(key) && kv.value instanceof String) {
                return (String) kv.value;
            }
        }
        return null;
    }

    private static byte[] f32(float[][] matrix) {
        ByteBuffer buffer = ByteBuffer.allocate(matrix.length * (matrix.length > 0
                ? matrix[0].length : 0) * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] row : matrix) {
            for (float value : row) {
                buffer.putFloat(value);
            }
        }
        return buffer.array();
    }

    private LoraAdapterGguf() {
    }
}
