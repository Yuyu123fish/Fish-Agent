package com.yuyu.fishagent.rag.pipeline.expand;

import com.yuyu.fishagent.rag.config.RagProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多查询扩展装配（第二类）：仅注册 {@link RagQueryExpand.SubQueryExpander}。
 */
@Configuration
public class RagQueryExpandConfiguration {

    @Bean
    public RagQueryExpand.SubQueryExpander subQueryExpander(RagProperties ragProperties) {
        return new RagQueryExpand.BreakIteratorExpander(ragProperties);
    }
}
