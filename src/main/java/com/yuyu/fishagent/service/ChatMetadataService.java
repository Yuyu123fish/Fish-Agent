package com.yuyu.fishagent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.dto.SessionInfo;
import com.yuyu.fishagent.entity.ChatMetadata;
import com.yuyu.fishagent.mapper.ChatMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 会话元数据（MySQL）：列表展示、首聊落库、所有权校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMetadataService {

    private final ChatMetadataMapper chatMetadataMapper;

    /**
     * 当前登录用户的会话列表摘要（按更新时间倒序）。
     *
     * @return 侧边栏用 {@link SessionInfo}；消息条数暂填 0（避免列表 N 次读对象存储）
     */
    public List<SessionInfo> listSessionsForCurrentUser() {
        Long userId = requireUserId();
        List<ChatMetadata> rows = chatMetadataMapper.selectList(Wrappers.<ChatMetadata>lambdaQuery()
                .eq(ChatMetadata::getUserId, userId)
                .orderByDesc(ChatMetadata::getUpdatedAt));
        ZoneId z = ZoneId.systemDefault();
        return rows.stream().map(r -> {
            String title = r.getTitle() != null && !r.getTitle().isBlank() ? r.getTitle() : "(未命名)";
            long updated = r.getUpdatedAt() == null ? 0L : r.getUpdatedAt().atZone(z).toInstant().toEpochMilli();
            return new SessionInfo(r.getSessionId(), title, 0, updated);
        }).toList();
    }

    /**
     * 按会话 ID 查询元数据（不限用户），用于判断是否占用及归属校验。
     *
     * @param sessionId 会话 ID
     * @return 元数据行
     */
    public Optional<ChatMetadata> findBySessionId(String sessionId) {
        return Optional.ofNullable(chatMetadataMapper.selectOne(Wrappers.<ChatMetadata>lambdaQuery()
                .eq(ChatMetadata::getSessionId, sessionId)));
    }

    /**
     * 读历史前调用：尚无元数据视为「全新会话」放行；已有记录则必须是当前用户。
     *
     * @param sessionId 会话 ID
     */
    public void assertReadableSessionOrNew(String sessionId) {
        Optional<ChatMetadata> row = findBySessionId(sessionId);
        if (row.isEmpty()) {
            return;
        }
        Long userId = requireUserId();
        if (!row.get().getUserId().equals(userId)) {
            throw new IllegalArgumentException("session not found or access denied");
        }
    }

    /**
     * 断言当前用户拥有该会话（删除等必须已存在绑定）。
     *
     * @param sessionId 会话 ID
     */
    public void assertOwnedByCurrentUser(String sessionId) {
        Long userId = requireUserId();
        ChatMetadata row = chatMetadataMapper.selectOne(Wrappers.<ChatMetadata>lambdaQuery()
                .eq(ChatMetadata::getSessionId, sessionId)
                .eq(ChatMetadata::getUserId, userId));
        if (row == null) {
            throw new IllegalArgumentException("session not found or access denied");
        }
    }

    /**
     * 若尚无元数据行则插入（首条用户消息时调用）；已存在则仅刷新标题（当传入非空时）。
     *
     * @param sessionId    会话 ID
     * @param titlePreview 可选标题预览（如首条用户消息截断）
     */
    @Transactional(rollbackFor = Exception.class)
    public void ensureSessionForCurrentUser(String sessionId, String titlePreview) {
        Long userId = requireUserId();
        ChatMetadata existing = chatMetadataMapper.selectOne(Wrappers.<ChatMetadata>lambdaQuery()
                .eq(ChatMetadata::getSessionId, sessionId));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ChatMetadata m = new ChatMetadata();
            m.setUserId(userId);
            m.setSessionId(sessionId);
            m.setTitle(trimTitle(titlePreview));
            m.setCreatedAt(now);
            m.setUpdatedAt(now);
            chatMetadataMapper.insert(m);
            log.debug("[ChatMetadata] 新建会话元数据 sid={}, userId={}", sessionId, userId);
            return;
        }
        if (!existing.getUserId().equals(userId)) {
            throw new IllegalArgumentException("session already bound to another user");
        }
        if (titlePreview != null && !titlePreview.isBlank()
                && (existing.getTitle() == null || existing.getTitle().isBlank())) {
            existing.setTitle(trimTitle(titlePreview));
            existing.setUpdatedAt(now);
            chatMetadataMapper.updateById(existing);
        }
    }

    /**
     * 对话落盘后调用，更新 {@code updated_at}。
     *
     * @param sessionId 会话 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void touchUpdatedAt(String sessionId) {
        Long userId = requireUserId();
        ChatMetadata row = chatMetadataMapper.selectOne(Wrappers.<ChatMetadata>lambdaQuery()
                .eq(ChatMetadata::getSessionId, sessionId)
                .eq(ChatMetadata::getUserId, userId));
        if (row != null) {
            row.setUpdatedAt(LocalDateTime.now());
            chatMetadataMapper.updateById(row);
        }
    }

    /**
     * 删除元数据行（历史正文由存储层另行删除）。
     *
     * @param sessionId 会话 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMetadataForCurrentUser(String sessionId) {
        Long userId = requireUserId();
        chatMetadataMapper.delete(Wrappers.<ChatMetadata>lambdaQuery()
                .eq(ChatMetadata::getSessionId, sessionId)
                .eq(ChatMetadata::getUserId, userId));
    }

    /**
     * 重命名当前用户拥有的会话标题。
     *
     * @param sessionId 会话 ID
     * @param newTitle  新标题（经 {@link #trimTitle} 截断与清洗）
     */
    @Transactional(rollbackFor = Exception.class)
    public void renameTitle(String sessionId, String newTitle) {
        Long userId = requireUserId();
        ChatMetadata row = chatMetadataMapper.selectOne(Wrappers.<ChatMetadata>lambdaQuery()
                .eq(ChatMetadata::getSessionId, sessionId)
                .eq(ChatMetadata::getUserId, userId));
        if (row == null) {
            throw new IllegalArgumentException("session not found or access denied");
        }
        row.setTitle(trimTitle(newTitle));
        row.setUpdatedAt(LocalDateTime.now());
        chatMetadataMapper.updateById(row);
    }

    private static Long requireUserId() {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("no authenticated user in context");
        }
        return uid;
    }

    private static String trimTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().replace("\r\n", " ").replace('\n', ' ');
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
