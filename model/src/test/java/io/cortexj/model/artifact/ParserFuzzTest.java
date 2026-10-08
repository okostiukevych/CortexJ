package io.cortexj.model.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit 2026-10-08: mutation fuzz over the binary inspectors - hostile input
 * must end in a typed failure (or a successful inspection), never in OOM,
 * StackOverflowError or a hang. Fixed seed for reproducibility.
 */
class ParserFuzzTest {

    @TempDir
    Path tempDir;

    private static final int ITERATIONS = 300;

    @Test
    @Timeout(120)
    void mutatedInputsNeverEscapeTypedFailures() throws Exception {
        Random random = new Random(0xC07EEL);
        AtomicInteger typed = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < ITERATIONS; i++) {
            byte[] gguf = mutatedGguf(random);
            try {
                new GgufArtifactInspector().inspect(writeTemp("fuzz-" + i + ".gguf", gguf));
                accepted.incrementAndGet();
            } catch (IllegalArgumentException | IOException expected) {
                typed.incrementAndGet();
            }
            byte[] st = mutatedSafetensors(random);
            try {
                new SafetensorsInspector().inspect(writeTemp("fuzz-" + i + ".safetensors", st));
                accepted.incrementAndGet();
            } catch (IllegalArgumentException | IOException expected) {
                typed.incrementAndGet();
            }
            byte[] onnx = mutatedOnnx(random);
            try {
                new OnnxLightInspector().inspect(writeTemp("fuzz-" + i + ".onnx", onnx));
                accepted.incrementAndGet();
            } catch (IllegalArgumentException | IOException expected) {
                typed.incrementAndGet();
            }
        }
        assertTrue(typed.get() > 0, "mutations must mostly fail typed");
        System.out.println("parser fuzz: " + ITERATIONS * 3 + " inputs, "
                + typed + " typed failures, " + accepted + " accepted");
    }

    @Test
    @Timeout(120)
    void validDocumentsSurviveTargetedMutations() throws Exception {
        // audit 2026-10-08: structure-aware round - a valid document with one
        // mutated field must still end typed (or valid), exercising deeper
        // parser states than pure noise
        byte[] validGguf = validGguf();
        byte[] validSt = validSafetensors();
        Random random = new Random(0xF005EL);
        int typed = 0;
        int accepted = 0;
        for (int i = 0; i < 200; i++) {
            byte[] mutated = validGguf.clone();
            // flip 1-4 bytes at random offsets (lengths, counts, types)
            int flips = 1 + random.nextInt(4);
            for (int f = 0; f < flips; f++) {
                mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
            }
            try {
                new GgufArtifactInspector().inspect(writeTemp("v2-" + i + ".gguf", mutated));
                accepted++;
            } catch (IllegalArgumentException | IOException expected) {
                typed++;
            }
            byte[] mutatedSt = validSt.clone();
            int flipsSt = 1 + random.nextInt(4);
            for (int f = 0; f < flipsSt; f++) {
                mutatedSt[random.nextInt(mutatedSt.length)] = (byte) random.nextInt(256);
            }
            try {
                new SafetensorsInspector().inspect(writeTemp("v2-" + i + ".safetensors", mutatedSt));
                accepted++;
            } catch (IllegalArgumentException | IOException expected) {
                typed++;
            }
        }
        // declared-huge header with a short body must fail fast on
        // truncation, not hang or allocate without bound
        ByteBuffer huge = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        huge.putLong(64L * 1024 * 1024);
        try {
            new SafetensorsInspector().inspect(writeTemp("huge.st", huge.array()));
            accepted++;
        } catch (IllegalArgumentException | IOException expected) {
            typed++;
        }
        assertTrue(typed > 0, "mutations must mostly fail typed");
        System.out.println("parser fuzz v2: 401 structured inputs, "
                + typed + " typed failures, " + accepted + " accepted");
    }

    private Path writeTemp(String name, byte[] bytes) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static byte[] validGguf() {
        // magic + v3 + 1 tensor + 1 KV + minimal tensor directory
        ByteBuffer name = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        name.putInt(0x46554747);
        name.putInt(3);
        name.putLong(1L);
        name.putLong(1L);
        byte[] key = "general.architecture".getBytes(StandardCharsets.UTF_8);
        name.putLong(key.length);
        name.put(key);
        name.putInt(8); // STRING type
        byte[] value = "llama".getBytes(StandardCharsets.UTF_8);
        name.putLong(value.length);
        name.put(value);
        byte[] tensor = "w.weight".getBytes(StandardCharsets.UTF_8);
        name.putLong(tensor.length);
        name.put(tensor);
        name.putInt(1); // 1 dimension
        name.putLong(4L); // 4 elements
        name.putInt(0); // F32
        name.putLong(0L); // offset
        byte[] header = new byte[name.position()];
        System.arraycopy(name.array(), 0, header, 0, header.length);
        long padding = (64L - (header.length % 64L)) % 64L;
        byte[] out = new byte[(int) (header.length + padding + 16)];
        System.arraycopy(header, 0, out, 0, header.length);
        ByteBuffer data = ByteBuffer.wrap(out, (int) (header.length + padding), 16)
                .order(ByteOrder.LITTLE_ENDIAN);
        data.putFloat(1.0f).putFloat(2.0f).putFloat(3.0f).putFloat(4.0f);
        return out;
    }

    private static byte[] validSafetensors() {
        String header = "{\"__metadata__\":{},\"w\":{\"dtype\":\"F32\",\"shape\":[4],\"data_offsets\":[0,16]}}";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(8 + headerBytes.length + 16).order(ByteOrder.LITTLE_ENDIAN);
        out.putLong(headerBytes.length);
        out.put(headerBytes);
        out.put(new byte[16]);
        return out.array();
    }

    private static byte[] mutatedGguf(Random random) {
        // mostly-valid prefix (magic, version, counts) + random tail/truncation
        ByteBuffer head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(0x46554747);
        head.putInt(random.nextBoolean() ? 3 : random.nextInt());
        head.putLong(random.nextInt(4));
        head.putLong(random.nextInt(4));
        byte[] tail = new byte[random.nextInt(300)];
        random.nextBytes(tail);
        byte[] out = new byte[head.position() + tail.length];
        System.arraycopy(head.array(), 0, out, 0, head.position());
        System.arraycopy(tail, 0, out, head.position(), tail.length);
        if (random.nextInt(5) == 0) {
            byte[] truncated = new byte[random.nextInt(out.length + 1)];
            System.arraycopy(out, 0, truncated, 0, truncated.length);
            return truncated;
        }
        if (random.nextInt(7) == 0) {
            // hostile counts with a tiny body
            ByteBuffer hostile = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            hostile.putInt(0x46554747);
            hostile.putInt(3);
            hostile.putLong(Long.MAX_VALUE - random.nextInt(100));
            hostile.putLong(Long.MAX_VALUE - random.nextInt(100));
            return hostile.array();
        }
        return out;
    }

    private static byte[] mutatedSafetensors(Random random) {
        String[] headers = {
                "{\"__metadata__\":{},\"w\":{\"dtype\":\"F32\",\"shape\":[4],\"data_offsets\":[0,16]}}",
                "{\"w\":{\"dtype\":\"F32\",\"shape\":[-1],\"data_offsets\":[0,16]}}",
                "{\"w\":{\"dtype\":\"F32\",\"shape\":[9999999999999999999],\"data_offsets\":[0,16]}}",
                "{\"w\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[16,0]}}",
                "{\"w\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[0,0]}}",
                "{\"notatensor\":[1,2,3]}",
                "{\"__metadata__\":{\"a\":\"b\"}}",
                "{",
                "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[",
        };
        String header = headers[random.nextInt(headers.length)];
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        long declared = random.nextInt(10) == 0
                ? 64L * 1024 * 1024 + 1
                : headerBytes.length;
        ByteBuffer out = ByteBuffer.allocate(8 + headerBytes.length + 32).order(ByteOrder.LITTLE_ENDIAN);
        out.putLong(declared);
        out.put(headerBytes);
        byte[] body = new byte[32];
        random.nextBytes(body);
        out.put(body);
        byte[] all = out.array();
        if (random.nextInt(4) == 0) {
            byte[] truncated = new byte[random.nextInt(all.length + 1)];
            System.arraycopy(all, 0, truncated, 0, truncated.length);
            return truncated;
        }
        return all;
    }

    private static byte[] mutatedOnnx(Random random) {
        byte[] buf = new byte[random.nextInt(200)];
        random.nextBytes(buf);
        if (random.nextBoolean() && buf.length > 4) {
            // varint-ish tag prefix: field 1, wire type 2, small length
            buf[0] = 0x0A;
            buf[1] = 0x04;
        }
        return buf;
    }
}
