package com.yuyu.fishagent.agent.tool.external;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import com.yuyu.fishagent.config.ToolProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 博查 (Bocha) 联网搜索：国内可用、中文友好。
 * <p>API 文档：<a href="https://open.bochaai.com/">博查AI开放平台</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.tools.bocha", name = "api-key")
public class BochaSearchToolProvider implements AgentToolProvider {

    private final ToolProperties properties;

    public record Input(String query, String freshness, Integer count) {}

    public record Hit(String name, String url, String snippet, String summary, String siteName, String datePublished) {}

    public record Output(String query, List<Hit> results) {}

    @Override
    public String name() {
        return "web_search_bocha";
    }

    @Override
    public ToolCallback build() {
        ToolProperties.Bocha cfg = properties.getBocha();
        RestClient client = RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        Function<Input, Output> fn = input -> {
            if (input == null || input.query() == null || input.query().isBlank()) {
                return new Output("", List.of());
            }
            int count = (input.count() == null || input.count() <= 0) ? cfg.getCount() : Math.min(input.count(), 50);
            String freshness = (input.freshness() == null || input.freshness().isBlank()) ? "noLimit" : input.freshness();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = client.post()
                        .uri("/v1/web-search")
                        .body(Map.of(
                                "query", input.query(),
                                "freshness", freshness,
                                "summary", true,
                                "count", count
                        ))
                        .retrieve()
                        .body(Map.class);
                List<Hit> hits = new ArrayList<>();
                if (resp != null) {
                    Object data = resp.get("data");
                    if (data instanceof Map<?, ?> dataMap) {
                        Object webPages = dataMap.get("webPages");
                        if (webPages instanceof Map<?, ?> wpMap) {
                            Object value = wpMap.get("value");
                            if (value instanceof List<?> list) {
                                for (Object o : list) {
                                    if (o instanceof Map<?, ?> m) {
                                        hits.add(new Hit(
                                                str(m.get("name")),
                                                str(m.get("url")),
                                                str(m.get("snippet")),
                                                str(m.get("summary")),
                                                str(m.get("siteName")),
                                                str(m.get("datePublished"))
                                        ));
                                    }
                                }
                            }
                        }
                    }
                }
                return new Output(input.query(), hits);
            } catch (Exception e) {
                log.warn("Bocha 调用失败: {}", e.getMessage());
                return new Output(input.query(), List.of());
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("使用博查 (Bocha) 进行中文联网搜索，返回网页标题/URL/摘要。query 必填；freshness 可选 (noLimit/oneDay/oneWeek/oneMonth/oneYear)，count 默认 5。")
                .inputType(Input.class)
                .build();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
