package com.myagent.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.chat.dto.MessageResponse;
import com.myagent.chat.dto.SessionRequest;
import com.myagent.chat.dto.SessionResponse;
import com.myagent.chat.entity.ChatMessage;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatMessageMapper;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.common.BizException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 会话与消息：按用户隔离，AG-UI 按 threadId 定位会话。 */
@Service
public class ChatSessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    public ChatSessionService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * 分页取会话（侧栏滚动加载）。
     *
     * <p>按 id 倒序 = 最近的在前；每页上限 100，避免一次把历史全拖出来。
     */
    public com.myagent.common.PageResult<SessionResponse> page(Long userId, long current, long size) {
        long page = Math.max(1, current);
        long pageSize = Math.min(Math.max(1, size), 100);
        var result = sessionMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, pageSize),
                Wrappers.<ChatSession>lambdaQuery()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getId));
        return com.myagent.common.PageResult.of(
                result.getRecords().stream().map(ChatSessionService::toResponse).toList(),
                result.getTotal(), page, pageSize);
    }

    public List<SessionResponse> list(Long userId) {
        return sessionMapper
                .selectList(Wrappers.<ChatSession>lambdaQuery()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getId))
                .stream()
                .map(ChatSessionService::toResponse)
                .toList();
    }

    @Transactional
    public SessionResponse create(Long userId, SessionRequest request) {
        String threadId = StringUtils.hasText(request.threadId())
                ? request.threadId()
                : java.util.UUID.randomUUID().toString();
        ChatSession session = createForThread(userId, threadId, request.agentId());
        if (StringUtils.hasText(request.title())) {
            session.setTitle(request.title());
            sessionMapper.updateById(session);
        }
        return toResponse(session);
    }

    @Transactional
    public void delete(Long userId, Long sessionId) {
        ChatSession session = requireOwned(userId, sessionId);
        messageMapper.delete(Wrappers.<ChatMessage>lambdaQuery().eq(ChatMessage::getSessionId, session.getId()));
        sessionMapper.deleteById(session.getId());
    }

    public List<MessageResponse> messages(Long userId, Long sessionId) {
        ChatSession session = requireOwned(userId, sessionId);
        return messageMapper
                .selectList(Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getSessionId, session.getId())
                        .orderByAsc(ChatMessage::getSeq)
                        .orderByAsc(ChatMessage::getId))
                .stream()
                .map(ChatSessionService::toResponse)
                .toList();
    }

    /** AG-UI：按 (userId, threadId) 查找已有会话。 */
    public Optional<ChatSession> findByThread(Long userId, String threadId) {
        if (!StringUtils.hasText(threadId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionMapper.selectOne(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getThreadId, threadId)));
    }

    @Transactional
    public ChatSession createForThread(Long userId, String threadId, Long agentId) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setAgentId(agentId);
        session.setThreadId(threadId);
        session.setTitle("会话 " + threadId.substring(0, Math.min(8, threadId.length())));
        session.setLastActiveAt(OffsetDateTime.now());
        sessionMapper.insert(session);
        return session;
    }

    /** 追加一条消息，seq 自增。 */
    @Transactional
    public void appendMessage(Long sessionId, String role, String content, Map<String, Object> aguiMeta) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setAguiMeta(aguiMeta);
        message.setSeq(nextSeq(sessionId));
        messageMapper.insert(message);
    }

    @Transactional
    /** 该会话已有的用户消息条数（Phase 17：判断是否为「第一条」，用于自动生成标题）。 */
    public long countUserMessages(Long sessionId) {
        return messageMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "USER"));
    }

    /**
     * 找出「标题仍可自动覆盖」且有历史消息的会话，并取出各自的首条用户消息（Phase 17 回填用）。
     */
    public java.util.List<com.myagent.agui.SessionTitleService.FirstMessage> backfillCandidates(int limit) {
        java.util.List<ChatSession> sessions = sessionMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ChatSession>lambdaQuery()
                        .and(w -> w.isNull(ChatSession::getTitleAuto).or().eq(ChatSession::getTitleAuto, true))
                        .orderByAsc(ChatSession::getId)
                        .last("limit " + Math.max(1, limit)));
        java.util.List<com.myagent.agui.SessionTitleService.FirstMessage> result = new java.util.ArrayList<>();
        for (ChatSession session : sessions) {
            ChatMessage first = messageMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<ChatMessage>lambdaQuery()
                            .eq(ChatMessage::getSessionId, session.getId())
                            .eq(ChatMessage::getRole, "USER")
                            .orderByAsc(ChatMessage::getSeq, ChatMessage::getId)
                            .last("limit 1"));
            if (first != null && first.getContent() != null && !first.getContent().isBlank()) {
                result.add(new com.myagent.agui.SessionTitleService.FirstMessage(
                        session.getId(), session.getAgentId(), first.getContent()));
            }
        }
        return result;
    }

    public void touch(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setLastActiveAt(OffsetDateTime.now());
            sessionMapper.updateById(session);
        }
    }

    private int nextSeq(Long sessionId) {
        Long maxSeq = messageMapper
                .selectList(Wrappers.<ChatMessage>lambdaQuery()
                        .select(ChatMessage::getSeq)
                        .eq(ChatMessage::getSessionId, sessionId))
                .stream()
                .map(ChatMessage::getSeq)
                .filter(java.util.Objects::nonNull)
                .map(Integer::longValue)
                .max(Long::compareTo)
                .orElse(0L);
        return (int) (maxSeq + 1);
    }

    /** 取会话并校验属主（Phase 12 停止执行入口也用）。 */
    public ChatSession requireOwned(Long userId, Long sessionId) {
        ChatSession session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BizException.notFound("会话不存在: " + sessionId);
        }
        if (!session.getUserId().equals(userId)) {
            throw BizException.forbidden("无权访问他人会话");
        }
        return session;
    }

    private static SessionResponse toResponse(ChatSession session) {
        return new SessionResponse(
                session.getId(),
                session.getAgentId(),
                session.getTitle(),
                session.getThreadId(),
                session.getOrigin() == null ? "WEB" : session.getOrigin(),
                session.getLastActiveAt(),
                session.getCreatedAt());
    }

    private static MessageResponse toResponse(ChatMessage message) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getAguiMeta(),
                message.getSeq(),
                message.getCreatedAt());
    }
}
