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
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception ex) {
            return json;
        }
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
