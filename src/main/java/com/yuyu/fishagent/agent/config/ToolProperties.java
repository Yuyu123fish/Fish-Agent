package com.yuyu.fishagent.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 外部工具的统一配置入口，对应 {@code fish.tools.*}。
 * <p>
 * 各子配置项是否启用由其对应工具 {@code @ConditionalOnProperty} 自行判断，缺失 key 时不装配。
 * 工具结果治理也放在这里，避免新增同名配置 Bean 并保持工具配置边界集中。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.tools")
public class ToolProperties {

    /** 全局默认工具返回字符上限。 */
    private int maxResultChars = 4000;

    /** 超过此阈值时追加提示，引导模型先提取关键信息。 */
    private int hintThresholdChars = 1500;

    /** 各工具可覆盖默认上限，key 为工具名（小写下划线）。 */
    private Map<String, Integer> overrides = new HashMap<>(Map.of(
            "web_fetch", 6000,
            "file_read", 8000,
            "web_search", 3000
    ));

    private Tavily tavily = new Tavily();
    private Bocha bocha = new Bocha();
    private Amap amap = new Amap();

    @Data
    public static class Tavily {
        private String apiKey;
        private String baseUrl = "https://api.tavily.com";
        private int maxResults = 5;
    }

    @Data
    public static class Bocha {
        private String apiKey;
        private String baseUrl = "https://api.bochaai.com";
        private int count = 5;
    }

    @Data
    public static class Amap {
        private String key;
        private String baseUrl = "https://restapi.amap.com";
    }

    public int getMaxResultChars(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return maxResultChars;
        }
        return overrides.getOrDefault(toolName, maxResultChars);
    }
}
