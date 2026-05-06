package com.yuyu.fishagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultipartCompleteRequest {

    private String taskId;
    private String uploadId;
    private String minioPath;
    private List<MultipartPartInfo> parts;
}
