package io.cortexj.examples.springai;

import io.cortexj.CortexJ;
import io.cortexj.springai.CortexJChatModel;

/**
 * Spring AI integration.
 *
 * <p>In a Spring context the adapter is simply a bean; business services keep using the
 * Spring AI API and never touch CortexJ directly:</p>
 *
 * <pre>{@code
 * @Configuration
 * public class AiConfig {
 *
 *     @Bean(destroyMethod = "close")
 *     public CortexJ cortexjPlatform() {
 *         return CortexJ.builder().build();
 *     }
 *
 *     @Bean(destroyMethod = "close")
 *     public ChatModel chatModel(CortexJ platform) {
 *         return new CortexJChatModel(platform, "Qwen/Qwen3-8B");
 *     }
 * }
 * }</pre>
 *
 * <p>This class shows the same wiring without the Spring context dependency.</p>
 */
public final class SpringAiWiring {

    public static void main(String[] args) throws Exception {
        CortexJ platform = CortexJ.builder().build();
        // @Bean chatModel(CortexJ platform)
        try (CortexJChatModel chatModel = new CortexJChatModel(platform, "Qwen/Qwen3-8B")) {
            org.springframework.ai.chat.model.ChatResponse response = chatModel.call(
                    new org.springframework.ai.chat.prompt.Prompt(
                            java.util.List.of(new org.springframework.ai.chat.messages.UserMessage("hi"))));
            System.out.println(response.getResult().getOutput().getText());
        } finally {
            platform.close();
        }
    }

    private SpringAiWiring() {
    }
}
