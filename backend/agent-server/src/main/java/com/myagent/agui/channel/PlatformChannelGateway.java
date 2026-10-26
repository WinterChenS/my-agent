package com.myagent.agui.channel;

import com.myagent.artifact.dto.ArtifactResponse;
import com.myagent.artifact.service.ArtifactCaptureService;
import com.myagent.artifact.service.ArtifactService;
import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.service.ChannelAppService;
import com.myagent.chat.entity.ChatSession;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 平台侧的 {@link Gateway} 实现：把 IM 消息接进**我们自己的**会话/记忆/产物链路。
 *
 * <p>为什么不用官方 {@code HarnessGateway}：它按 {@code MsgContext.canonicalKey()} 自己算 sessionId，
 * 我们就没法把 IM 会话锚到 {@code chat_session}，Web 端也就看不到历史。实现这个只有两个抽象方法的
 * 官方 SPI（和 {@code PgAgentStateStore} 同性质），换来的是「一套会话、一套记忆、一套产物」。
 *
 * <p>出站只发**正文**：思考过程、工具调用、工具结果一律不落到 IM（用户明确要求）。
 */
@Component
public class PlatformChannelGateway implements Gateway {

    private static final Logger log = LoggerFactory.getLogger(PlatformChannelGateway.class);

    /** 用户消息里的媒体占位符（官方 mapper 写入形如 {@code [image]} / {@code [file: a.pdf]}）。 */
    private static final Pattern MEDIA_MARKER =
            Pattern.compile("\\[(image|audio|video|file)(:\\s*[^\\]]*)?\\]");

    /** 单轮随回复发出的产物数量上限（每条消息只能带一个媒体，太多会刷屏）。 */
    static final int MAX_OUTBOUND_ATTACHMENTS = 4;

    /** 会话标题最大长度。 */
    private static final int TITLE_MAX = 40;

    private final ChannelAppService channelAppService;
    private final ChannelSessionBridge sessionBridge;
    private final com.myagent.agui.AgentFactory agentFactory;
    private final ArtifactService artifactService;
    private final ArtifactCaptureService artifactCaptureService;
    private final DingTalkMediaClientFactory mediaClientFactory;

    public PlatformChannelGateway(
            ChannelAppService channelAppService,
            ChannelSessionBridge sessionBridge,
            com.myagent.agui.AgentFactory agentFactory,
            ArtifactService artifactService,
            ArtifactCaptureService artifactCaptureService,
            DingTalkMediaClientFactory mediaClientFactory) {
        this.channelAppService = channelAppService;
        this.sessionBridge = sessionBridge;
        this.agentFactory = agentFactory;
        this.artifactService = artifactService;
        this.artifactCaptureService = artifactCaptureService;
        this.mediaClientFactory = mediaClientFactory;
    }

