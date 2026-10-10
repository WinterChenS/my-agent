package com.myagent.approval.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.approval.dto.ApprovalDecisionRequest;
import com.myagent.approval.dto.ApprovalDecisionResponse;
import com.myagent.approval.dto.ApprovalResponse;
import com.myagent.approval.entity.ApprovalRecord;
import com.myagent.approval.mapper.ApprovalRecordMapper;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.common.BizException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HITL 审批：记录被权限系统拦截的工具调用，并受理用户的批准/拒绝。
 *
 * <p>“恢复执行”遵循官方 AG-UI 模式：本服务只落库并返回 resume 指令，
 * 由前端用该指令发起带 {@code resume} 的新一轮 {@code /agui/run}，
 * 由 AgentScope 的 HITL resume 能力继续执行（不自研等待逻辑，门禁 G8）。
 */
@Service
public class ApprovalService {

    private static final String APPROVED = "APPROVED";
    private static final String DENIED = "DENIED";
    private static final String PENDING = "PENDING";
    private static final String RESUME_RESOLVED = "resolved";
    private static final String RESUME_CANCELLED = "cancelled";

    private final ApprovalRecordMapper approvalRecordMapper;
    private final ChatSessionMapper chatSessionMapper;

    public ApprovalService(
            ApprovalRecordMapper approvalRecordMapper, ChatSessionMapper chatSessionMapper) {
        this.approvalRecordMapper = approvalRecordMapper;
        this.chatSessionMapper = chatSessionMapper;
    }

    /** 权限系统拦截工具调用时落库（PENDING）。同一次 interrupt 只记一次。 */
    @Transactional
    public void recordPending(Long sessionId, String interruptId, String toolName, Map<String, Object> args) {
        if (interruptId == null || interruptId.isBlank()) {
            return;
        }
        Long existing = approvalRecordMapper.selectCount(
                Wrappers.<ApprovalRecord>lambdaQuery().eq(ApprovalRecord::getRequestId, interruptId));
        if (existing > 0) {
            return;
        }
        ApprovalRecord record = new ApprovalRecord();
        record.setSessionId(sessionId);
        record.setRequestId(interruptId);
        record.setToolName(toolName);
        record.setArgs(args);
        record.setStatus(PENDING);
        approvalRecordMapper.insert(record);
    }

    public List<ApprovalResponse> listBySession(Long userId, Long sessionId) {
        requireOwnedSession(userId, sessionId);
        return approvalRecordMapper
                .selectList(Wrappers.<ApprovalRecord>lambdaQuery()
                        .eq(ApprovalRecord::getSessionId, sessionId)
                        .orderByAsc(ApprovalRecord::getId))
                .stream()
                .map(ApprovalService::toResponse)
                .toList();
    }

    /** 受理审批决定；只有会话属主可决定。 */
    @Transactional
    public ApprovalDecisionResponse decide(Long userId, String interruptId, ApprovalDecisionRequest request) {
        ApprovalRecord record = approvalRecordMapper.selectOne(
                Wrappers.<ApprovalRecord>lambdaQuery().eq(ApprovalRecord::getRequestId, interruptId));
        if (record == null) {
            throw BizException.notFound("审批记录不存在: " + interruptId);
        }
        ChatSession session = chatSessionMapper.selectById(record.getSessionId());
        if (session == null) {
            throw BizException.notFound("会话不存在: " + record.getSessionId());
        }
        if (!session.getUserId().equals(userId)) {
            throw BizException.forbidden("无权处理他人会话的审批");
        }
        if (!PENDING.equals(record.getStatus())) {
            throw BizException.conflict("该审批已处理: " + record.getStatus());
        }

        boolean approved = APPROVED.equalsIgnoreCase(request.decision().trim());
        if (!approved && !DENIED.equalsIgnoreCase(request.decision().trim())) {
            throw BizException.badRequest("decision 只能是 APPROVED 或 DENIED");
        }

        record.setStatus(approved ? APPROVED : DENIED);
        record.setDecidedBy(userId);
        record.setDecidedAt(OffsetDateTime.now());
        approvalRecordMapper.updateById(record);

        Map<String, Object> payload = request.reason() == null ? Map.of() : Map.of("reason", request.reason());
        return new ApprovalDecisionResponse(
                interruptId,
                record.getStatus(),
                approved ? RESUME_RESOLVED : RESUME_CANCELLED,
                payload);
    }

    private ChatSession requireOwnedSession(Long userId, Long sessionId) {
        ChatSession session = sessionId == null ? null : chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw BizException.notFound("会话不存在: " + sessionId);
        }
        if (!session.getUserId().equals(userId)) {
            throw BizException.forbidden("无权访问他人会话");
        }
        return session;
    }

    private static ApprovalResponse toResponse(ApprovalRecord record) {
        return new ApprovalResponse(
                record.getId(),
                record.getSessionId(),
                record.getRequestId(),
                record.getToolName(),
                record.getArgs(),
                record.getStatus(),
                record.getDecidedBy(),
                record.getDecidedAt(),
                record.getCreatedAt());
    }
}
