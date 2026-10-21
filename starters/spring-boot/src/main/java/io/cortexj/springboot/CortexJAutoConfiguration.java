package io.cortexj.springboot;

import io.cortexj.CortexJ;
import io.cortexj.springai.CortexJChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration of the CortexJ platform and a {@link ChatModel} over it
 * (audit 2026-10-21 follow-up), driven by {@code cortexj.*} properties:
 *
 * <ul>
 *   <li>{@code cortexj.model} - the model reference to auto-load; when set,
 *       an auto-wired {@link CortexJChatModel} is created on the platform.</li>
 *   <li>{@code cortexj.offline}, {@code cortexj.allow-remote-runtime},
 *       {@code cortexj.working-directory}, {@code cortexj.trust-policy},
 *       {@code cortexj.memory-limit}, {@code cortexj.max-artifact-bytes},
 *       {@code cortexj.remote.base-url/api-key/model-prefix} and
 *       {@code cortexj.shutdown-hook} - pass to the platform builder.</li>
 * </ul>
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}: a user-defined
 * {@link CortexJ} or {@link ChatModel} disables the auto-configured one without
 * removing the starter from the classpath. An unresolvable
 * {@code cortexj.model} fails the startup with a typed {@code CORTEXJ-}
 * diagnostic (the platform planner refuses unknown references eagerly) instead
 * of lazily failing at the first call.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(CortexJChatModel.class)
@ConditionalOnProperty(prefix = "cortexj", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CortexJProperties.class)
public final class CortexJAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CortexJ.class)
    public CortexJ cortexJPlatform(CortexJProperties properties) {
        return properties.apply(CortexJ.builder()).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ChatModel.class)
    @ConditionalOnProperty(prefix = "cortexj", name = "model")
    public CortexJChatModel cortexJChatModel(CortexJ platform, CortexJProperties properties) {
        return new CortexJChatModel(platform, properties.getModel());
    }
}
