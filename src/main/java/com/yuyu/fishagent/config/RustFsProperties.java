package com.yuyu.fishagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RustFS（MinIO S3 兼容）连接参数：{@code fish.rustfs.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.rustfs")
public class RustFsProperties {

    /**
     * 为 {@code true}（默认）时对话 JSON 写入 RustFS/MinIO；
     * 显式设为 {@code false} 时回退到本地 {@code history-dir/{userId}/}（仅开发兜底）。
     */
    private boolean enabled = true;

    private String endpoint = "http://localhost:9000";

    private String accessKey = "";

    private String secretKey = "";

    private String bucketChat = "fish-chat";

    private String bucketDocs = "fish-docs";
}
