package com.yuyu.fishagent.rag.pipeline.rerank;

import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;

import java.util.List;

/**
 * RAG 候选精排接口。
 * <p>编排层只依赖此接口；具体供应商、HTTP 协议和失败降级都由实现类封装，便于后续替换为本地模型或其他服务。</p>
 */
public interface RagReranker {

    /**
     * 对融合后的候选池做精排。
     *
     * @param query      用户查询文本
     * @param candidates 融合后的候选池
     * @param topN       精排后最多保留条数
     * @return 精排结果；实现类应负责必要的降级
     */
    List<RagRecall.RecallHit> rerank(String query, List<RagRecall.RecallHit> candidates, int topN);
}
