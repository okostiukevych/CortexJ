package io.cortexj.planner.test;

import io.cortexj.error.PlanningException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ConversionStep;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionResult;
import io.cortexj.spi.conversion.ConversionSupport;
import io.cortexj.spi.conversion.ConverterId;
import io.cortexj.spi.conversion.ModelConverter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionPlanningTest {

    private static final ModelReference MODEL = ModelReference.of("conv/model");

    static final class SafetensorsToGgufConverter implements ModelConverter {

        int evaluations;

        @Override
        public ConverterId id() {
            return ConverterId.of("test-st-gguf");
        }

        @Override
        public ConversionSupport evaluate(ConversionRequest request) {
            evaluations++;
            if (request.sourceArtifact().format() == ArtifactFormat.SAFETENSORS
                    && request.targetFormat() == ArtifactFormat.GGUF) {
                return ConversionSupport.supported();
            }
            return ConversionSupport.unsupported(
                    io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                    "test converter only supports safetensors -> gguf");
        }

        @Override
        public ConversionResult convert(ConversionRequest request) {
            throw new UnsupportedOperationException("planner tests never execute conversions");
        }
    }

    private io.cortexj.planner.internal.DefaultExecutionPlanner planner(
            List<ModelConverter> converters, ModelArtifact... repositoryArtifacts) {
        return new io.cortexj.planner.internal.DefaultExecutionPlanner(
                new io.cortexj.planner.internal.PlannerInputs(
                        Arrays.asList(
                                (io.cortexj.spi.runtime.RuntimeProvider) new TestRuntimeProvider("gguf-only", ArtifactFormat.GGUF)
                                        .devices(io.cortexj.runtime.DeviceType.CPU)
                                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)),
                        Collections.singletonList((io.cortexj.spi.repository.ModelRepositoryProvider)
                                new TestRepositoryProvider(repositoryArtifacts)),
                        converters,
                        new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                        null,
                        TestRepositoryProvider.environment(25),
                        TestRepositoryProvider.hardware(false),
                        null, null, null, null,
                        false, true, "0.1.0-test", 0L));
    }

    @Test
    void levelCPlanSelectsConversionWhenNoDirectArtifactExists() {
        SafetensorsToGgufConverter converter = new SafetensorsToGgufConverter();
        ExecutionPlan plan = planner(
                Collections.<ModelConverter>singletonList(converter),
                TestRuntimeProvider.artifact("model.safetensors", ArtifactFormat.SAFETENSORS, 4_000_000_000L, null))
                .plan(ExecutionRequest.forModel(MODEL));

        assertTrue(plan.selected().requiresConversion());
        ConversionStep step = plan.selected().conversion();
        assertEquals("test-st-gguf", step.converterId());
        assertEquals("model.safetensors", step.sourceArtifactId());
        assertEquals(ArtifactFormat.SAFETENSORS, step.sourceFormat());
        assertEquals(ArtifactFormat.GGUF, step.targetFormat());
        assertEquals("gguf-only", plan.selected().runtimeId());
        assertTrue(plan.explanation().contains("conversion:"),
                "explanation must mention the conversion step:\n" + plan.explanation());
        assertTrue(plan.selected().artifact().id().startsWith("model.safetensors."),
                "converted artifact id must be derived from the source");
    }

    @Test
    void directArtifactWinsOverConversion() {
        SafetensorsToGgufConverter converter = new SafetensorsToGgufConverter();
        ExecutionPlan plan = planner(
                Collections.<ModelConverter>singletonList(converter),
                TestRuntimeProvider.artifact("model-q4.gguf", ArtifactFormat.GGUF, 1_000_000_000L, "q4_k"),
                TestRuntimeProvider.artifact("model.safetensors", ArtifactFormat.SAFETENSORS, 4_000_000_000L, null))
                .plan(ExecutionRequest.forModel(MODEL));

        assertFalse(plan.selected().requiresConversion());
        assertEquals("model-q4.gguf", plan.selected().artifact().id());
        assertEquals(0, converter.evaluations,
                "converters must not be consulted when a direct artifact works");
    }

    @Test
    void withoutConvertersImpossiblePlanStaysHonest() {
        assertThrows(PlanningException.class,
                () -> planner(Collections.<ModelConverter>emptyList(),
                        TestRuntimeProvider.artifact("model.safetensors", ArtifactFormat.SAFETENSORS, 4_000_000_000L, null))
                        .plan(ExecutionRequest.forModel(MODEL)));
    }

    @Test
    void converterForWrongDirectionIsIgnored() {
        ModelConverter wrongDirection = new ModelConverter() {
            @Override
            public ConverterId id() {
                return ConverterId.of("test-wrong-way");
            }

            @Override
            public ConversionSupport evaluate(ConversionRequest request) {
                return ConversionSupport.unsupported(
                        io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                        "only gguf -> onnx");
            }

            @Override
            public ConversionResult convert(ConversionRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        assertThrows(PlanningException.class,
                () -> planner(Collections.<ModelConverter>singletonList(wrongDirection),
                        TestRuntimeProvider.artifact("model.safetensors", ArtifactFormat.SAFETENSORS, 4_000_000_000L, null))
                        .plan(ExecutionRequest.forModel(MODEL)));
    }
}
