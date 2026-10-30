package com.myagent.agui.connector.dingtalk;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 把"平台用户"映射成"钉钉 userId（staffId）"，作为文档接口的 {@code operatorId}。
 *
 * <p>怎么来的：用户跟钉钉机器人说过话 → {@code channel_peer.peer_key} 就是他的 staffId；
 * 而 {@code channel_peer.chat_session_id → chat_session.user_id} 把这条记录挂到了平台用户上。
 * 所以**只要用户曾经在钉钉里跟机器人聊过**，这里就能自动对上，用户什么都不用填。
 *
 * <p>对不上时返回空，由工具提示用户手填（连接器的「操作人」字段）。
 */
@Component
public class DingTalkOperatorResolver {

    private final JdbcTemplate jdbc;

    public DingTalkOperatorResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 平台用户 → 钉钉 userId；找不到返回 null。 */
    public String resolve(long userId) {
        try {
            List<String> keys = jdbc.queryForList(
                    "select cp.peer_key from channel_peer cp"
                            + " join chat_session cs on cs.id = cp.chat_session_id"
                            + " join channel_app ca on ca.id = cp.channel_app_id"
                            + " where ca.channel_type = 'DINGTALK' and cp.peer_kind = 'DIRECT' and cs.user_id = ?"
                            + " order by cp.last_active_at desc nulls last, cp.id desc limit 1",
                    String.class, userId);
            return keys.isEmpty() ? null : keys.get(0);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
