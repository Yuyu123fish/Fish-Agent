package com.yuyu.fishagent.rag.pipeline.recall;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 需要本机 Elasticsearch 与有效 embedding 配置时去掉 {@link Disabled} 再跑；默认跳过 CI。
 */
class ElasticsearchLongTermMemorySearcherIT {

    @Test
    @Disabled("本地需运行 ES 与 DashScope Embedding 时再启用")
    void recallSmokePlaceholder() {
        // 可在此 @Autowired RagRecall.DocumentSearcher（Bean 名 longTermMemorySearcher）并写入若干文档后断言召回
    }
}
