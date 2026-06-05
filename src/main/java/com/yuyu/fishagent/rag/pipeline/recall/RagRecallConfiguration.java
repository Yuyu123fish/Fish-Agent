package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.rag.pipeline.expand.RagQueryExpand;
import com.yuyu.fishagent.rag.pipeline.query.RagQueryRewrite;
import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.rerank.DashScopeRagReranker;
import com.yuyu.fishagent.rag.pipeline.rerank.RagReranker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 召回与编排装配：虚拟线程池、三路 {@link RagRecall.DocumentSearcher}（用户记忆 / 用户知识库 / 公共知识）、
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
    public RagRecall.Augmentation longTermRagContextService(
            RagProperties ragProperties,
            RagQueryRewrite.QueryRewriter queryRewriter,
            RagQueryExpand.SubQueryExpander subQueryExpander,
            UserMemoryElasticsearchSearcher userMemoryElasticsearchSearcher,
            UserKnowledgeElasticsearchSearcher userKnowledgeElasticsearchSearcher,
            PublicKnowledgeElasticsearchSearcher publicKnowledgeElasticsearchSearcher,
            ObjectProvider<ElasticsearchOperations> operationsProvider,
            @Qualifier("ragRecallExecutor") ExecutorService ragRecallExecutor,
            RagReranker ragReranker) {
        return new RagRecall.DefaultAugmentation(
                ragProperties,
                queryRewriter,
                subQueryExpander,
                userMemoryElasticsearchSearcher,
                userKnowledgeElasticsearchSearcher,
                publicKnowledgeElasticsearchSearcher,
                operationsProvider,
                ragRecallExecutor,
                ragReranker);
    }
}
