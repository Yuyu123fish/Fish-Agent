package com.yuyu.fishagent.agent.tool.external;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import com.yuyu.fishagent.agent.config.ToolProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Tavily 联网搜索工具：仅当配置 {@code fish.tools.tavily.api-key} 时装配。
 * <p>API 文档：<a href="https://docs.tavily.com/documentation/api-reference/endpoint/search">Tavily Search</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.tools.tavily", name = "api-key")
public class TavilySearchToolProvider implements AgentToolProvider {

    private final ToolProperties properties;

    public record Input(String query, Integer maxResults) {}

    public record Hit(String title, String url, String content, Double score) {}

    public record Output(String query, String answer, List<Hit> results) {}

    @Override
    public String name() {
        return "web_search_tavily";
    }

    @Override
    public ToolCallback build() {
        ToolProperties.Tavily cfg = properties.getTavily();
        // 构建 RestClient，配置 API 认证
        RestClient client = RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        Function<Input, Output> fn = input -> {
            if (input == null || input.query() == null || input.query().isBlank()) {
                return new Output("", "ERROR: query is required", List.of());
            }
            // 限制最大结果数，防止返回过多数据
            int max = (input.maxResults() == null || input.maxResults() <= 0)
                    ? cfg.getMaxResults() : Math.min(input.maxResults(), 20);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = client.post()
                        .uri("/search")
                        .body(Map.of(
                                "query", input.query(),
                                "search_depth", "basic",
                                "max_results", max,
                                "include_answer", true
                        ))
                        .retrieve()
                        .body(Map.class);
                if (resp == null) {
                    return new Output(input.query(), "ERROR: empty response", List.of());
                }
                // 提取搜索答案和结果列表
                String answer = (String) resp.getOrDefault("answer", "");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> raw = (List<Map<String, Object>>) resp.getOrDefault("results", List.of());
                List<Hit> hits = raw.stream().map(r -> new Hit(
                        String.valueOf(r.getOrDefault("title", "")),
                        String.valueOf(r.getOrDefault("url", "")),
                        String.valueOf(r.getOrDefault("content", "")),
                        r.get("score") instanceof Number n ? n.doubleValue() : null
                )).toList();
                return new Output(input.query(), answer, hits);
            } catch (Exception e) {
                log.warn("Tavily 调用失败: {}", e.getMessage());
                return new Output(input.query(), "ERROR: " + e.getMessage(), List.of());
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("使用 Tavily 进行联网搜索，返回相关网页摘要列表与简短答案。query 必填，maxResults 默认 5（最大 20）。")
                .inputType(Input.class)
                .build();
    }
}