    @Override
    public void bindMainAgent(HarnessAgent agent) {
        // 渠道一律按 channel_app.agent_id 解析 Agent，不需要「主 Agent」概念
    }

    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
        return Mono.just(textMsg("（渠道消息缺少上下文，无法处理）"));
    }

    @Override
    public Mono<Msg> run(
            MsgContext msgContext,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext,
            InboundMessage inbound) {
        return Mono.fromCallable(() -> handle(inbound, outboundAddress))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<io.agentscope.core.event.AgentEvent> runStream(
            MsgContext msgContext,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext,
            InboundMessage inbound) {
        // 官方钉钉/飞书渠道都是非流式（只调用 run），这里不实现流式：
        // 一旦有渠道走这条路，宁可返回空流也不要悄悄退化成"逐字刷屏"
        return Flux.empty();
    }

    // ===================== 主流程 =====================

    private Msg handle(InboundMessage inbound, OutboundAddress address) {
        if (inbound == null) {
            return textMsg("（没有收到消息内容）");
        }
        ChannelApp app = resolveApp(inbound);
        if (app == null) {
            return textMsg("（这个机器人还没配置对应的渠道应用）");
        }
        String runId = UUID.randomUUID().toString();
        try {
            // 1) 先把「文字 + 媒体描述」抽出来（不下载），用于会话标题与占位判断
            InboundScan scan = scanInbound(inbound);
            if (scan.text().isBlank() && scan.media().isEmpty()) {
                return textMsg("（这条消息没有可处理的文字或文件）");
            }

            // 2) 会话（首次对话时创建，并记住推送地址）
            ChatSession session = sessionBridge.ensureSession(app, inbound, address, titleFor(scan.text()));

            // 3) 媒体：下载 → 图片直接作为 ImageBlock（多模态）/ 文件落工作区
            List<ContentBlock> blocks = new ArrayList<>();
            List<String> notes = new ArrayList<>(scan.mediaNotes());
            for (MediaRef media : scan.media()) {
                handleInboundMedia(app, session, media, blocks, notes);
            }
            String userText = joinText(scan.text(), notes);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("channel", app.getChannelType());
            meta.put("channelAppId", app.getId());
            meta.put("peerKey", ChannelSessionBridge.peerKey(inbound));
            meta.put("runId", runId);
            if (scan.messageId() != null) {
                meta.put("channelMessageId", scan.messageId());
            }
            sessionBridge.appendUserMessage(session, userText, meta);

            Map<String, Long> workspaceSnapshot = safeBeginRun(app);

            // 4) 跑 Agent（与 AG-UI 同一条官方入口）
            String answer = runAgent(app, session, blocks, userText);

            // 5) 落库 + 登记产物
            sessionBridge.appendAssistantMessage(session, answer, Map.of(
                    "channel", app.getChannelType(), "channelAppId", app.getId(), "runId", runId));
            safeEndRun(app, session, workspaceSnapshot, runId);

            // 6) 把本轮产物发回 IM（只发文件/图片，不混进正文）
            sendAttachments(app, session, runId, address, peerIsDirect(inbound));
            return textMsg(answer);
        } catch (Exception ex) {
            log.warn("IM run 失败 appId={}: {}", app.getId(), ex.toString(), ex);
            return textMsg("这条没跑成功：" + rootMessage(ex));
        }
    }

    private String runAgent(
            ChannelApp app, ChatSession session, List<ContentBlock> mediaBlocks, String text) {
        HarnessAgent agent = agentFactory.build(app.getAgentId());
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.builder().text(text).build());
        blocks.addAll(mediaBlocks);
        Msg userMsg = Msg.builder().role(MsgRole.USER).content(blocks).build();
        RuntimeContext context = RuntimeContext.builder()
                .sessionId(session.getThreadId())
                .userId(String.valueOf(app.getOwnerUserId()))
                .build();
        StreamOptions options = StreamOptions.builder()
                .eventTypes(EventType.ALL)
                .incremental(true)
                .build();
        StringBuilder answer = new StringBuilder();
        agent.stream(List.of(userMsg), options, context)
                .doOnNext(event -> accumulate(event, answer))
                .blockLast();
        String result = answer.toString().strip();
        return result.isEmpty() ? "（这轮没有产生文字回复）" : result;
    }

    /** 只累积「正文」增量：思考、工具调用、工具结果一律丢弃（需求 3）。 */
    static void accumulate(Event event, StringBuilder out) {
        if (event == null || event.getType() != EventType.AGENT_RESULT) {
            return;
        }
        Msg message = event.getMessage();
        if (message == null) {
            return;
        }
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock textBlock) {
                merge(out, textBlock.getText());
            }
        }
    }

    /** 兼容「增量」与「累积」两种下发方式（不同版本/模型行为不同）。 */
    static void merge(StringBuilder out, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        String current = out.toString();
        if (current.isEmpty()) {
            out.append(chunk);
        } else if (chunk.equals(current)) {
            // 重复整段：忽略
        } else if (chunk.startsWith(current)) {
            out.setLength(0);
            out.append(chunk);
        } else {
            out.append(chunk);
        }
    }

    // ===================== 入站解析 =====================

    /** 一条入站消息里抽出来的东西。 */
    record InboundScan(String text, List<MediaRef> media, List<String> mediaNotes, String messageId) {}

    /** 入站媒体的引用（官方 mapper 在 metadata 里给的）。 */
    record MediaRef(String kind, String id, String fileName, String mime, String recognition) {}

    private InboundScan scanInbound(InboundMessage inbound) {
        String rawText = "";
        String messageId = null;
        List<MediaRef> media = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (inbound.messages() != null) {
            for (Msg msg : inbound.messages()) {
                if (msg == null) {
                    continue;
                }
                Map<String, Object> metadata = msg.getMetadata() == null ? Map.of() : msg.getMetadata();
                if (messageId == null) {
                    messageId = str(metadata.get("channelMessageId"));
                }
                String kind = str(metadata.get(ChannelMediaMetadata.KIND));
                if (kind.isBlank()) {
                    if (rawText.isBlank()) {
                        rawText = msg.getTextContent();
                    }
                    continue;
                }
                String fileName = defaultFileName(kind, str(metadata.get(ChannelMediaMetadata.FILE_NAME)));
                media.add(new MediaRef(
                        kind,
                        str(metadata.get(ChannelMediaMetadata.ID)),
                        fileName,
                        guessMime(fileName),
                        str(metadata.get(ChannelMediaMetadata.RECOGNITION))));
                if (rawText.isBlank()) {
                    rawText = msg.getTextContent();
                }
            }
        }
        return new InboundScan(cleanMarkers(rawText), media, notes, messageId);
    }

    private void handleInboundMedia(
            ChannelApp app,
            ChatSession session,
            MediaRef media,
            List<ContentBlock> blocks,
            List<String> notes) {
        // 语音：优先用渠道自带的转写文本，模型不必"听"
        if ("audio".equals(media.kind()) && !media.recognition().isBlank()) {
            notes.add("（用户发来一段语音，转写如下）" + media.recognition());
            return;
        }
        DingTalkMediaClient client = mediaClientFactory.get(app);
        if (client == null) {
            notes.add("（收到一个" + kindLabel(media.kind()) + "：" + media.fileName() + "，该渠道暂不支持下载）");
            return;
        }
        try {
            byte[] bytes = client.download(media.id());
            if ("image".equals(media.kind()) && agentFactory.isMultimodal(app.getAgentId())) {
                blocks.add(ImageBlock.builder()
                        .source(new Base64Source(
                                media.mime(), Base64.getEncoder().encodeToString(bytes)))
                        .build());
                // 图片也留一份到产物/工作区，便于 Web 端回看
                artifactService.store(app.getOwnerUserId(), session.getId(),
                        ArtifactService.ROLE_SOURCE, media.fileName(), media.mime(), bytes, false);
                notes.add("（用户发来一张图片：" + media.fileName() + "）");
                return;
            }
            artifactService.store(app.getOwnerUserId(), session.getId(),
                    ArtifactService.ROLE_SOURCE, media.fileName(), media.mime(), bytes, false);
            // store() 本身不物化（注释写明"由 upload() 负责"），IM 路径必须自己落到工作区，
            // 否则模型 read_file 永远 File not found（实测踩过）。
            materializeOriginal(app, media.fileName(), bytes);
            if ("image".equals(media.kind())) {
                notes.add("（用户发来一张图片：" + media.fileName()
                        + "，已放入工作区；当前模型不支持看图，可忽略或提示用户换多模态模型）");
            } else {
                notes.add(fileNote(app, media, bytes));
            }
        } catch (Exception ex) {
            log.warn("处理入站媒体失败 kind={} appId={}: {}", media.kind(), app.getId(), ex.toString());
            notes.add("（收到一个" + kindLabel(media.kind()) + "：" + media.fileName() + "，但下载失败）");
        }
    }

    // ===================== 出站媒体 =====================

    private void sendAttachments(
            ChannelApp app,
            ChatSession session,
            String runId,
            OutboundAddress address,
            boolean direct) {
        if (address == null || address.to() == null || address.to().isBlank()) {
            return;
        }
        DingTalkMediaClient client = mediaClientFactory.get(app);
        if (client == null) {
            return;
        }
        List<ArtifactResponse> produced;
        try {
            produced = artifactService.list(app.getOwnerUserId(), session.getId()).stream()
                    .filter(a -> runId.equals(a.runId()))
                    .filter(a -> ArtifactService.ROLE_ARTIFACT.equals(a.role()))
                    .filter(a -> a.sizeBytes() != null && a.sizeBytes() > 0)
                    .limit(MAX_OUTBOUND_ATTACHMENTS)
                    .toList();
        } catch (Exception ex) {
            log.warn("查询本轮产物失败 sessionId={}: {}", session.getId(), ex.toString());
            return;
        }
        for (ArtifactResponse artifact : produced) {
            try {
                ArtifactService.DownloadResult content =
                        artifactService.download(app.getOwnerUserId(), artifact.id());
                if (content.bytes() == null || content.bytes().length == 0) {
                    continue;
                }
                client.sendMedia(direct, address.to(), content.fileName(), content.bytes());
            } catch (Exception ex) {
                log.warn("发送产物到 IM 失败 artifactId={}: {}", artifact.id(), ex.toString());
            }
        }
    }

    // ===================== 辅助 =====================

    private Map<String, Long> safeBeginRun(ChannelApp app) {
        try {
            return artifactCaptureService.beginRun(String.valueOf(app.getOwnerUserId()));
        } catch (Exception ex) {
            log.debug("产物快照失败（忽略）: {}", ex.getMessage());
            return null;
        }
    }

    private void safeEndRun(ChannelApp app, ChatSession session, Map<String, Long> snapshot, String runId) {
        if (snapshot == null) {
            return;
        }
        try {
            artifactCaptureService.endRun(
                    app.getOwnerUserId(), session.getId(), session.getThreadId(), snapshot, runId);
        } catch (Exception ex) {
            log.warn("登记产物失败 sessionId={}: {}", session.getId(), ex.toString());
        }
    }

    private ChannelApp resolveApp(InboundMessage inbound) {
        String channelId = inbound.channelId();
        if (channelId == null || channelId.isBlank()) {
            return null;
        }
        try {
            return channelAppService.require(Long.valueOf(channelId));
        } catch (Exception ex) {
            log.warn("渠道应用不存在 channelId={}", channelId);
            return null;
        }
    }

    private static boolean peerIsDirect(InboundMessage inbound) {
        return inbound.peer() == null
                || inbound.peer().kind() == null
                || inbound.peer().kind().isDirect();
    }

    static String cleanMarkers(String text) {
        if (text == null) {
            return "";
        }
        return MEDIA_MARKER.matcher(text).replaceAll("").replaceAll("[ \\t]{2,}", " ").strip();
    }

    /**
     * 文件类入站消息的说明：**给准确路径** + 能转文本就先转好（xlsx/docx/pptx）。
     *
     * <p>踩过的坑：只写"已放入工作区"不给路径，模型会说"文件没有位置"；直接把 xlsx 路径给模型，
     * 它读出来是二进制乱码。所以 Office 文件额外落一份 `.txt` 旁挂文件，并附一小段预览。
     */
    private String fileNote(ChannelApp app, MediaRef media, byte[] bytes) {
        String safeName = ArtifactService.safeFileName(media.fileName());
        String path = "uploads/" + safeName;
        String label = kindLabel(media.kind());
        if (!"file".equals(media.kind()) || !OfficeTextExtractor.supports(safeName)) {
            return "（用户上传了" + label + "：" + safeName + "，工作区路径：" + path
                    + "，可用 read_file 读取）";
        }
        java.util.Optional<OfficeTextExtractor.Extracted> extracted =
                OfficeTextExtractor.extract(safeName, bytes);
        if (extracted.isEmpty()) {
            return "（用户上传了" + label + "：" + safeName + "，工作区路径：" + path
                    + "；这是二进制文件，解析失败，可告知用户换个格式或直接问内容）";
        }
        String sidecar = OfficeTextExtractor.sidecarName(safeName);
        try {
            artifactService.materialize(
                    String.valueOf(app.getOwnerUserId()),
                    extracted.get().text().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    sidecar);
        } catch (Exception ex) {
            log.warn("写入 Office 文本旁挂文件失败 {}: {}", sidecar, ex.toString());
        }
        String preview = extracted.get().text();
        if (preview.length() > 800) {
            preview = preview.substring(0, 800) + "\n…（完整内容见文件）";
        }
        return "（用户上传了" + label + "：" + safeName + "，原始文件：" + path
                + "；已转成文本：" + "uploads/" + sidecar
                + "，可直接 read_file 该文本）\n内容预览：\n" + preview;
    }

    /** 把入站文件原样落到工作区 uploads/（失败只告警，不影响这一轮对话）。 */
    private void materializeOriginal(ChannelApp app, String fileName, byte[] bytes) {
        try {
            artifactService.materialize(String.valueOf(app.getOwnerUserId()), bytes, fileName);
        } catch (Exception ex) {
            log.warn("物化入站文件失败 {}: {}", fileName, ex.toString());
        }
    }

    private static String joinText(String text, List<String> notes) {
        StringBuilder builder = new StringBuilder(text == null ? "" : text);
        for (String note : notes) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(note);
        }
        return builder.toString().strip();
    }

    private static String titleFor(String text) {
        String firstLine = text == null ? "" : text.strip().split("\n")[0].strip();
        if (firstLine.isEmpty()) {
            return "IM 会话";
        }
        return firstLine.length() <= TITLE_MAX ? firstLine : firstLine.substring(0, TITLE_MAX) + "…";
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "image" -> "图片";
            case "file" -> "文件";
            case "audio" -> "语音";
            case "video" -> "视频";
            default -> "媒体";
        };
    }

    private static String defaultFileName(String kind, String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return switch (kind) {
            case "image" -> "image.png";
            case "audio" -> "audio.amr";
            case "video" -> "video.mp4";
            default -> "file.bin";
        };
    }

    static String guessMime(String fileName) {
        String ext = DingTalkMediaClient.extension(fileName);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "md" -> "text/markdown";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static Msg textMsg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }
}
