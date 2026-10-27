package com.myagent.agui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 给「模型调用失败」的错误文案补一句"已自动重试过"的说明。
 *
 * <p>为什么需要它：重试是静默的，用户只看到一模一样的报错，就会认为"根本没重试"（真实反馈）。
 * 日志里有 {@code Retrying model request (attempt 1/3)}，但用户在聊天界面看不到。
 *
 * <p>只在**可重试类型**的错误上追加，避免误导（比如参数错 400 本来就不该重试）。
 */
@Component
public class ModelRetryHint {

    private final int maxRetries;

    public ModelRetryHint(@Value("${myagent.model.retry.max-retries:3}") int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    /**
     * 给错误文案追加重试说明；不需要追加时原样返回。
     *
     * @param message 原始错误信息
     * @return 追加后的信息（可能与入参相同）
     */
    public String decorate(String message) {
        if (message == null || message.isEmpty() || maxRetries <= 0) {
            return message;
        }
        // 重试用尽时，Reactor 只留下 "Retries exhausted: 3/3"，根因被吃掉，用户完全看不懂
        if (message.contains("Retries exhausted") || message.contains("RetryExhaustedException")) {
            return "模型调用失败：已按配置自动重试 "
                    + maxRetries
                    + " 次仍未成功（上游在流式返回中断开了连接）。稍后重发通常就能恢复；"
                    + "如果持续失败，请换一个模型或让管理员检查模型服务。";
        }
        if (!ModelFactory.isTransientStreamFailure(new RuntimeException(message))) {
            return message;
        }
        return message
                + "（已按配置自动重试 "
                + maxRetries
                + " 次仍失败：模型服务在流式返回中断开了连接，一般稍后重发即可；持续失败请换一个模型或联系管理员）";
    }
}
