package com.myagent.agui.skillimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.common.BizException;
import com.myagent.skillconfig.dto.SkillImportCandidate;
import com.myagent.skillconfig.dto.SkillImportRequest;
import com.myagent.skillconfig.dto.SkillImportResult;
import com.myagent.skillconfig.service.SkillImportService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 聊天里的 {@code install_skill}：多技能仓库先问用户、单技能直接装、危险结论不放行。
 *
 * <p>纯单元测试（服务打桩）：这一层只负责「交互策略」，下载与落库由 SkillImportService 负责，
 * 那边已有连库测试覆盖。
 */
class SkillInstallToolTest {

    private final SkillImportService importService = Mockito.mock(SkillImportService.class);

    private final SkillInstallTool tool = new SkillInstallTool(importService);

    private static SkillImportCandidate candidate(String name, String root, String verdict) {
        return new SkillImportCandidate(root, null, name, "说明", "正文", 2, 100, List.of("scripts/a.py"),
                verdict, List.of(), null, null);
    }

    private static ToolCallParam param(Map<String, Object> input) {
        ToolUseBlock block = new ToolUseBlock("call-1", "install_skill", input);
        return ToolCallParam.builder().toolUseBlock(block).input(input).build();
    }

    /**
     * 带运行时上下文的调用参数：自动绑定靠 {@code sessionId}（= threadId）定位会话用的 Agent，
     * 而不是 {@code agent.getAgentId()}（那是 AgentScope 的实例 UUID，在库里查不到）。
     */
    private static ToolCallParam paramWithContext(Map<String, Object> input, String threadId, String userId) {
        io.agentscope.core.agent.RuntimeContext context = io.agentscope.core.agent.RuntimeContext
                .builder()
                .sessionId(threadId)
                .userId(userId)
                .build();
        ToolUseBlock block = new ToolUseBlock("call-1", "install_skill", input);
        return ToolCallParam.builder().toolUseBlock(block).input(input).runtimeContext(context).build();
    }

    private static String text(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }

    @Test
    void 多技能仓库不擅自安装_返回候选清单() {
        when(importService.preview(any())).thenReturn(new SkillImportResult(
                "https://codeload.example/zip/HEAD",
                "HEAD",
                true,
                List.of(candidate("pdf", "skills/pdf", "SAFE"), candidate("xlsx", "skills/xlsx", "SAFE")),
                List.of(),
                List.of()));

        String output = text(tool.callAsync(param(new LinkedHashMap<>(Map.of("url", "https://github.com/o/r"))))
                .block());

        assertThat(output).contains("2 个技能").contains("skills/pdf").contains("skills/xlsx");
        verify(importService, never()).install(any());
    }

    @Test
    void 单技能仓库直接安装并报告结果() {
        when(importService.preview(any())).thenReturn(new SkillImportResult(
                "https://codeload.example/zip/HEAD",
                "HEAD",
                true,
                List.of(candidate("pdf", "skills/pdf", "SAFE")),
                List.of(),
                List.of()));
        when(importService.install(any())).thenReturn(new SkillImportResult(
                "https://codeload.example/zip/HEAD",
                "HEAD",
                false,
                List.of(candidate("pdf", "skills/pdf", "SAFE")),
                List.of(new SkillImportResult.Installed(1L, "pdf", "skills/pdf", 1, "created", 2)),
                List.of()));

        String output = text(tool.callAsync(param(new LinkedHashMap<>(Map.of("url", "https://github.com/o/r"))))
                .block());

        assertThat(output).contains("已安装 1 个技能").contains("pdf").contains("v1");
    }

    @Test
    void 指定names时跳过预览直接安装() {
        when(importService.install(any())).thenReturn(new SkillImportResult(
                "https://codeload.example/zip/HEAD",
                "HEAD",
                false,
                List.of(),
                List.of(new SkillImportResult.Installed(9L, "pdf", "skills/pdf", 2, "updated", 3)),
                List.of()));

        String output = text(tool.callAsync(param(new LinkedHashMap<>(Map.of(
                        "url", "https://github.com/o/r",
                        "names", List.of("skills/pdf")))))
                .block());

        verify(importService, never()).preview(any());
        verify(importService).install(argThat(request -> request.names().contains("skills/pdf")));
        assertThat(output).contains("updated").contains("3 个资源文件");
    }

