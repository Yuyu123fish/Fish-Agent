package com.yuyu.fishagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分片上传初始化请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultipartInitRequest {

    private String fileName;
    private long fileSize;
    private String contentType;
}
