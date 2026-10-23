package io.cortexj.springai;

import io.cortexj.ErrorCode;
import io.cortexj.error.ConfigurationException;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Verifies the classpath provides the Spring AI major the adapter is built for.
 *
 * <p>The artifact is compiled against Spring AI 2.0.x. Every signature the adapter
 * invokes (messages, options, metadata builders, tool responses) is stable across
 * the 1.x and 2.x lines - the new adapter compiles against 1.0.7 unchanged except
 * for {@code getOptions()}, added in 2.0. Despite that accidental compatibility,
 * Spring AI 1.x reached EOL together with Spring Boot 3.x (June 30, 2026) and is
 * not supported: no fixes, no test lane. Instead of letting a 1.x classpath fail
 * late with an obscure {@code NoSuchMethodError}, the chat adapters fail fast at
 * construction with an actionable diagnostic naming the required minimum version.</p>
 *
 * <p>The probe is the presence of {@link ChatModel#getOptions()} on the
 * {@code ChatModel} interface the JVM actually loaded - it exists since 2.0.0
 * (replacing the deprecated {@code getDefaultOptions()}). If a Spring AI 1.x jar
 * wins the classpath, the lookup fails and the guard fires.</p>
 */
final class SpringAiSupport {

    private SpringAiSupport() {
    }

    /** Fail-fast guard for chat adapters; embedding adapter is version-tolerant. */
    static void requireChatAdapterSupport() {
        require(isSpringAi2Present());
    }

    /**
     * True when the loaded {@code ChatModel} interface declares {@code getOptions()},
     * i.e. the classpath resolved to Spring AI 2.0 or newer.
     */
    static boolean isSpringAi2Present() {
        try {
            ChatModel.class.getDeclaredMethod("getOptions");
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    static void require(boolean springAi2Present) {
        if (springAi2Present) {
            return;
        }
        throw new ConfigurationException(
                ErrorCode.CONF_INVALID,
                "cortexj-spring-ai requires Spring AI 2.0.x or newer; the classpath resolved to Spring AI 1.x "
                        + "(ChatModel.getOptions() is absent, a 2.0-only method)",
                "Upgrade to Spring AI 2.x: the 1.x line reached EOL together with Spring Boot 3.x (June 30, 2026) "
                        + "and is not covered by CortexJ compatibility tests. For Spring AI 1.x applications use the "
                        + "direct CortexJ API or the embedded OpenAI-compatible server.");
    }
}