    @Test
    void 安装后默认自动绑定到当前Agent_并说明下一轮可用() {
        when(importService.install(any())).thenReturn(new SkillImportResult(
                "https://codeload.example/zip/HEAD",
                "HEAD",
                false,
                List.of(),
                List.of(new SkillImportResult.Installed(7L, "pdf", "skills/pdf", 1, "created", 2)),
                List.of()));
        when(importService.bindToAgentOfThread("thread-1", 1L, List.of(7L))).thenReturn("stub-demo-agent");

        String output = text(tool.callAsync(paramWithContext(
                        new LinkedHashMap<>(Map.of("url", "https://github.com/o/r", "names", List.of("pdf"))),
                        "thread-1",
                        "1"))
                .block());

        verify(importService).bindToAgentOfThread("thread-1", 1L, List.of(7L));
        assertThat(output).contains("已绑定到当前 Agent").contains("下一轮");
    }

    @Test
    void bind设为false时不绑定() {
        when(importService.install(any())).thenReturn(new SkillImportResult(
                "https://codeload.example/zip/HEAD",
                "HEAD",
                false,
                List.of(),
                List.of(new SkillImportResult.Installed(7L, "pdf", "skills/pdf", 1, "created", 2)),
                List.of()));

        tool.callAsync(paramWithContext(
                        new LinkedHashMap<>(Map.of(
                                "url", "https://github.com/o/r",
                                "names", List.of("pdf"),
                                "bind", false)),
                        "thread-1",
                        "1"))
                .block();

        verify(importService, never()).bindToAgentOfThread(any(), any(), any());
    }

    @Test
    void 找不到Agent时安装仍然成功_只是提示手动绑定() {
        when(importService.install(any())).thenReturn(new SkillImportResult(
                "https://codeload.example/zip/HEAD",
                "HEAD",
                false,
                List.of(),
                List.of(new SkillImportResult.Installed(7L, "pdf", "skills/pdf", 1, "created", 2)),
                List.of()));
        when(importService.bindToAgentOfThread(any(), any(), any())).thenReturn(null);

        String output = text(tool.callAsync(paramWithContext(
                        new LinkedHashMap<>(Map.of("url", "https://github.com/o/r", "names", List.of("pdf"))),
                        "ghost-thread",
                        "1"))
                .block());

        assertThat(output).contains("已安装 1 个技能").contains("没能自动绑定");
    }

    @Test
    void 危险技能被跳过时把原因带回给用户() {
        when(importService.install(any())).thenReturn(new SkillImportResult(
                "https://codeload.example/zip/HEAD",
                "HEAD",
                false,
                List.of(),
                List.of(),
                List.of(new SkillImportResult.Skipped("evil", "skills/evil", "安全扫描为 DANGEROUS，拒绝安装"))));

        String output = text(tool.callAsync(param(new LinkedHashMap<>(Map.of(
                        "url", "https://github.com/o/r",
                        "names", List.of("evil")))))
                .block());

        assertThat(output).contains("跳过").contains("DANGEROUS");
    }

    @Test
    void 缺url时返回错误文本而不是抛异常() {
        String output = text(tool.callAsync(param(new LinkedHashMap<>())).block());
        assertThat(output).contains("安装失败").contains("url");
    }

    @Test
    void 服务异常转成错误文本() {
        when(importService.install(any())).thenThrow(BizException.badRequest("下载失败（HTTP 404）"));

        String output = text(tool.callAsync(param(new LinkedHashMap<>(Map.of(
                        "url", "https://github.com/o/nope",
                        "names", List.of("x")))))
                .block());

        assertThat(output).contains("安装失败").contains("404");
    }

    @Test
    void 工具元信息正确() {
        assertThat(tool.getName()).isEqualTo("install_skill");
        assertThat(tool.isReadOnly()).isFalse();
        assertThat(tool.getParameters()).containsEntry("required", List.of("url"));
    }
}
