package com.yuyu.fishagent.memory.dto;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆压缩请求。
 * <p>{@code chatHistory} 按时间正序传入，服务会交给模型生成短期摘要与长期事实。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryCompressionRequest {

    private String sessionId;

    private List<ChatMessageDTO> chatHistory = new ArrayList<>();
}
