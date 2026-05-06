package com.yuyu.fishagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultipartAbortRequest {

    private String taskId;
    private String uploadId;
    private String minioPath;
}
