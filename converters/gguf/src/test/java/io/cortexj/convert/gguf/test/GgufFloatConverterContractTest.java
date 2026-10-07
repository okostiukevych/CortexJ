package io.cortexj.convert.gguf.test;

import io.cortexj.convert.gguf.GgufFloatConverter;
import io.cortexj.convert.gguf.internal.GgufBinaryReader;
import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.conversion.ModelConverter;
import io.cortexj.tck.ConverterContract;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Audit 2026-10-07 (#148): the conversion-tier contract, exercised against
 * the real F32/F16 converter with a synthesized fixture.
 */
class GgufFloatConverterContractTest extends ConverterContract {

    @Override
    protected ModelConverter converter() {
        return new GgufFloatConverter();
    }

    @Override
    protected Path convertibleSourcePath() {
        try {
            GgufDocument document = new GgufDocument();
            document.version = 3;
            document.metadata.add(new GgufDocument.KeyValue(
                    "general.architecture", GgufBinaryReader.Types.STRING, "llama"));
            document.tensors.add(new GgufDocument.TensorInfo(
                    "token_embd.weight", new long[]{4L, 1L}, GgufDocument.TENSOR_F32, 0L));
            Path file = tempDir.resolve("contract-source.gguf");
            try (OutputStream raw = Files.newOutputStream(file);
                 OutputStream buffered = new BufferedOutputStream(raw, 1 << 16)) {
                GgufBinaryWriter writer = new GgufBinaryWriter(buffered);
                document.writeHeader(writer);
                writer.writeFloat32(1.5f);
                writer.writeFloat32(-2.25f);
                writer.writeFloat32(0.0625f);
                writer.writeFloat32(1024.0f);
                writer.flush();
            }
            return file;
        } catch (IOException fixtureFailure) {
            throw new IllegalStateException("cannot synthesize the contract fixture", fixtureFailure);
        }
    }

    @Override
    protected ModelArtifact convertibleSourceArtifact() {
        return new ModelArtifact.Abstract("contract-source.gguf", ArtifactFormat.GGUF,
                1_000L, null,
                java.util.Collections.<String, String>emptyMap()) {
        };
    }

    @Override
    protected ArtifactFormat convertibleTargetFormat() {
        return ArtifactFormat.GGUF;
    }
}
