package com.yuyu.fishagent.agent.tool.external;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import com.yuyu.fishagent.agent.config.ToolProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 高德实时/预报天气工具。
 * <p>用户传入 {@code city}（adcode 或中文城市名）；如非数字 adcode 会先调地理编码接口转换。
 * <p>API 文档：<a href="https://lbs.amap.com/api/webservice/guide/api-advanced/weatherinfo">天气查询</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.tools.amap", name = "key")
public class AmapWeatherToolProvider implements AgentToolProvider {

    private final ToolProperties properties;

    public record Input(String city, String extensions) {}

    public record Output(String city, String adcode, String weather, String temperature,
                         String windDirection, String windPower, String humidity, String reportTime,
                         List<Map<String, Object>> forecasts) {}

    @Override
    public String name() {
        return "amap_weather";
    }

    @Override
    public ToolCallback build() {
        ToolProperties.Amap cfg = properties.getAmap();
        RestClient client = RestClient.builder().baseUrl(cfg.getBaseUrl()).build();

        Function<Input, Output> fn = input -> {
            if (input == null || input.city() == null || input.city().isBlank()) {
                return new Output("", "", "ERROR: city is required", "", "", "", "", "", List.of());
            }
            // 使用 final 变量便于内层 uri lambda 捕获；非数字 adcode 时先调地理编码接口转换
            final String adcode;
            if (input.city().matches("^\\d{4,6}$")) {
                adcode = input.city();
            } else {
                String resolved = lookupAdcode(client, cfg.getKey(), input.city());
                if (resolved == null) {
                    return new Output(input.city(), "", "ERROR: 无法解析城市编码", "", "", "", "", "", List.of());
                }
                adcode = resolved;
            }
            String ext = (input.extensions() == null || input.extensions().isBlank()) ? "base" : input.extensions();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = client.get()
                        .uri(uri -> uri.path("/v3/weather/weatherInfo")
                                .queryParam("key", cfg.getKey())
                                .queryParam("city", adcode)
                                .queryParam("extensions", ext)
                                .queryParam("output", "JSON")
                                .build())
                        .retrieve()
                        .body(Map.class);
                if (resp == null || !"1".equals(String.valueOf(resp.get("status")))) {
                    return new Output(input.city(), adcode,
                            "ERROR: " + (resp == null ? "null" : resp.get("info")),
                            "", "", "", "", "", List.of());
                }
                if ("base".equalsIgnoreCase(ext)) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> lives = (List<Map<String, Object>>) resp.getOrDefault("lives", List.of());
                    if (lives.isEmpty()) {
                        return new Output(input.city(), adcode, "no data", "", "", "", "", "", List.of());
                    }
                    Map<String, Object> live = lives.get(0);
                    return new Output(
                            String.valueOf(live.getOrDefault("city", input.city())),
                            String.valueOf(live.getOrDefault("adcode", adcode)),
                            String.valueOf(live.getOrDefault("weather", "")),
                            String.valueOf(live.getOrDefault("temperature", "")),
                            String.valueOf(live.getOrDefault("winddirection", "")),
                            String.valueOf(live.getOrDefault("windpower", "")),
                            String.valueOf(live.getOrDefault("humidity", "")),
                            String.valueOf(live.getOrDefault("reporttime", "")),
                            List.of()
                    );
                } else {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> forecasts = (List<Map<String, Object>>) resp.getOrDefault("forecasts", List.of());
                    if (forecasts.isEmpty()) {
                        return new Output(input.city(), adcode, "no data", "", "", "", "", "", List.of());
                    }
                    Map<String, Object> f = forecasts.get(0);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> casts = (List<Map<String, Object>>) f.getOrDefault("casts", List.of());
                    return new Output(
                            String.valueOf(f.getOrDefault("city", input.city())),
                            String.valueOf(f.getOrDefault("adcode", adcode)),
                            "forecast", "", "", "", "",
                            String.valueOf(f.getOrDefault("reporttime", "")),
                            casts
                    );
                }
            } catch (Exception e) {
                log.warn("amap_weather 调用失败: {}", e.getMessage());
                return new Output(input.city(), adcode, "ERROR: " + e.getMessage(), "", "", "", "", "", List.of());
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("查询中国城市的实时天气或未来预报。city 接受 adcode（如 110000）或中文城市名（如 上海）；extensions=base 实时(默认)、all 预报。")
                .inputType(Input.class)
                .build();
    }

    /**
     * 复用高德地理编码接口，将中文城市名解析为 adcode。
     */
    private static String lookupAdcode(RestClient client, String key, String address) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = client.get()
                    .uri(uri -> uri.path("/v3/geocode/geo")
                            .queryParam("key", key)
                            .queryParam("address", address)
                            .queryParam("output", "JSON")
                            .build())
                    .retrieve()
                    .body(Map.class);
            if (resp == null) return null;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> geocodes = (List<Map<String, Object>>) resp.getOrDefault("geocodes", List.of());
            if (geocodes.isEmpty()) return null;
            Object adcode = geocodes.get(0).get("adcode");
            return adcode == null ? null : String.valueOf(adcode);
        } catch (Exception e) {
            log.warn("amap geocode 失败: {}", e.getMessage());
            return null;
        }
    }
}
