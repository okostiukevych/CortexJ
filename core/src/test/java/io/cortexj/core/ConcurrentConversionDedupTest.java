package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionResult;
import io.cortexj.spi.conversion.ConversionSupport;
import io.cortexj.spi.conversion.ConverterId;
import io.cortexj.spi.conversion.ModelConverter;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentConversionDedupTest {

    @TempDir
    Path tempDir;

    private static final byte[] SOURCE_BYTES = "fake-safetensors-weights".getBytes();
    private static final byte[] CONVERTED_BYTES = "converted-gguf-weights".getBytes();

    static final class CountingConverter implements ModelConverter {

        final AtomicInteger conversions = new AtomicInteger();
        volatile long firstConversionMillis = -1L;

        @Override
        public ConverterId id() {
            return ConverterId.of("dedup-st-gguf");
        }

        @Override
        public ConversionSupport evaluate(ConversionRequest request) {
            if (request.sourceArtifact().format() == ArtifactFormat.SAFETENSORS
                    && request.targetFormat() == ArtifactFormat.GGUF) {
                return ConversionSupport.supported();
            }
            return ConversionSupport.unsupported(
                    io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED, "dedup: safetensors only");
        }

        @Override
        public ConversionResult convert(ConversionRequest request) {
            conversions.incrementAndGet();
            synchronized (this) {
                if (firstConversionMillis < 0) {
                    firstConversionMillis = System.currentTimeMillis();
                }
            }
            try {
                Thread.sleep(200);
                Path output = request.outputDirectory().resolve("model.safetensors.gguf");
                Files.write(output, CONVERTED_BYTES);
                io.cortexj.model.ModelArtifact out = new io.cortexj.model.ModelArtifact.Abstract(
                        "model.safetensors.gguf", ArtifactFormat.GGUF, CONVERTED_BYTES.length,
                        null, null) {
                };
                return new ConversionResult(out, output,
                        Collections.singletonMap("converterVersion", "1.0.0-dedup"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("conversion interrupted", e);
            }
        }
    }

    private ResolvedModel resolvedModel(Path safetensorsFile) {
        io.cortexj.model.ModelArtifact source = new io.cortexj.model.ModelArtifact.Abstract(
                "model.safetensors", ArtifactFormat.SAFETENSORS, SOURCE_BYTES.length,
                null, null) {
        };
        io.cortexj.model.ModelDescriptor descriptor = io.cortexj.model.ModelDescriptor
                .builder("e2e/convertible-model")
                .revision("rev1")
                .architecture("llama")
                .task(TaskCapability.TEXT_GENERATION)
                .artifact(source)
                .build();
        final URI location = safetensorsFile.toUri();
        ResolvedArtifact artifact = new ResolvedArtifact() {
            @Override
            public io.cortexj.model.ModelArtifact artifact() {
                return source;
            }

            @Override
            public URI location() {
                return location;
            }

            @Override
            public java.util.Map<String, String> downloadHeaders() {
                return Collections.emptyMap();
            }
        };
        return new ResolvedModel(descriptor, "rev1", RepositoryId.of("e2e-repo"),
                Collections.singletonList(artifact));
    }

    @Test
    void concurrentLoadsWithSameConversionTriggerExactlyOneConvert() throws Exception {
        Path safetensors = tempDir.resolve("model.safetensors");
        Files.write(safetensors, SOURCE_BYTES);

        ConversionEndToEndTest.RecordingRuntimeProvider runtime =
                new ConversionEndToEndTest.RecordingRuntimeProvider();
        CountingConverter converter = new CountingConverter();
        Path workDir = tempDir.resolve("cortexj-work");

        final ResolvedModel resolved = resolvedModel(safetensors);
        ModelRepositoryProvider repository = new ModelRepositoryProvider() {
            @Override
            public RepositoryId id() {
                return RepositoryId.of("e2e-repo");
            }

            @Override
            public boolean supports(ModelReference reference) {
                return true;
            }

            @Override
            public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
                return resolved;
            }
        };

        CortexJ platform = CortexJ.builder()
                .runtimeProvider(runtime)
                .repositoryProvider(repository)
                .converter(converter)
                .workingDirectory(workDir)
                .build();
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<ModelHandle> handles = Collections.synchronizedList(new ArrayList<ModelHandle>());
            final RuntimeException[] failures = new RuntimeException[3];
            Thread[] threads = new Thread[3];
            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                threads[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                            handles.add(platform.models().load(ModelReference.of("e2e/convertible-model")));
                        } catch (RuntimeException e) {
                            failures[index] = e;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                threads[i].start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join(30_000);
            }
            for (int i = 0; i < threads.length; i++) {
                if (failures[i] != null) {
                    throw failures[i];
                }
            }
            assertEquals(3, handles.size(), "all three loads must succeed");

            assertEquals(1, converter.conversions.get(),
                    "concurrent loads requiring the same conversion must share one convert() invocation");

            assertEquals(3, runtime.loadedPaths.size());
            for (Path runtimePath : runtime.loadedPaths) {
                assertArrayEquals(CONVERTED_BYTES, Files.readAllBytes(runtimePath),
                        "every waiter must receive the valid converted artifact");
            }

            for (ModelHandle handle : handles) {
                handle.close();
            }

            assertEquals(1, converter.conversions.get(),
                    "no further conversions after the waiters joined");
        } finally {
            platform.close();
        }
    }
}
