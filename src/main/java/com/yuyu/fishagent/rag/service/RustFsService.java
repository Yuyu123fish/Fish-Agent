package com.yuyu.fishagent.rag.service;

import com.yuyu.fishagent.rag.config.RustFsProperties;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * RustFS（MinIO）封装：桶就绪检查、对象读写删、知识库分片 staging + compose 合并。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.rustfs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RustFsService {

    private final RustFsProperties props;

    private volatile MinioClient client;

    /**
     * 启动时尝试连接；失败时不阻断应用启动，首次读写前会再次 {@link #ensureClient()}。
     */
    @PostConstruct
    public void init() {
        try {
            ensureClient();
            log.info("[RustFs] 已连接 endpoint={}, chatBucket={}", props.getEndpoint(), props.getBucketChat());
        } catch (Exception e) {
            log.warn("[RustFs] 启动时未能连接对象存储（首次读写时将重试）: {}", e.getMessage());
        }
    }

    /**
     * 懒加载并建桶：避免 MinIO 晚于应用启动时进程直接崩溃。
     */
    private synchronized void ensureClient() throws Exception {
        if (client != null) {
            return;
        }
        MinioClient c = MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
        ensureBucket(c, props.getBucketChat());
        ensureBucket(c, props.getBucketDocs());
        this.client = c;
    }

    /**
     * 写入会话 JSON（覆盖）。
     */
    public void putChatJson(String objectKey, byte[] jsonBytes) throws Exception {
        ensureClient();
        client.putObject(PutObjectArgs.builder()
                .bucket(props.getBucketChat())
                .object(objectKey)
                .stream(new ByteArrayInputStream(jsonBytes), jsonBytes.length, -1)
                .contentType("application/json; charset=utf-8")
                .build());
    }

    /**
     * 读取会话 JSON；不存在时返回 {@code null}。
     */
    public byte[] getChatJsonOrNull(String objectKey) throws Exception {
        ensureClient();
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(props.getBucketChat())
                .object(objectKey)
                .build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equalsIgnoreCase(e.errorResponse().code())) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 删除会话对象。
     */
    public void deleteChatJson(String objectKey) throws Exception {
        ensureClient();
        client.removeObject(RemoveObjectArgs.builder()
                .bucket(props.getBucketChat())
                .object(objectKey)
                .build());
    }

    /**
     * 写入 fish-docs 最终对象（流式）。
     *
     * @return ETag（供分片协议回传；直传可忽略）
     */
    public String putDocObject(String objectKey, InputStream stream, long size, String contentType) throws Exception {
        ensureClient();
        String ct = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        ObjectWriteResponse resp = client.putObject(PutObjectArgs.builder()
                .bucket(props.getBucketDocs())
                .object(objectKey)
                .stream(stream, size, -1)
                .contentType(ct)
                .build());
        return resp.etag();
    }

    /**
     * 读取 fish-docs 桶对象；不存在时返回 {@code null}。
     */
    public byte[] getDocObjectOrNull(String objectKey) throws Exception {
        ensureClient();
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(props.getBucketDocs())
                .object(objectKey)
                .build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equalsIgnoreCase(e.errorResponse().code())) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 删除 fish-docs 桶中的对象。
     */
    public void deleteDocObject(String objectKey) throws Exception {
        ensureClient();
        client.removeObject(RemoveObjectArgs.builder()
                .bucket(props.getBucketDocs())
                .object(objectKey)
                .build());
    }

    /**
     * 将多个已上传的源对象按顺序合并为最终对象（用于分片 staging）。
     */
    public void composeDocObject(List<String> sourceObjectKeysInOrder, String destObjectKey) throws Exception {
        ensureClient();
        if (sourceObjectKeysInOrder == null || sourceObjectKeysInOrder.isEmpty()) {
            throw new IllegalArgumentException("compose 源对象列表不能为空");
        }
        List<ComposeSource> sources = new ArrayList<>(sourceObjectKeysInOrder.size());
        for (String key : sourceObjectKeysInOrder) {
            sources.add(ComposeSource.builder().bucket(props.getBucketDocs()).object(key).build());
        }
        client.composeObject(ComposeObjectArgs.builder()
                .bucket(props.getBucketDocs())
                .object(destObjectKey)
                .sources(sources)
                .build());
    }

    /**
     * 删除指定前缀下 fish-docs 中的所有对象（用于清理 staging/{taskId}/）。
     */
    public void deleteObjectsByPrefix(String prefix) throws Exception {
        ensureClient();
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder()
                .bucket(props.getBucketDocs())
                .prefix(prefix)
                .recursive(true)
                .build());
        for (Result<Item> result : results) {
            Item item;
            try {
                item = result.get();
            } catch (Exception e) {
                log.warn("[RustFs] listObjects 迭代失败 prefix={}: {}", prefix, e.getMessage());
                continue;
            }
            if (item.isDir()) {
                continue;
            }
            String name = item.objectName();
            if (name == null || name.isBlank()) {
                continue;
            }
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.getBucketDocs())
                    .object(name)
                    .build());
        }
    }

    private static void ensureBucket(MinioClient c, String name) throws Exception {
        boolean exists = c.bucketExists(BucketExistsArgs.builder().bucket(name).build());
        if (!exists) {
            c.makeBucket(MakeBucketArgs.builder().bucket(name).build());
            log.info("[RustFs] 已创建 bucket {}", name);
        }
    }
}
