package io.cortexj.declarative.test;

import io.cortexj.declarative.JsonPromptDecoder;
import io.cortexj.declarative.ModelInterfaces;
import io.cortexj.declarative.ModelPrompt;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.CortexJ;
import io.cortexj.Models;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelInterfacesTest {

    interface Echo {
        @ModelPrompt("Repeat this exactly: {text}")
        String echo(String text);
    }

    interface Amount {
        @ModelPrompt("Extract the total from: {document}. Reply as JSON {\"total\": <number>}")
        Total extract(String document);
    }

    interface Unannotated {
        String call(String input);
    }

    static final class Total {
        public double total;
    }

    private ModelHandle handle(final String reply) {
        return new ModelHandle() {
            @Override
            public io.cortexj.model.ModelDescriptor descriptor() {
                return io.cortexj.model.ModelDescriptor.builder("test/model").build();
            }

            @Override
            public io.cortexj.planner.ExecutionPlan plan() {
                return null;
            }

            @Override
            public io.cortexj.model.ModelCapabilities capabilities() {
                return null;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T capability(Class<T> capabilityType) {
                if (capabilityType == TextGenerationCapability.class) {
                    return (T) new TextGenerationCapability() {
                        @Override
                        public GenerationResponse generate(GenerationRequest request) {
                            return new GenerationResponse(reply,
                                    io.cortexj.inference.FinishReason.STOP,
                                    io.cortexj.inference.TokenUsage.unknown(), null);
                        }

                        @Override
                        public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
                            throw new UnsupportedOperationException();
                        }
                    };
                }
                throw new IllegalArgumentException("unsupported " + capabilityType);
            }

            @Override
            public <T> java.util.Optional<T> capabilityIfSupported(Class<T> capabilityType) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean supports(io.cortexj.model.TaskCapability task) {
                return false;
            }

            @Override
            public io.cortexj.model.ModelLifecycleState state() {
                return io.cortexj.model.ModelLifecycleState.READY;
            }

            @Override
            public void waitForReady() {
            }

            @Override
            public void close() {
            }
        };
    }

    private CortexJ fakePlatform() {
        return new CortexJ() {
            @Override
            public Models models() {
                return new Models() {
                    @Override
                    public ModelHandle load(ModelReference reference) {
                        return handle("fixed");
                    }

                    @Override
                    public ModelHandle load(io.cortexj.planner.ExecutionRequest request) {
                        return handle("fixed");
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(ModelReference reference) {
                        return java.util.concurrent.CompletableFuture.completedFuture(handle("fixed"));
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(io.cortexj.planner.ExecutionRequest request) {
                        return java.util.concurrent.CompletableFuture.completedFuture(handle("fixed"));
                    }
                };
            }

            @Override
            public io.cortexj.planner.Planner planner() {
                return null;
            }

            @Override
            public io.cortexj.diagnostics.Support support() {
                return null;
            }

            @Override
            public io.cortexj.hardware.EnvironmentProfile environment() {
                return null;
            }

            @Override
            public io.cortexj.hardware.HardwareProfile hardware() {
                return null;
            }

            @Override
            public List<io.cortexj.runtime.RuntimeDescriptor> runtimes() {
                return java.util.Collections.emptyList();
            }

            @Override
            public List<io.cortexj.runtime.RuntimeHealthReport> runtimeHealth() {
                return java.util.Collections.emptyList();
            }

            @Override
            public String platformModule() {
                return "cortexj-core";
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void stringMethodsRenderTemplatesAndReturnText() {
        Echo echo = ModelInterfaces.of(handle("INVOICE-42"), Echo.class, null);
        assertEquals("INVOICE-42", echo.echo("INVOICE-42"));
    }

    @Test
    void structuredMethodsDecodeViaPromptDecoder() {
        Amount amount = ModelInterfaces.of(handle("{\"total\": 149.99}"), Amount.class,
                new JsonPromptDecoder<Total>());
        Total total = amount.extract("coffee 149.99");
        assertEquals(149.99, total.total, 0.001);
    }

    @Test
    void malformedJsonFailsLoudly() {
        Amount amount = ModelInterfaces.of(handle("sorry, no json here"), Amount.class,
                new JsonPromptDecoder<Total>());
        assertThrows(IllegalArgumentException.class, () -> amount.extract("anything"));
    }

    @Test
    void unannotatedMethodsFailWithActionableError() {
        Unannotated unannotated = ModelInterfaces.of(handle("x"), Unannotated.class, null);
        assertThrows(IllegalStateException.class, () -> unannotated.call("x"));
    }

    @Test
    void nonStringReturnWithoutDecoderFails() {
        Amount amount = ModelInterfaces.of(handle("{\"total\": 1}"), Amount.class, null);
        assertThrows(IllegalStateException.class, () -> amount.extract("x"));
    }

    @Test
    void declarativeWorksWithThePlatformFacade() {
        // spec 49 example shape: interface + platform, proxy is separate from core
        Echo echo = ModelInterfaces.of(
                fakePlatform().models().load(ModelReference.of("e2e/model")),
                Echo.class, null);
        assertEquals("fixed", echo.echo("anything"));
    }
}
