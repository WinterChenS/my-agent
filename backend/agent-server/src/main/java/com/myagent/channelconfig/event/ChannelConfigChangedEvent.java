package com.myagent.channelconfig.event;

/**
 * 渠道配置变更事件：新增 / 修改 / 启停 / 删除。
 *
 * <p>用事件而不是直接调用，是为了让 {@code channelconfig} 包**不认识** AgentScope
 * （门禁 G2：AgentScope API 只允许出现在 agui/ 等白名单包）：真正加载/重启渠道的
 * {@code com.myagent.agui.channel.ChannelRuntime} 监听本事件。
 *
 * @param appId 渠道应用 id
 * @param deleted 是否是删除（删除时只需停掉并清理运行时）
 */
public record ChannelConfigChangedEvent(Long appId, boolean deleted) {}
