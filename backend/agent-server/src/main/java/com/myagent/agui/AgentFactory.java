package com.myagent.agui;

import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.agentconfig.mapper.AgentMcpServerMapper;
import com.myagent.agentconfig.mapper.AgentSkillMapper;
import com.myagent.mcpconfig.entity.McpServer;
import com.myagent.mcpconfig.mapper.McpServerMapper;
import com.myagent.common.BizException;
import com.myagent.modelconfig.entity.LlmModel;
import com.myagent.modelconfig.entity.LlmProvider;
import com.myagent.modelconfig.mapper.LlmModelMapper;
import com.myagent.modelconfig.mapper.LlmProviderMapper;
import com.myagent.common.CryptoService;
import com.myagent.skillconfig.mapper.SkillMapper;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 核心翻译器：把数据库里的 Agent 配置行翻译成 AgentScope 的 {@link HarnessAgent}。
 *
 * <p>职责（技术方案第 4 节）：
 * <ul>
 *   <li>系统提示词、模型、采样参数、最大迭代
 *   <li>权限模式 → AgentScope 权限上下文（HITL 基础）
 *   <li>Skill 与 MCP 工具装载（Phase 5 / Phase 6 接入，见 {@code TODO(P5)} / {@code TODO(P6)}）
 * </ul>
 *
 * <p>缓存：按 agent 主键缓存构建结果，配置变更时通过 {@link #invalidate(Long)} 失效。
 * 门禁 G2：AgentScope API 只出现在 {@code agui} 包。
 */
@Component
public class AgentFactory implements AgentCacheInvalidator, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    /** 占位中间件：{@code HarnessAgent.Builder.middleware} 不接受 null，诊断关闭时用它顶位。 */
    private static final io.agentscope.core.middleware.MiddlewareBase NOOP_MIDDLEWARE =
            new io.agentscope.core.middleware.MiddlewareBase() {};

    private final AgentMapper agentMapper;
    private final LlmModelMapper llmModelMapper;
    private final LlmProviderMapper llmProviderMapper;
    private final CryptoService cryptoService;
    private final ModelFactory modelFactory;
    private final AguiProperties aguiProperties;
    private final SkillRepositorySync skillRepositorySync;
    private final AgentSkillMapper agentSkillMapper;
    private final SkillMapper skillMapper;
    private final AgentMcpServerMapper agentMcpServerMapper;
    private final McpServerMapper mcpServerMapper;
    private final McpToolkitFactory mcpToolkitFactory;
    // Phase 11 / D23：会话状态与长期记忆入库（官方 SPI 的 PostgreSQL 实现）
    private final com.myagent.agui.memory.PgAgentStateStore pgAgentStateStore;
    private final io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem memoryRemoteFilesystem;
    /** 官方「Agent 主动交付产物」的接收端（见 PlatformArtifactDeliveryTarget）。 */
    private final com.myagent.artifact.PlatformArtifactDeliveryTarget artifactDeliveryTarget;

        /** 是否把最终系统提示打到日志（诊断用，默认关）。 */
    private final boolean promptDebugEnabled;

    /** 缓存键：{@code "12"} 或 {@code "12|3,5"}（含会话级技能时），见 build(Long, List)。 */
    private final Map<String, HarnessAgent> cache = new ConcurrentHashMap<>();

    public AgentFactory(
            AgentMapper agentMapper,
            LlmModelMapper llmModelMapper,
            LlmProviderMapper llmProviderMapper,
            CryptoService cryptoService,
            ModelFactory modelFactory,
            AguiProperties aguiProperties,
            SkillRepositorySync skillRepositorySync,
            AgentSkillMapper agentSkillMapper,
            SkillMapper skillMapper,
            AgentMcpServerMapper agentMcpServerMapper,
            McpServerMapper mcpServerMapper,
            McpToolkitFactory mcpToolkitFactory,
            com.myagent.agui.memory.PgAgentStateStore pgAgentStateStore,
            io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem memoryRemoteFilesystem,
            com.myagent.artifact.PlatformArtifactDeliveryTarget artifactDeliveryTarget,
            @org.springframework.beans.factory.annotation.Value("${myagent.agui.debug-system-prompt:false}")
                    boolean promptDebugEnabled) {
        this.agentMapper = agentMapper;
        this.llmModelMapper = llmModelMapper;
        this.llmProviderMapper = llmProviderMapper;
        this.cryptoService = cryptoService;
        this.modelFactory = modelFactory;
        this.aguiProperties = aguiProperties;
        this.skillRepositorySync = skillRepositorySync;
        this.agentSkillMapper = agentSkillMapper;
        this.skillMapper = skillMapper;
        this.agentMcpServerMapper = agentMcpServerMapper;
        this.mcpServerMapper = mcpServerMapper;
        this.mcpToolkitFactory = mcpToolkitFactory;
        this.pgAgentStateStore = pgAgentStateStore;
        this.memoryRemoteFilesystem = memoryRemoteFilesystem;
        this.artifactDeliveryTarget = artifactDeliveryTarget;
        this.promptDebugEnabled = promptDebugEnabled;
    }

    /** 按 Agent 主键构建（带缓存）。 */
    public HarnessAgent build(Long agentDbId) {
        return build(agentDbId, java.util.List.of());
    }

    /**
     * 按 Agent 主键 + 会话级额外技能构建（D21：`/` 选中的 Skill 并入该会话的技能集合）。
     *
     * <p>缓存键为 {@code agentDbId|排序后的技能id}，因此不同技能组合互不干扰；
     * {@link #invalidate(Long)} 会清掉该 agent 的所有组合。
     */
    public HarnessAgent build(Long agentDbId, java.util.List<Long> extraSkillIds) {
        java.util.List<Long> extras = extraSkillIds == null
                ? java.util.List.of()
                : extraSkillIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        String cacheKey = extras.isEmpty()
                ? String.valueOf(agentDbId)
                : agentDbId + "|" + extras.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));

        HarnessAgent cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Agent agent = agentMapper.selectById(agentDbId);
        if (agent == null) {
            throw BizException.notFound("Agent 不存在: " + agentDbId);
        }
        if (!"ENABLED".equals(agent.getStatus())) {
            throw BizException.badRequest("Agent 已禁用: " + agent.getAgentId());
        }
        HarnessAgent built = doBuild(agent, extras);
        HarnessAgent previous = cache.putIfAbsent(cacheKey, built);
        if (previous != null) {
            closeQuietly(built);
            return previous;
        }
        return built;
    }

    /** 按业务标识 agent_id 构建。 */
    public HarnessAgent buildByAgentId(String agentId) {
        Agent agent = agentMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Agent>lambdaQuery()
                        .eq(Agent::getAgentId, agentId));
        if (agent == null) {
            throw BizException.notFound("Agent 不存在: " + agentId);
        }
        return build(agent.getId());
    }

    /**
     * 把外部引用解析为 Agent 主键：纯数字视为主键，否则按业务标识 agent_id 查。
     *
     * @return 找不到时返回 null（由调用方决定报错方式）
     */
    public Long resolveAgentDbId(String reference) {
        if (!org.springframework.util.StringUtils.hasText(reference)) {
            return null;
        }
        String trimmed = reference.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            Long id = Long.valueOf(trimmed);
            return agentMapper.selectById(id) == null ? null : id;
        }
        Agent agent = agentMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Agent>lambdaQuery()
                        .eq(Agent::getAgentId, trimmed));
        return agent == null ? null : agent.getId();
    }

    private HarnessAgent doBuild(Agent agent, java.util.List<Long> extraSkillIds) {
        LlmModel model = agent.getModelId() == null ? null : llmModelMapper.selectById(agent.getModelId());
        if (model == null) {
            throw BizException.badRequest("Agent 未绑定有效模型: " + agent.getAgentId());
        }
        LlmProvider provider = llmProviderMapper.selectById(model.getProviderId());
        if (provider == null) {
            throw BizException.badRequest("模型对应的供应商不存在: " + model.getProviderId());
        }
        if (!"ENABLED".equals(provider.getStatus())) {
            throw BizException.badRequest("供应商已禁用: " + provider.getName());
        }

        String apiKey = cryptoService.decrypt(provider.getApiKeyCipher());
        // Phase 15：模型级显式列（温度/Top-P/输出长度）优先于历史 params_json，二者合并后交给模型工厂
        Map<String, Object> params = new java.util.HashMap<>(
                model.getParams() == null ? Map.of() : model.getParams());
        if (model.getTemperature() != null) {
            params.put("temperature", model.getTemperature());
        }
        if (model.getTopP() != null) {
            params.put("topP", model.getTopP());
        }
        if (model.getMaxTokens() != null) {
            params.put("maxTokens", model.getMaxTokens());
        }
        ChatModelBase chatModel =
                modelFactory.create(provider.getProviderType(), provider.getBaseUrl(), apiKey, model.getModelId(), params, true);

        // 参数优先级：Agent 级覆盖 > 模型级（模型级也必须生效，不能被空 options 覆盖掉）
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (agent.getTemperature() != null) {
            options.temperature(agent.getTemperature().doubleValue());
        } else if (model.getTemperature() != null) {
            options.temperature(model.getTemperature().doubleValue());
        }
        if (agent.getMaxTokens() != null) {
            options.maxTokens(agent.getMaxTokens());
        } else if (model.getMaxTokens() != null) {
            options.maxTokens(model.getMaxTokens());
        }
        if (model.getTopP() != null) {
            options.topP(model.getTopP().doubleValue());
        }

        // Skill：官方仓库 + 按绑定过滤（只暴露该 Agent 绑定的启用 Skill）
        // 技能集合 = Agent 绑定的 + 会话级选中的（D21），去重后交给官方 SkillFilter
        java.util.LinkedHashSet<Long> allSkillIds = new java.util.LinkedHashSet<>(
                agentSkillMapper.findSkillIds(agent.getId()));
        if (extraSkillIds != null) {
            allSkillIds.addAll(extraSkillIds);
        }
        List<String> boundSkillNames = allSkillIds.isEmpty()
                ? List.of()
                : skillMapper.findNamesByIds(new java.util.ArrayList<>(allSkillIds));
        SkillFilter skillFilter = boundSkillNames.isEmpty()
                ? SkillFilter.none()
                : SkillFilter.only(boundSkillNames.toArray(String[]::new));

        // MCP：按绑定注册官方 MCP 工具（工具由框架按 Server 命名空间暴露）
        List<Long> boundMcpIds = agentMcpServerMapper.findMcpServerIds(agent.getId());
        List<McpServer> mcpServers = boundMcpIds.isEmpty()
                ? List.of()
                : mcpServerMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<McpServer>lambdaQuery()
                                .in(McpServer::getId, boundMcpIds)
                                .eq(McpServer::getStatus, "ENABLED"));
        // AG-UI 交互卡片工具（确认卡/映射卡）对所有 Agent 开放：
        // 它们是只读的「往界面发一张卡」工具，不产生副作用；只在 demo-agent 上开放会让
        // 「用技能驱动 AG-UI 交互」这件事对其它 Agent 失效（别人的 Agent 拿不到工具）。
        Toolkit toolkit = mcpToolkitFactory.buildToolkit(mcpServers, true);

        HarnessAgent built = HarnessAgent.builder()
                .name(agent.getName())
                .agentId(agent.getAgentId())
                // 追加平台级「交互卡片」指引：不依赖用户是否绑定了对应技能，
                // 每个 Agent 都知道自己手上有卡片工具、以及什么时候该用（用户反馈"不会主动用卡片"）
                // 再追加「长期记忆用法」指引：框架的 memory_search 是字面量整串匹配，
                // 模型传整句问句必然 0 命中（用户反馈"永久记忆不生效"）
                .sysPrompt(withSkillGuidance(
                        withMemoryGuidance(withCardGuidance(agent.getSysPrompt()))))
                // 官方产物交付：配了它，框架会向 Agent 注册内置工具 deliverArtifact，
                // Agent 主动交付的成果会进 artifact 表（并记下所属 run，供气泡下方展示）
                .artifactDeliveryTarget(artifactDeliveryTarget)
                .model(chatModel)
                .maxIters(agent.getMaxIterations() == null ? 20 : agent.getMaxIterations())
                .workspace(java.nio.file.Path.of(aguiProperties.workspaceRoot()))
                .generateOptions(options.build())
                .skillRepository(skillRepositorySync.newRepository())
                .skillFilter(skillFilter)
                .toolkit(toolkit)
                // Phase 11 / D23：框架会话状态落数据库（替换默认 JsonFileAgentStateStore）
                .stateStore(pgAgentStateStore)
                // 每轮把记忆里的关键事实提到系统提示最前面：
                // 框架注入的 <memory_context> 靠后且体量大，模型实测会忽略它、改用 memory_search，
                // 而后者是整串字面量匹配（整句/多词 → 0 命中）→ 模型就说“没有记录”。
                .middleware(new com.myagent.agui.middleware.MemoryHintMiddleware(
                        java.nio.file.Path.of(aguiProperties.workspaceRoot())))
                // 诊断开关（默认关）：把最终系统提示的形态打日志。
                // 技能清单没渲染出来时框架是**静默**的，模型只会说「没有可用技能」——
                // 实测排查这类问题只能靠它（见 PromptDebugMiddleware 注释）。
                .middleware(promptDebugEnabled
                        ? new com.myagent.agui.middleware.PromptDebugMiddleware()
                        : NOOP_MIDDLEWARE)
                // 上下文治理（官方能力）：历史超阈值就压缩；超长工具结果（例如被读进来的大文件正文）
                // 外置到文件、只留预览。没有这两个配置时，上下文会无上限增长——实测出现过
                // 1.8MB 的会话状态，向模型请求 127 万 tokens 直接被厂商拒绝（context_length_exceeded）。
                .compaction(CompactionConfig.builder()
                        // Phase 15：上下文长度（模型窗口）也要生效——压缩阈值取「配置值」与
                        // 「窗口的 60%」中的较小者，避免窗口小的模型被压爆、窗口大的模型压得过勤。
                        .triggerTokens(compactionTriggerFor(model.getContextWindow()))
                        .keepTokens(aguiProperties.compactionKeepTokens())
                        .build())
                .toolResultEviction(ToolResultEvictionConfig.builder()
                        .maxResultChars(aguiProperties.toolResultMaxChars())
                        .previewChars(aguiProperties.toolResultPreviewChars())
                        .build())
                // Phase 11 / D23：工作区 memory/** 经官方 CompositeFilesystem 路由到数据库
                .filesystemRoute(MEMORY_ROUTE_PREFIX, memoryRemoteFilesystem)
                // 权限上下文必须在 toolkit 之后构建：ask 规则要覆盖 Agent 实际拥有的工具
                .permissionContext(buildPermissionContext(agent.getPermissionMode(), toolkit))
                .build();
        log.info(
                "构建 Agent 完成: {} (mode={}, skills={}, mcpServers={})",
                agent.getAgentId(),
                agent.getPermissionMode(),
                boundSkillNames,
                mcpServers.stream().map(McpServer::getName).toList());
        return built;
    }


    /**
     * 在系统提示词后面追加一段**技能**使用指引。
     *
     * <p>为什么必须写死这几条（2026-10-23 用户实测，代价很大）：
     *
     * <ul>
     *   <li>框架的 skill id 是 {@code <名字>_<source>}（实测 {@code architecture-diagram_myagent}），
     *       但模型看到用户说"用 architecture-diagram 技能"就直接拿裸名字去调
     *       {@code load_skill_through_path} → {@code Error: Skill not found}，然后回复"技能加载失败"；
     *   <li>它还会用 {@code list_files} / {@code glob **&#47;SKILL.md} 去磁盘找技能，找不到就断定"没装"——
     *       而技能正文本来就不落盘（由工具从数据库读），磁盘上永远没有 SKILL.md；
     *   <li>技能没绑定到本 Agent 时，模型只能反复猜 id，用户拿到的是"加载失败"这种无用信息。
     * </ul>
     *
     * <p>这三条都不是"模型不够聪明"，而是**平台约定必须讲清楚**。
     */
    static String withSkillGuidance(String sysPrompt) {
        String base = sysPrompt == null ? "" : sysPrompt.trim();
        String guidance = String.join("\n",
                "## 技能（Skills）使用规则（重要）",
                "1. 系统提示里的 `<available_skills>` 列出当前 Agent 已绑定的技能，每个技能有 `<name>` 与 `<skill-id>`。",
                "2. **加载技能必须用 `<skill-id>`**（格式是「技能名_myagent」，例如 `pdf_myagent`），**不要用 `<name>`**：",
                "   用 `<name>` 调 `load_skill_through_path` 会返回 `Skill not found`。",
                "3. 用户消息里出现 `[[skill:xxx]]` 时，`xxx` 是**技能名**：到 `<available_skills>` 找同名 `<name>`，",
                "   再用它的 `<skill-id>` 加载；找不到就先按第 4 条处理，不要猜 id。",
                "4. `<available_skills>` 里**没有**的技能 = 未绑定到本 Agent 或已停用。这时直接告诉用户：",
                "   「该技能没有绑定到当前 Agent」并给出两条路——到「Agent 配置」里绑定，或在对话输入框用 `/` 选择技能；",
                "   不要反复尝试不同的 id，也不要说「技能文件不存在」。",
                "5. 技能的正文与 `references/` `scripts/` 都在数据库里，**磁盘上不会有 SKILL.md**：",
                "   不要用 `list_files` / `glob **/SKILL.md` 判断技能是否存在，只能看 `<available_skills>` 或调工具加载。");
        return base.isEmpty() ? guidance : base + "\n\n" + guidance;
    }

    /**
     * 在用户自己的系统提示词后面追加一段**交互卡片**使用指引。
     *
     * <p>为什么由平台注入而不是只靠技能：技能要用户手动绑定，且模型未必命中；
     * 而"该弹卡片时弹卡片"是平台的交互约定，应该对每个 Agent 默认生效。
     */
    static String withCardGuidance(String sysPrompt) {
        String base = sysPrompt == null ? "" : sysPrompt.trim();
        String guidance = String.join("\n",
                "## 交互卡片（本平台能力，请主动使用）",
                "你手上有两个工具可以在界面上弹卡片，用户点按钮即可回答，比在正文里提问更清楚：",
                "1. `demo_confirm_card` —— 需要用户确认或选择时**必须**用它，不要只在正文里问「是否继续」。",
                "   典型场景：不可逆/有风险的操作（删除、覆盖、发布、扣费、改生产数据）、多方案二选一、",
                "   缺少只有用户知道的信息且答案可枚举。question 要写清后果。",
                "2. `demo_mapping_card` —— 要展示字段映射、多行对照清单等结构化数据时用它。",
                "调用后用户会在界面上看到卡片；他的选择会作为后续消息回到你这里，**在收到之前不要假定他已同意**。");
        return base.isEmpty() ? guidance : base + "\n\n" + guidance;
    }

    /**
     * 在系统提示词后面追加一段**长期记忆**使用指引。
     *
     * <p>为什么必须由平台注入（实测，2026-10-20）：
     * 框架的 {@code memory_search} 把整条 {@code query} 当成**一个整体字面量**去匹配记忆文件的每一行
     * （{@code MemorySearchTool.keywordSearch}：{@code Pattern.compile(Pattern.quote(query))}），
     * 而模型习惯把问句整句填进去。实测同一条记忆：
     * <ul>
     *   <li>{@code query="怎么称呼我"} → {@code No matching memories found}（用户看到“它说没有找到”）</li>
     *   <li>{@code query="主人"} → {@code Found 37 matches}（其中就有“用户希望被称为「主人」”）</li>
     * </ul>
     * 记忆本身是好的（`memory_save` 写得进、`memory_context` 也注入了）；坏的是“怎么问”。
     * 所以这里把三件事讲明白：先看已注入的 memory_context、搜索只给短关键词、0 命中要换词再试。
     *
     * <p>不改框架的记忆检索逻辑（门禁 G8），只补提示词。
     */
    static String withMemoryGuidance(String sysPrompt) {
        String base = sysPrompt == null ? "" : sysPrompt.trim();
        String guidance = String.join("\n",
                "## 长期记忆（本平台能力，请正确使用）",
                "1. `<memory_context>` 里**已经注入**了 MEMORY.md 与最近的记忆台账 —— 回答“我叫什么/我的偏好/之前说好的事”",
                "   这类问题前**先看它**，不要一上来就搜索。",
                "2. `memory_search` 的 `query` 是**按字面量匹配整段文字**的：只传**短关键词**（如 `称呼`、`主人`、`偏好`、`报销`），",
                "   **绝对不要传整句问句**（传“用户平时怎么称呼我”会 0 命中）。",
                "3. 命中 0 条**不等于没有记忆**：换成更短或同义的关键词再试（`称呼` → `主人` → `叫`），",
                "   或直接用 `memory_get` 读 `MEMORY.md`。不要因为搜索为空就回答“没有相关记忆”。",
                "4. 命中后用返回的 `Source: <path#line>` 调 `memory_get` 取上下文，并在回答里注明来源。",
                "5. 用户让你“记住 / 以后都这样”时，调 `memory_save`；**不要**用 `write_file`/`edit_file` 改 MEMORY.md 或 memory/ 下的文件。");
        return base.isEmpty() ? guidance : base + "\n\n" + guidance;
    }

    /** 该会话模型的**有效**压缩触发阈值（与运行时一致：配置值与窗口 60% 取小）。 */
    public int effectiveCompactionTrigger(Long agentDbId) {
        Agent agent = agentDbId == null ? null : agentMapper.selectById(agentDbId);
        LlmModel model = (agent == null || agent.getModelId() == null)
                ? null : llmModelMapper.selectById(agent.getModelId());
        return compactionTriggerFor(model == null ? null : model.getContextWindow());
    }

    /**
     * 本会话模型生效的**上下文窗口**（长度）：模型配了就用配的，没配用兜底值。
     *
     * <p>前端用它做上下文用量条的分母（以前只给了压缩触发阈值，模型配的窗口在界面上完全没体现）。
     */
    public int effectiveContextWindow(Long agentDbId) {
        Agent agent = agentDbId == null ? null : agentMapper.selectById(agentDbId);
        LlmModel model = (agent == null || agent.getModelId() == null)
                ? null : llmModelMapper.selectById(agent.getModelId());
        return resolveContextWindow(
                model == null ? null : model.getContextWindow(), aguiProperties.contextWindowFallback());
    }

    /** 窗口解析：配了（正整数）就用配的，否则用兜底值。纯函数，便于单测。 */
    static int resolveContextWindow(Integer configured, int fallback) {
        return configured != null && configured > 0 ? configured : fallback;
    }

    /** 该 Agent 绑定的模型是否开启多模态（图片输入）；模型缺失或未开启都返回 false。 */
    public boolean isMultimodal(Long agentDbId) {
        Agent agent = agentDbId == null ? null : agentMapper.selectById(agentDbId);
        if (agent == null || agent.getModelId() == null) {
            return false;
        }
        LlmModel model = llmModelMapper.selectById(agent.getModelId());
        return model != null && Boolean.TRUE.equals(model.getMultimodal());
    }

    /**
     * 按 Agent 主键构建它使用的 ChatModel（标题生成等辅助能力复用同一套装配逻辑）。
     *
     * <p>与 doBuild 用的是同一份模型/供应商校验与参数合并，避免两处配置不一致。
     */
    public ChatModelBase buildChatModelFor(Long agentDbId) {
        Agent agent = agentMapper.selectById(agentDbId);
        if (agent == null) {
            throw BizException.notFound("Agent 不存在: " + agentDbId);
        }
        LlmModel model = agent.getModelId() == null ? null : llmModelMapper.selectById(agent.getModelId());
        if (model == null) {
            throw BizException.badRequest("Agent 未绑定有效模型: " + agent.getAgentId());
        }
        return buildChatModel(model.getId());
    }

    /**
     * 按**模型主键**直接构建 ChatModel（不经过 Agent）。
     *
     * <p>用途：平台自己发起的单次调用，比如会话标题生成、技能 AI 生成 —— 这类调用
     * 不需要（也不应该）借某个 Agent 的配置，只要模型 + 供应商即可。
     */
    public ChatModelBase buildChatModel(Long modelId) {
        LlmModel model = modelId == null ? null : llmModelMapper.selectById(modelId);
        if (model == null) {
            throw BizException.badRequest("模型不存在: " + modelId);
        }
        LlmProvider provider = llmProviderMapper.selectById(model.getProviderId());
        if (provider == null) {
            throw BizException.badRequest("模型对应的供应商不存在: " + model.getProviderId());
        }
        String apiKey = cryptoService.decrypt(provider.getApiKeyCipher());
        Map<String, Object> params = new java.util.HashMap<>(
                model.getParams() == null ? Map.of() : model.getParams());
        if (model.getTemperature() != null) {
            params.put("temperature", model.getTemperature());
        }
        if (model.getTopP() != null) {
            params.put("topP", model.getTopP());
        }
        if (model.getMaxTokens() != null) {
            params.put("maxTokens", model.getMaxTokens());
        }
        // 用流式：实测这个 OpenAI 兼容端点在「非流式」下解析不出文本（chat 路径同样是流式）。
        return modelFactory.create(
                provider.getProviderType(), provider.getBaseUrl(), apiKey, model.getModelId(), params, true);
    }

    /** 压缩触发阈值：配置值与「模型窗口的 60%」取小（模型未配窗口时用配置值）。 */
    private int compactionTriggerFor(Integer contextWindow) {
        int configured = aguiProperties.compactionTriggerTokens();
        if (contextWindow == null || contextWindow <= 0) {
            return configured;
        }
        int fromWindow = (int) (contextWindow * 0.6);
        return Math.max(1000, Math.min(configured, fromWindow));
    }

    /** 权限模式 → AgentScope 权限模式。 */
    static PermissionMode toPermissionMode(String platformMode) {
        String mode = platformMode == null ? "" : platformMode.trim().toUpperCase();
        return switch (mode) {
            case "ALWAYS_ALLOW" -> PermissionMode.BYPASS;
            case "ALWAYS_DENY" -> PermissionMode.DONT_ASK;
            default -> PermissionMode.DEFAULT;
        };
    }

    /** 长期记忆在（运行时数据根下的）目录名；路由到数据库的前缀。 */
    static final String MEMORY_ROUTE_PREFIX = "memory";

    /**
     * 构建权限上下文。
     *
     * <p>实测结论（Task 7.1）：仅把 mode 设为 DEFAULT **不会**触发审批——工具会直接执行。
     * 要真正拦下来，必须显式添加 {@link PermissionBehavior#ASK} 规则。
     *
     * <p>ask 规则直接取自 Agent 实际拥有的工具（{@link Toolkit#getToolNames()}），而不是仅靠
     * 同步过的工具缓存——否则同步未跑或失败时会漏拦。
     */
    static PermissionContextState buildPermissionContext(String platformMode, Toolkit toolkit) {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(toPermissionMode(platformMode));
        if (!"REQUIRE_APPROVAL".equalsIgnoreCase(platformMode == null ? "" : platformMode.trim())) {
            return builder.build();
        }
        java.util.Set<String> toolNames = new java.util.LinkedHashSet<>();
        toolNames.add("*");
        if (toolkit != null) {
            toolNames.addAll(toolkit.getToolNames());
        }
        for (String toolName : toolNames) {
            builder.addAskRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.ASK, "myagent"));
        }
        return builder.build();
    }

    @Override
    public void invalidate(Long agentDbId) {
        // 组合缓存键形如 "12" 或 "12|3,5"：两类都要清
        String exact = String.valueOf(agentDbId);
        String prefix = agentDbId + "|";
        cache.keySet().stream()
                .filter(k -> k.equals(exact) || k.startsWith(prefix))
                .toList()
                .forEach(k -> closeQuietly(cache.remove(k)));
    }

    @Override
    public void invalidateAll() {
        // 缓存键现在是组合键（agent id 或 agent id + 技能组合），直接整体清空
        cache.keySet().forEach(k -> closeQuietly(cache.remove(k)));
    }

    private static void closeQuietly(HarnessAgent agent) {
        if (agent == null) {
            return;
        }
        try {
            agent.close();
        } catch (Exception ex) {
            log.warn("关闭 HarnessAgent 失败: {}", ex.getMessage());
        }
    }

    @Override
    public void close() {
        invalidateAll();
    }
}
