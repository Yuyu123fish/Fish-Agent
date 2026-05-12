package com.yuyu.fishagent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档解析任务状态（供前端轮询）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTaskStatusResponse {

    private String status;

    private String errorMsg;
}
