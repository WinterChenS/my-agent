package com.myagent.agui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * SSE 出站 JSON 规范化：**去掉值为 null 的对象字段**。
 *
 * <p>为什么需要它（Task 9.1 实测发现的真实缺陷）：
 * AgentScope 的官方编码器会把未设置的可选字段序列化成 {@code "expiresAt": null}；
 * 而 AG-UI 官方客户端的 Zod 校验里这些字段是 {@code z.string().optional()}，
 * **null 不算可选**，于是整个 {@code RUN_FINISHED} 事件校验失败（ZodError），
 * 前端既收不到 interrupt，也就渲染不出审批卡。
 *
 * <p>AG-UI 协议对「无值」的语义就是**字段缺席**（官方客户端自己的
 * {@code omitOptionalNulls} 也是这么处理出站请求的），所以这里在服务端出口做同样的事。
 * 只递归删除对象里值为 null 的**字段**，不动数组元素（数组内的 null 属于数据本身）。
 */
@Component
public class AguiSseSanitizer {

    private final ObjectMapper objectMapper;

    public AguiSseSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 输入一段 JSON，输出去掉 null 字段后的 JSON；解析失败时原样返回。 */
    public String stripNulls(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode cleaned = clean(root);
            JsonNode fixed = humanizeContextLength(cleaned);
            return objectMapper.writeValueAsString(fixed);
        } catch (Exception ex) {
            return json;
        }
    }

    /**
     * 把「上下文超长」这类厂商原始报错翻译成可操作的话。
     *
     * <p>实测：会话上下文涨到 127 万 tokens 时，厂商直接返回
     * {@code context_length_exceeded}，前端只能看到一长串英文原文（还夹着 request_id），
     * 用户不知道该做什么。这里替换成中文提示 + 具体建议。
     */
    private JsonNode humanizeContextLength(JsonNode node) {
        if (!(node instanceof ObjectNode obj)) {
            return node;
        }
        JsonNode type = obj.get("type");
        JsonNode message = obj.get("message");
        if (type == null || message == null || !"RUN_ERROR".equals(type.asText())) {
            return node;
        }
        String text = message.asText();
        if (text == null) {
            return node;
        }
        boolean tooLong = text.contains("context_length_exceeded")
                || text.contains("maximum context length")
                || text.contains("exceeds the maximum");
        if (tooLong) {
            obj.put("message",
                    "上下文超过模型窗口上限：这一轮要发给模型的内容太多了（历史 + 本轮附件/工具结果）。"
                    + "建议：①新建一个会话继续（最有效）；②把该模型的 max_tokens（补全长度）调小，比如 8192，"
                    + "不要占满窗口；③少让 Agent 读超大文件。原始报错：" + text);
            obj.put("code", "CONTEXT_LENGTH_EXCEEDED");
            return obj;
        }
        // 厂商拒收图片（模型其实不支持图片输入，或图片格式不对）
        // 实测：模型配置里把「多模态」开了，但供应商根本不吃图，用户只看到一长串英文。
        boolean imageRejected = text.contains("unsupported image")
                || text.contains("image_url")
                || text.contains("image is not supported")
                || text.contains("does not support image")
                || text.contains("invalid image");
        if (imageRejected) {
            obj.put("message",
                    "这个模型不接受图片：供应商直接退回了本轮附带的图片。"
                    + "图片没有被使用，本轮失败。"
                    + "建议：①去「模型」页确认该模型的「支持多模态（图片输入）」开关是否与实际能力一致，"
                    + "关掉后图片会被自动剥离、对话仍可继续；②或改用支持图片的模型。原始报错：" + text);
            obj.put("code", "IMAGE_NOT_SUPPORTED");
        }
        return obj;
    }

    private JsonNode clean(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var fieldNames = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String name : fieldNames) {
                JsonNode value = object.get(name);
                if (value == null || value.isNull()) {
                    object.remove(name);
                } else {
                    clean(value);
                }
            }
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (JsonNode element : array) {
                clean(element);
            }
            return array;
        }
        return node;
    }
}
