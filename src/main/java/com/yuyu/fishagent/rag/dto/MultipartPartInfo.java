package com.yuyu.fishagent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 完成 multipart 时的单个分片元数据（与 MinIO Part 对应）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultipartPartInfo {

    private int partNumber;
    private String etag;
}
