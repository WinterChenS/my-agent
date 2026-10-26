package com.myagent.channelconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.channelconfig.mapper.ChannelAppMapper;
import com.myagent.channelconfig.mapper.ChannelPeerMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * IM 推送目标的解析：把「用户/模型说的渠道名 + 接收人名」解析成确定的 {@code (channelAppId, peerKey)}。
 *
 * <p>为什么需要它：定时任务只能存**明确的**目标 id。之前工具没有这两个参数，`target=IM` 时只能回退到
 * "当前会话的绑定"，一旦会话不是从 IM 来的、或有多个机器人/多个接收人，就会**选错人**（实测踩过）。
 *
 * <p>约束策略（宁可拒绝，也不猜）：
 * <ul>
 *   <li>渠道支持：数字 id、精确名称（忽略大小写）、名称包含；命中多个 → 返回候选让调用方问用户；</li>
 *   <li>接收人支持：peerKey、精确名称、名称包含；命中多个 → 返回候选；</li>
 *   <li>都为空时才用"会话自带的绑定"作为兜底；再不行就报错并列出可选清单。</li>
 * </ul>
 */
@Component
public class ChannelTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(ChannelTargetResolver.class);

    private final ChannelAppMapper appMapper;
    private final ChannelPeerMapper peerMapper;

    public ChannelTargetResolver(ChannelAppMapper appMapper, ChannelPeerMapper peerMapper) {
        this.appMapper = appMapper;
        this.peerMapper = peerMapper;
    }

    /** 一个可用渠道。 */
    public record TargetChannel(Long id, String name, String type, boolean enabled, int peerCount) {

        public String display() {
            return "#" + id + " " + name + "（" + type + (enabled ? "" : "，未启用") + "，" + peerCount + " 个接收人）";
        }
    }

    /** 一个已绑定的接收人。 */
    public record TargetPeer(
            Long channelAppId, String channelName, String channelType,
            String peerKey, String peerName, String peerKind) {

        public String display() {
            return (peerName == null || peerName.isBlank() ? peerKey : peerName)
                    + "（" + peerKey + "，" + ("GROUP".equalsIgnoreCase(peerKind) ? "群" : "单聊") + "）";
        }
    }

    /** 解析结果：要么确定，要么需要用户选。 */
    public record Resolution(
            Long channelAppId,
            String channelName,
            String peerKey,
            String peerName,
            String problem,
            List<String> options) {

        public boolean ok() {
            return problem == null && channelAppId != null && peerKey != null && !peerKey.isBlank();
        }

        public String describe() {
            String who = peerName == null || peerName.isBlank() ? peerKey : peerName + "(" + peerKey + ")";
            return channelName + " → " + who;
        }

        static Resolution resolved(Long channelId, String channelName, TargetPeer peer) {
            return new Resolution(channelId, channelName, peer.peerKey(), peer.peerName(), null, List.of());
        }

        static Resolution needChoice(String problem, List<String> options) {
            return new Resolution(null, null, null, null, problem, options);
        }
    }

    // ===================== 查询 =====================

    /** 全部渠道（含未启用的，便于诊断"为什么发不出去"）。 */
    public List<TargetChannel> channels() {
        List<ChannelApp> apps = loadApps();
        List<TargetChannel> result = new ArrayList<>(apps.size());
        for (ChannelApp app : apps) {
            int peers = loadPeers(app.getId()).size();
            result.add(new TargetChannel(app.getId(), app.getName(), app.getChannelType(),
                    Boolean.TRUE.equals(app.getEnabled()), peers));
        }
        return result;
    }

    public List<TargetPeer> peers(Long channelAppId) {
        if (channelAppId == null) {
            return List.of();
        }
        ChannelApp app = loadApp(channelAppId);
        if (app == null) {
            return List.of();
        }
        return loadPeers(channelAppId).stream()
                .map(p -> new TargetPeer(app.getId(), app.getName(), app.getChannelType(),
                        p.getPeerKey(), p.getPeerName(), p.getPeerKind()))
                .toList();
    }

    // 下面两个是数据来源，单独抽出来方便单测替换（不依赖 MyBatis-Plus wrapper 的内部结构）

    protected List<ChannelApp> loadApps() {
        return appMapper.selectList(
                Wrappers.<ChannelApp>lambdaQuery().orderByAsc(ChannelApp::getId));
    }

    protected ChannelApp loadApp(Long channelAppId) {
        return appMapper.selectById(channelAppId);
    }

    protected List<ChannelPeer> loadPeers(Long channelAppId) {
        return peerMapper.selectList(Wrappers.<ChannelPeer>lambdaQuery()
                .eq(ChannelPeer::getChannelAppId, channelAppId)
                .orderByAsc(ChannelPeer::getId));
    }

    /** 所有渠道的接收人合集（模型"有哪些人可以推"时用）。 */
    public List<TargetPeer> allPeers() {
        List<TargetPeer> result = new ArrayList<>();
        for (TargetChannel channel : channels()) {
            result.addAll(peers(channel.id()));
        }
        return result;
    }

    // ===================== 解析 =====================

    /**
     * 解析推送目标。
     *
     * @param channelHint 渠道（id / 名称 / 名称片段），可为空
     * @param recipientHint 接收人（peerKey / 名称 / 名称片段），可为空
     * @param fallbackChannelId 会话自带的绑定（兜底）
     * @param fallbackPeerKey 会话自带的绑定（兜底）
     */
    public Resolution resolve(
            String channelHint, String recipientHint, Long fallbackChannelId, String fallbackPeerKey) {
        List<TargetChannel> channels = channels();
        if (channels.isEmpty()) {
            return Resolution.needChoice("平台还没有配置任何 IM 渠道，请先在「IM 渠道」里添加机器人", List.of());
        }

        // 1) 定渠道
        TargetChannel channel;
        if (hasText(channelHint)) {
            String hint = channelHint.trim();
            String lowerChannelHint = hint.toLowerCase(Locale.ROOT);
            List<TargetChannel> matched = channels.stream()
                    .filter(c -> String.valueOf(c.id()).equals(hint)
                            || c.name().equalsIgnoreCase(hint)
                            || c.name().toLowerCase(Locale.ROOT).contains(lowerChannelHint)
                            // 反向包含：模型把「#2 钉钉机器人（DINGTALK，2 个接收人）」整行贴回来
                            || lowerChannelHint.contains(c.name().toLowerCase(Locale.ROOT))
                            || c.type().equalsIgnoreCase(hint))
                    .toList();
            if (matched.isEmpty()) {
                return Resolution.needChoice("找不到渠道「" + channelHint + "」，可选：",
                        channels.stream().map(TargetChannel::display).toList());
            }
            if (matched.size() > 1) {
                return Resolution.needChoice("「" + channelHint + "」匹配到多个渠道，请指定一个：",
                        matched.stream().map(TargetChannel::display).toList());
            }
            channel = matched.get(0);
        } else if (fallbackChannelId != null) {
            Optional<TargetChannel> fallback = channels.stream()
                    .filter(c -> c.id().equals(fallbackChannelId)).findFirst();
            if (fallback.isEmpty()) {
                return Resolution.needChoice("会话绑定的渠道已不存在，可选：",
                        channels.stream().map(TargetChannel::display).toList());
            }
            channel = fallback.get();
        } else if (channels.size() == 1) {
            channel = channels.get(0);
        } else {
            return Resolution.needChoice("有多个渠道，请指定发给哪个：",
                    channels.stream().map(TargetChannel::display).toList());
        }

        // 2) 定接收人
        List<TargetPeer> peers = peers(channel.id());
        if (peers.isEmpty()) {
            return Resolution.needChoice(
                    "渠道「" + channel.name() + "」还没有绑定任何接收人（让用户先给机器人发一条消息，"
                            + "或在「IM 渠道 → 会话」里手工绑定 staffId）",
                    List.of());
        }
        if (hasText(recipientHint)) {
            String hint = recipientHint.trim();
            // 模型常把清单里的整行原样回填（如「你好（0935422236-…，单聊）」），
            // 所以除了「精确/包含」还要支持「提示串里包含接收人名或 peerKey」。
            String lowerHint = hint.toLowerCase(Locale.ROOT);
            List<TargetPeer> matched = peers.stream()
                    .filter(p -> p.peerKey().equals(hint)
                            || (p.peerName() != null && p.peerName().equalsIgnoreCase(hint))
                            || (p.peerName() != null
                                    && p.peerName().toLowerCase(Locale.ROOT).contains(lowerHint))
                            || lowerHint.contains(p.peerKey().toLowerCase(Locale.ROOT))
                            || (p.peerName() != null && !p.peerName().isBlank()
                                    && lowerHint.contains(p.peerName().toLowerCase(Locale.ROOT))))
                    .toList();
            if (matched.isEmpty()) {
                return Resolution.needChoice("渠道「" + channel.name() + "」里找不到接收人「" + hint + "」，可选：",
                        peers.stream().map(TargetPeer::display).toList());
            }
            if (matched.size() > 1) {
                return Resolution.needChoice("「" + hint + "」匹配到多个接收人，请指定一个：",
                        matched.stream().map(TargetPeer::display).toList());
            }
            return Resolution.resolved(channel.id(), channel.name(), matched.get(0));
        }
        if (hasText(fallbackPeerKey)) {
            Optional<TargetPeer> fallback = peers.stream()
                    .filter(p -> p.peerKey().equals(fallbackPeerKey)).findFirst();
            if (fallback.isPresent()) {
                return Resolution.resolved(channel.id(), channel.name(), fallback.get());
            }
        }
        if (peers.size() == 1) {
            return Resolution.resolved(channel.id(), channel.name(), peers.get(0));
        }
        return Resolution.needChoice("渠道「" + channel.name() + "」有多个接收人，请指定发给谁：",
                peers.stream().map(TargetPeer::display).toList());
    }

    /** 给模型看的清单文本。 */
    public String describeTargets() {
        List<TargetChannel> channels = channels();
        if (channels.isEmpty()) {
            return "平台还没有配置任何 IM 渠道。";
        }
        StringBuilder out = new StringBuilder("可用推送目标：\n");
        for (TargetChannel channel : channels) {
            out.append("- ").append(channel.display()).append('\n');
            for (TargetPeer peer : peers(channel.id())) {
                out.append("    · ").append(peer.display()).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
