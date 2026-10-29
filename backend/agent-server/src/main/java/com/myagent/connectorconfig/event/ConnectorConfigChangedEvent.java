package com.myagent.connectorconfig.event;

import java.util.List;

/**
 * 连接器配置/绑定变更（新建、修改、启停、删除、绑定 Agent）。
 *
 * <p>为什么需要它：Agent 的工具集是**构建时**按"绑定了哪些类型的连接器"决定的，
 * 构建结果又被 {@code AgentFactory} 缓存。用户把连接器**从停用改成启用**之后，
 * 如果不让缓存失效，下一轮对话用的还是旧实例 —— 表现就是「我明明开了，它还说没有这个功能」
 * （用户实测反馈）。
 *
 * <p>事件放在 {@code connectorconfig} 包、监听者在 {@code agui} 包（门禁 G2）：
 * 配置包不引用 AgentScope；由 agui 侧调用 {@code AgentCacheInvalidator}。
 *
 * @param connectorId 变更的连接器
 * @param agentIds 受影响的 Agent（变更前/变更后绑定的都算；空表示"不确定，全部失效"）
 */
public record ConnectorConfigChangedEvent(long connectorId, List<Long> agentIds) {}
