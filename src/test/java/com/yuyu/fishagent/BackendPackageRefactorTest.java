package com.yuyu.fishagent;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendPackageRefactorTest {

    private static final List<String> EXPECTED_CLASSES = List.of(
            "com.yuyu.fishagent.common.exception.GlobalExceptionHandler",
            "com.yuyu.fishagent.common.exception.SessionLockedException",
            "com.yuyu.fishagent.common.ratelimit.RateLimitResult",
            "com.yuyu.fishagent.common.ratelimit.RateLimitService",
            "com.yuyu.fishagent.common.config.WebMvcConfig",
            "com.yuyu.fishagent.common.config.SchedulingConfiguration",
            "com.yuyu.fishagent.common.config.RateLimitProperties",
            "com.yuyu.fishagent.common.dto.ChatMessageDTO",
            "com.yuyu.fishagent.auth.AuthController",
            "com.yuyu.fishagent.auth.AuthService",
            "com.yuyu.fishagent.auth.dto.LoginRequest",
            "com.yuyu.fishagent.auth.dto.LoginResponse",
            "com.yuyu.fishagent.auth.dto.RegisterRequest",
            "com.yuyu.fishagent.auth.entity.SysUser",
            "com.yuyu.fishagent.auth.mapper.SysUserMapper",
            "com.yuyu.fishagent.auth.enums.UserRole",
            "com.yuyu.fishagent.auth.config.AuthProperties",
            "com.yuyu.fishagent.llm.config.FishChatModelConfiguration",
            "com.yuyu.fishagent.llm.config.FishEmbeddingModelConfiguration",
            "com.yuyu.fishagent.llm.config.FishLlmChatProvider",
            "com.yuyu.fishagent.llm.config.FishLlmConfigurationConsistencyLogger",
            "com.yuyu.fishagent.llm.config.FishLlmEmbeddingProperties",
            "com.yuyu.fishagent.llm.config.FishLlmEnvironmentPostProcessor",
            "com.yuyu.fishagent.llm.config.FishLlmProperties",
            "com.yuyu.fishagent.memory.LongTermMemoryIngestionService",
            "com.yuyu.fishagent.memory.MemoryCompressionService",
            "com.yuyu.fishagent.memory.shortterm.RedisShortTermMemoryStore",
            "com.yuyu.fishagent.memory.shortterm.ShortTermMemorySnapshot",
            "com.yuyu.fishagent.memory.shortterm.ShortTermMemoryStore",
            "com.yuyu.fishagent.memory.longterm.ElasticsearchLongTermMemoryStore",
            "com.yuyu.fishagent.memory.longterm.LongTermMemoryFactSanitizer",
            "com.yuyu.fishagent.memory.longterm.LongTermMemoryPromptBuilder",
            "com.yuyu.fishagent.memory.longterm.LongTermMemoryResponseParser",
            "com.yuyu.fishagent.memory.longterm.LongTermMemoryStore",
            "com.yuyu.fishagent.memory.compress.MemoryPromptBuilder",
            "com.yuyu.fishagent.memory.compress.MemoryResponseParser",
            "com.yuyu.fishagent.memory.config.MemoryProperties",
            "com.yuyu.fishagent.agent.config.AgentProperties",
            "com.yuyu.fishagent.agent.config.ToolProperties",
            "com.yuyu.fishagent.rag.KnowledgeController",
            "com.yuyu.fishagent.rag.service.KnowledgeIngestionService",
            "com.yuyu.fishagent.rag.service.KnowledgeManageService",
            "com.yuyu.fishagent.rag.service.MultipartInitResult",
            "com.yuyu.fishagent.rag.service.OrphanTaskCompensationService",
            "com.yuyu.fishagent.rag.service.RustFsService",
            "com.yuyu.fishagent.rag.pipeline.expand.RagQueryExpand",
            "com.yuyu.fishagent.rag.pipeline.expand.RagQueryExpandConfiguration",
            "com.yuyu.fishagent.rag.pipeline.query.RagQueryRewrite",
            "com.yuyu.fishagent.rag.pipeline.query.RagQueryRewriteConfiguration",
            "com.yuyu.fishagent.rag.pipeline.recall.RagRecall",
            "com.yuyu.fishagent.rag.pipeline.recall.RagRecallConfiguration",
            "com.yuyu.fishagent.rag.pipeline.recall.PublicKnowledgeElasticsearchSearcher",
            "com.yuyu.fishagent.rag.pipeline.recall.UserKnowledgeElasticsearchSearcher",
            "com.yuyu.fishagent.rag.pipeline.recall.UserMemoryElasticsearchSearcher",
            "com.yuyu.fishagent.rag.document.PublicKnowledgeDocument",
            "com.yuyu.fishagent.rag.document.UserMemoryDocument",
            "com.yuyu.fishagent.rag.dto.DocumentMetadataPageResponse",
            "com.yuyu.fishagent.rag.dto.DocumentMetadataResponse",
            "com.yuyu.fishagent.rag.dto.DocumentTaskStatusResponse",
            "com.yuyu.fishagent.rag.dto.KnowledgeUploadResponse",
            "com.yuyu.fishagent.rag.dto.MultipartAbortRequest",
            "com.yuyu.fishagent.rag.dto.MultipartCompleteRequest",
            "com.yuyu.fishagent.rag.dto.MultipartInitRequest",
            "com.yuyu.fishagent.rag.dto.MultipartInitResponse",
            "com.yuyu.fishagent.rag.dto.MultipartPartInfo",
            "com.yuyu.fishagent.rag.dto.MultipartPartResponse",
            "com.yuyu.fishagent.rag.entity.DocumentMetadata",
            "com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper",
            "com.yuyu.fishagent.rag.config.KnowledgeProperties",
            "com.yuyu.fishagent.rag.config.RagProperties",
            "com.yuyu.fishagent.rag.config.RustFsProperties",
            "com.yuyu.fishagent.chat.ChatController",
            "com.yuyu.fishagent.chat.ChatService",
            "com.yuyu.fishagent.chat.ChatMetadataService",
            "com.yuyu.fishagent.chat.history.ChatMemoryStore",
            "com.yuyu.fishagent.chat.history.RustFsChatMemoryStore",
            "com.yuyu.fishagent.chat.history.UserScopedFileChatMemoryStore",
            "com.yuyu.fishagent.chat.dto.ChatRequest",
            "com.yuyu.fishagent.chat.dto.SessionInfo",
            "com.yuyu.fishagent.chat.entity.ChatMetadata",
            "com.yuyu.fishagent.chat.mapper.ChatMetadataMapper"
    );

    private static final List<String> LEGACY_CLASSES = List.of(
            "com.yuyu.fishagent.exception.GlobalExceptionHandler",
            "com.yuyu.fishagent.ratelimit.RateLimitService",
            "com.yuyu.fishagent.controller.ChatController",
            "com.yuyu.fishagent.service.ChatService",
            "com.yuyu.fishagent.dto.ChatRequest",
            "com.yuyu.fishagent.entity.ChatMetadata",
            "com.yuyu.fishagent.mapper.ChatMetadataMapper",
            "com.yuyu.fishagent.config.AgentProperties",
            "com.yuyu.fishagent.config.llm.FishLlmProperties",
            "com.yuyu.fishagent.agent.memory.longterm.PublicKnowledgeDocument",
            "com.yuyu.fishagent.agent.memory.rag.recall.RagRecall"
    );

    @TestFactory
    Stream<DynamicTest> expectedPackagesExposeMovedClasses() {
        return EXPECTED_CLASSES.stream()
                .map(className -> DynamicTest.dynamicTest(className,
                        () -> assertDoesNotThrow(() -> Class.forName(className))));
    }

    @TestFactory
    Stream<DynamicTest> legacyPackagesNoLongerExposeMovedClasses() {
        return LEGACY_CLASSES.stream()
                .map(className -> DynamicTest.dynamicTest(className,
                        () -> assertThrows(ClassNotFoundException.class, () -> Class.forName(className))));
    }
}
