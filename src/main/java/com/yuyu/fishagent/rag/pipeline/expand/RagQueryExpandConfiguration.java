package com.yuyu.fishagent.rag.pipeline.expand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多查询扩展装配：按 {@code fish.rag.expand} 选择 LLM 分解、词级拆解或单条原句。
 */
@Slf4j
@Configuration
public class RagQueryExpandConfiguration {

    @Bean
    public RagQueryExpand.SubQueryExpander subQueryExpander(
            RagProperties ragProperties,
            @Qualifier("memoryChatModel") ObjectProvider<ChatModel> memoryChatModelProvider,
            ObjectMapper objectMapper) {

        RagProperties.Expand expand = ragProperties.getExpand();
        if (!expand.isEnabled()) {
            return new RagQueryExpand.IdentityExpander();
        }

        return switch (expand.getStrategy()) {
            case TOKEN -> new RagQueryExpand.BreakIteratorExpander(ragProperties);
            case IDENTITY -> new RagQueryExpand.IdentityExpander();
            case LLM -> {
                ChatModel model = memoryChatModelProvider.getIfAvailable();
                if (model != null) {
                    yield new RagQueryExpand.LlmQueryDecomposer(model, ragProperties, objectMapper);
                }
                log.warn("[RagQueryExpandConfiguration] expand.strategy=LLM 但 memoryChatModel 不可用，回退 IdentityExpander");
                yield new RagQueryExpand.IdentityExpander();
            }
        };
    }
}
