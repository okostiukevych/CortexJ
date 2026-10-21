package io.cortexj.springboot;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot auto-configuration contract: properties under {@code cortexj.*} produce
 * the platform and, when a model reference is set, an auto-wired ChatModel -
 * user-defined beans always win over the starters' ones, and an unresolvable
 * model reference fails the startup with a remediation message.
 */
class CortexJAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CortexJAutoConfiguration.class));

    @Test
    void withoutAModelReferenceOnlyThePlatformIsCreated() {
        runner.run(context -> {
            assertTrue(context.containsBean("cortexJPlatform"));
            assertTrue(context.getBeansOfType(ChatModel.class).isEmpty());
        });
    }

    @Test
    void modelReferenceAutoWiresAChatModelOnThePlatform() {
        runner.withPropertyValues("cortexj.model=fake/test-model")
                .withUserConfiguration(FakePlatformConfig.class)
                .run(context -> {
                    assertTrue(context.containsBean("cortexJChatModel"));
                    io.cortexj.springai.CortexJChatModel chatModel = context.getBean("cortexJChatModel",
                            io.cortexj.springai.CortexJChatModel.class);
                    assertEquals("fake/test-model", chatModel.getModelName());
                });
    }

    @Test
    void aUserDefinedChatModelWinsOverTheAutoConfiguration() {
        runner.withPropertyValues("cortexj.model=fake/test-model")
                .withUserConfiguration(FakePlatformConfig.class, UserChatModelConfig.class)
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(ChatModel.class).size());
                    assertSame(context.getBean("userChatModel"), context.getBean(ChatModel.class));
                });
    }

    @Test
    void platformBeanIsOverridableByTheUser() {
        runner.withUserConfiguration(FakePlatformConfig.class)
                .run(context -> {
                    // the fake replaces the auto-created platform entirely
                    assertSame(context.getBean("fakePlatform"), context.getBean(CortexJ.class));
                });
    }

    @Test
    void unknownModelReferenceFailsTheStartupWithARemediationMessage() {
        // honest fail-fast: a model the platform cannot resolve must break the
        // context startup, not produce a lazy surprise at the first call
        runner.withPropertyValues("cortexj.model=does/not-exist", "cortexj.offline=true")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "unresolvable model must fail the context startup");
                    // honest check: somewhere in the wrapped cause chain there
                    // must be a typed CortexJException with its CORTEXJ- code -
                    // not just a generic wrapper message
                    Throwable failure = context.getStartupFailure();
                    io.cortexj.error.CortexJException typed = null;
                    for (Throwable cause = failure; cause != null && typed == null; cause = cause.getCause()) {
                        if (cause instanceof io.cortexj.error.CortexJException cortexjException) {
                            typed = cortexjException;
                        }
                    }
                    assertNotNull(typed, "the failure must carry a typed CORTEXJ- exception: "
                            + failure);
                    assertTrue(typed.code().startsWith("CORTEXJ-"),
                            "the code must be the CORTEXJ- diagnostic: " + typed.code());
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class FakePlatformConfig {

        @Bean("fakePlatform")
        CortexJ fakePlatform() {
            return CortexJAutoConfigurationTest.fakePlatform();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserChatModelConfig {

        @Bean
        ChatModel userChatModel() {
            return new ChatModel() {
                @Override
                public org.springframework.ai.chat.model.ChatResponse call(
                        org.springframework.ai.chat.prompt.Prompt prompt) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                        org.springframework.ai.chat.prompt.Prompt prompt) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    private static CortexJ fakePlatform() {
        return new CortexJ() {
            @Override
            public io.cortexj.Models models() {
                return new io.cortexj.Models() {
                    @Override
                    public ModelHandle load(ModelReference reference) {
                        return new ModelHandle() {
                            @Override
                            public ModelDescriptor descriptor() {
                                return ModelDescriptor.builder("fake/test-model")
                                        .task(TaskCapability.TEXT_GENERATION)
                                        .build();
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
                            public <T> T capability(Class<T> capabilityType) {
                                throw new IllegalArgumentException(
                                        "capability not scripted in this test: " + capabilityType);
                            }

                            @Override
                            public <T> java.util.Optional<T> capabilityIfSupported(Class<T> capabilityType) {
                                return java.util.Optional.empty();
                            }

                            @Override
                            public boolean supports(TaskCapability task) {
                                return false;
                            }

                            @Override
                            public ModelLifecycleState state() {
                                return ModelLifecycleState.READY;
                            }

                            @Override
                            public void waitForReady() {
                            }

                            @Override
                            public void close() {
                            }
                        };
                    }

                    @Override
                    public ModelHandle load(io.cortexj.planner.ExecutionRequest request) {
                        return load((ModelReference) null);
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(ModelReference reference) {
                        return java.util.concurrent.CompletableFuture.completedFuture(load(reference));
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(io.cortexj.planner.ExecutionRequest request) {
                        return java.util.concurrent.CompletableFuture.completedFuture(load(request));
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
                return List.of();
            }

            @Override
            public List<io.cortexj.runtime.RuntimeHealthReport> runtimeHealth() {
                return List.of();
            }

            @Override
            public String platformModule() {
                return "cortexj-test";
            }

            @Override
            public void close() {
            }
        };
    }
}
