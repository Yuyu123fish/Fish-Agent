package com.yuyu.fishagent.config.llm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * 在环境就绪早期，将 {@code fish.llm.chat-provider} 映射为 {@code spring.ai.model.chat}，
 * 以便 Spring AI / Alibaba 的 {@code @ConditionalOnProperty} 只激活<strong>一个</strong> Chat 自动配置，
 * 避免出现多个 {@link org.springframework.ai.chat.model.ChatModel} Bean 冲突。
 * <p>
 * 本处理器使用 {@link Ordered#LOWEST_PRECEDENCE}，在 {@code ConfigData} 已加载 {@code application.yml}
 * <strong>之后</strong>再执行，才能可靠找到 {@code applicationConfig:} 锚点；若仍过早执行，
 * 会退化为 {@code addLast}，可能导致 {@code spring.ai.model.chat} 被后续配置覆盖，从而出现
 * {@code fish.llm.chat-provider=OLLAMA} 但 Ollama 自动配置未生效的问题。
 * </p>
 * <p>
 * 推导出的 {@code spring.ai.model.chat} 插入到首个 classpath 应用配置属性源<strong>之前</strong>，
 * 从而<strong>覆盖 yaml 中与 fish 冲突的 spring.ai.model.chat</strong>，但仍低于
 * {@code systemProperties} / {@code systemEnvironment}，便于用 {@code -D} 或 {@code SPRING_AI_MODEL_CHAT} 做最终覆盖。
 * </p>
 *
 * <p>注册方式：在 {@code META-INF/spring.factories} 中声明键
 * {@code org.springframework.boot.env.EnvironmentPostProcessor}（Spring Boot 3.x 仍从该文件加载）。</p>
 */
public class FishLlmEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(FishLlmEnvironmentPostProcessor.class);

    @Override
    public int getOrder() {
        // 必须在 ConfigDataEnvironmentPostProcessor 之后执行，否则尚无 applicationConfig: 锚点。
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** 注入到 Environment 末尾的 {@link MapPropertySource} 名称，便于排查属性来源。 */
    static final String PROPERTY_SOURCE_NAME = "fishLlmSpringAiModelChat";

    /**
     * 根据 {@code fish.llm.chat-provider} 写入 {@code spring.ai.model.chat}，插入位置见类注释。
     *
     * @param environment 可配置环境
     * @param application   当前 Spring 应用（未使用，保留接口签名）
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty("fish.llm.chat-provider");
        FishLlmChatProvider provider = FishLlmChatProvider.parse(raw);
        String value = provider.toSpringAiModelChatValue();
        MapPropertySource routing = new MapPropertySource(
                PROPERTY_SOURCE_NAME,
                Collections.singletonMap("spring.ai.model.chat", value));
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            sources.remove(PROPERTY_SOURCE_NAME);
        }
        // 插在 application.yml 等 ConfigData 之前：fish 与 yaml 中 spring.ai.model.chat 冲突时以 fish 为准。
        String configAnchor = findFirstClasspathApplicationPropertySourceName(sources);
        if (configAnchor != null) {
            sources.addBefore(configAnchor, routing);
            log.info("[FishLlm] 已根据 fish.llm.chat-provider={} 设置 spring.ai.model.chat={}（插入于 '{}' 之前）",
                    provider, value, configAnchor);
        } else {
            sources.addLast(routing);
            log.warn("[FishLlm] 未找到 applicationConfig 属性源锚点，已将 spring.ai.model.chat={} 置于 PropertySources 末尾；"
                    + "若仍无法启用 Ollama，请检查是否被更高优先级属性源覆盖，或设置 SPRING_AI_MODEL_CHAT=ollama。", value);
        }
    }

    /**
     * 查找 Spring Boot ConfigData 为 classpath 应用配置文件注入的属性源名称，用于在其<strong>前</strong>插入路由键。
     *
     * @param sources 可变属性源集合
     * @return 锚点名称；未找到时返回 {@code null}
     */
    private static String findFirstClasspathApplicationPropertySourceName(MutablePropertySources sources) {
        for (PropertySource<?> ps : sources) {
            String name = ps.getName();
            if (name == null) {
                continue;
            }
            if (name.startsWith("applicationConfig:")) {
                return name;
            }
            // 少数环境 / 版本下 ConfigData 展示名不同，做宽松匹配。
            if (name.contains("application.yml") && name.contains("classpath")) {
                return name;
            }
        }
        return null;
    }
}
