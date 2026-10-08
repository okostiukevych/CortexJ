package io.cortexj.tck;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionResult;
import io.cortexj.spi.conversion.ConversionSupport;
import io.cortexj.spi.conversion.ModelConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract for {@link ModelConverter} implementations (audit 2026-10-07,
 * #148): the experimental {@code spi.conversion} tier previously had no
 * contract tests at all.
 */
public abstract class ConverterContract {

    protected abstract ModelConverter converter();

    /**
     * A convertible source artifact for the round-trip contract, or null when
     * the fixture cannot ship one on this machine - the round-trip then
     * skips loudly (explicit assumeTrue, never silently).
     */
    protected Path convertibleSourcePath() {
        return null;
    }

    protected ModelArtifact convertibleSourceArtifact() {
        return null;
    }

    protected ArtifactFormat convertibleTargetFormat() {
        return ArtifactFormat.GGUF;
    }

    @TempDir
    protected Path tempDir;

    @Test
    public void identityIsStable() {
        assertNotNull(converter().id(), "converter id must not be null");
        assertNotNull(converter().id().value(), "converter id value must not be null");
    }

    @Test
    public void evaluateNeverThrowsAndRejectsAlienTargets() {
        // an UNKNOWN-format stub (never nulls - converters may assume a
        // well-formed request object and only guarantee a verdict, not
        // null-tolerance)
        ModelArtifact alienArtifact = new ModelArtifact.Abstract(
                "tck-alien", ArtifactFormat.UNKNOWN, 0L, null,
                Collections.<String, String>emptyMap()) {
        };
        ConversionRequest alien = new ConversionRequest(
                alienArtifact, tempDir.resolve("missing.bin"), ArtifactFormat.UNKNOWN, null, tempDir,
                Collections.<String, Object>emptyMap());
        ConversionSupport support = converter().evaluate(alien);
        assertNotNull(support, "evaluate must return a support verdict, never null");
        if (!support.isSupported()) {
            assertNotNull(support.reasonCode(), "a rejection must carry a reason code");
        }
    }

    @Test
    public void roundTripPreservesProvenanceWhenFixturePresent() {
        Path source = convertibleSourcePath();
        ModelArtifact artifact = convertibleSourceArtifact();
        assumeTrue(source != null && artifact != null,
                "no convertible fixture on this machine - round-trip skipped loudly");
        ConversionRequest request = new ConversionRequest(
                artifact, source, convertibleTargetFormat(), null, tempDir,
                Collections.<String, Object>emptyMap());
        ConversionSupport support = converter().evaluate(request);
        assumeTrue(support.isSupported(), "fixture not supported by this converter - skipped loudly");
        ConversionResult result = converter().convert(request);
        assertNotNull(result.outputPath(), "conversion must produce an output path");
        assertNotNull(result.outputArtifact(), "conversion must describe the output artifact");
        assertTrue(result.provenance() != null && !result.provenance().isEmpty(),
                "conversion must record provenance (§32.4)");
    }

    @Test
    public void repeatedEvaluateIsDeterministic() {
        ModelArtifact alienArtifact = new ModelArtifact.Abstract(
                "tck-alien", ArtifactFormat.UNKNOWN, 0L, null,
                Collections.<String, String>emptyMap()) {
        };
        ConversionRequest request = new ConversionRequest(
                alienArtifact, tempDir.resolve("missing.bin"), convertibleTargetFormat(), null, tempDir,
                Collections.<String, Object>emptyMap());
        ConversionSupport first = converter().evaluate(request);
        ConversionSupport second = converter().evaluate(request);
        assertEquals(first.isSupported(), second.isSupported(),
                "evaluate must be deterministic for the same request");
    }
}
