package com.yuyu.fishagent.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 登录鉴权配置：{@code fish.auth.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.auth")
public class AuthProperties {

    /** Redis 中会话 TTL（秒）。 */
    private long sessionTtlSeconds = 86400;

    /**
     * 逗号分隔的白名单路径前缀；匹配到的请求不校验登录。
     * <p>例如：{@code /api/auth/login,/api/auth/register}</p>
     */
    private String whiteList = "/api/auth/login,/api/auth/register,/api/auth/logout";

    /**
     * 将 {@link #whiteList} 解析为去空白后的路径前缀列表。
     *
     * @return 白名单前缀列表
     */
    public List<String> resolvedWhiteListPrefixes() {
        return Arrays.stream(whiteList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
