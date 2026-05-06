package com.yuyu.fishagent.agent.tool.external;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import com.yuyu.fishagent.config.ToolProperties;
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
 * 高德地理编码工具：将地址文本解析为 adcode、经纬度等结构化字段。
 * <p>API 文档：<a href="https://lbs.amap.com/api/webservice/guide/api/georegeo">地理编码</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.tools.amap", name = "key")
public class AmapGeoToolProvider implements AgentToolProvider {

    private final ToolProperties properties;

    public record Input(String address, String city) {}

    public record GeoHit(String formattedAddress, String province, String city, String district,
                         String adcode, String location, String level) {}

    public record Output(String address, List<GeoHit> results) {}

    @Override
    public String name() {
        return "amap_geocode";
    }

    @Override
    public ToolCallback build() {
        ToolProperties.Amap cfg = properties.getAmap();
        RestClient client = RestClient.builder().baseUrl(cfg.getBaseUrl()).build();

        Function<Input, Output> fn = input -> {
            if (input == null || input.address() == null || input.address().isBlank()) {
                return new Output("", List.of());
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = client.get()
                        .uri(uri -> {
                            var b = uri.path("/v3/geocode/geo")
                                    .queryParam("key", cfg.getKey())
                                    .queryParam("address", input.address())
                                    .queryParam("output", "JSON");
                            if (input.city() != null && !input.city().isBlank()) {
                                b.queryParam("city", input.city());
                            }
                            return b.build();
                        })
                        .retrieve()
                        .body(Map.class);
                if (resp == null || !"1".equals(String.valueOf(resp.get("status")))) {
                    return new Output(input.address(), List.of());
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> geocodes = (List<Map<String, Object>>) resp.getOrDefault("geocodes", List.of());
                List<GeoHit> hits = geocodes.stream().map(g -> new GeoHit(
                        str(g.get("formatted_address")),
                        str(g.get("province")),
                        str(g.get("city")),
                        str(g.get("district")),
                        str(g.get("adcode")),
                        str(g.get("location")),
                        str(g.get("level"))
                )).toList();
                return new Output(input.address(), hits);
            } catch (Exception e) {
                log.warn("amap_geocode 调用失败: {}", e.getMessage());
                return new Output(input.address(), List.of());
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("将地址解析为 adcode/经纬度等结构化字段。address 必填（中文地址），city 可选用以缩小搜索范围。")
                .inputType(Input.class)
                .build();
    }

    private static String str(Object o) {
        return o == null ? "" : (o instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : String.valueOf(o));
    }
}
