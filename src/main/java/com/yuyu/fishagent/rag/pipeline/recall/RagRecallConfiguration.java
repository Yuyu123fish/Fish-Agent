package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.rag.pipeline.expand.RagQueryExpand;
import com.yuyu.fishagent.rag.pipeline.expand.RagHydeService;
import com.yuyu.fishagent.rag.pipeline.query.RagQueryRewrite;
import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.rerank.DashScopeRagReranker;
import com.yuyu.fishagent.rag.pipeline.rerank.RagReranker;
import com.yuyu.fishagent.rag.tracing.RagQualityLogger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 召回与编排装配：虚拟线程池、四路 {@link RagRecall.DocumentSearcher}（用户记忆 / 用户知识库 / 知识卡片 / 公共知识）、
 * 对 Chat 暴露的 {@link RagRecall.Augmentation}。
 */
@Configuration
public class RagRecallConfiguration {

    @Bean(name = "ragRecallExecutor", destroyMethod = "shutdown")
    public ExecutorService ragRecallExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public RagReranker ragReranker(RagProperties ragProperties) {
        return new DashScopeRagReranker(ragProperties);
    }

    @Bean
    public RagHydeService ragHydeService(
            @Qualifier("memoryChatModel") ObjectProvider<ChatModel> memoryChatModelProvider,
            RagProperties ragProperties) {
        return new RagHydeService(memoryChatModelProvider, ragProperties);
    }

    @Bean
    public RagRecall.Augmentation longTermRagContextService(
            RagProperties ragProperties,
            RagQueryRewrite.QueryRewriter queryRewriter,
            RagQueryExpand.SubQueryExpander subQueryExpander,
            UserMemoryElasticsearchSearcher userMemoryElasticsearchSearcher,
            UserKnowledgeElasticsearchSearcher userKnowledgeElasticsearchSearcher,
            UserKnowledgeCardSearcher userKnowledgeCardSearcher,
            PublicKnowledgeElasticsearchSearcher publicKnowledgeElasticsearchSearcher,
            ObjectProvider<ElasticsearchOperations> operationsProvider,
            @Qualifier("ragRecallExecutor") ExecutorService ragRecallExecutor,
            RagReranker ragReranker,
            RagHydeService ragHydeService,
            RagQualityLogger ragQualityLogger) {
        return new RagRecall.DefaultAugmentation(
                ragProperties,
                queryRewriter,
                subQueryExpander,
                userMemoryElasticsearchSearcher,
                userKnowledgeElasticsearchSearcher,
                userKnowledgeCardSearcher,
                publicKnowledgeElasticsearchSearcher,
                operationsProvider,
                ragRecallExecutor,
                ragReranker,
                ragHydeService,
                ragQualityLogger);
    }
}
