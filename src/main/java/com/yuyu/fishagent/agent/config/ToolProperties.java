package com.yuyu.fishagent.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部工具的统一配置入口，对应 {@code fish.tools.*}。
 * <p>
 * 各子配置项是否启用由其对应工具 {@code @ConditionalOnProperty} 自行判断，缺失 key 时不装配。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.tools")
public class ToolProperties {

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
}
