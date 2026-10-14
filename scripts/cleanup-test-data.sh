#!/usr/bin/env bash
# 清理实施期验收脚本留下的测试数据（默认只预览；加 --yes 才执行）。
#
# 为什么需要：AgentScope/AG-UI 的验收脚本每跑一次都会新建 provider/model/agent/会话，
# 几十轮下来会把聊天页左侧塞满（用户实测反馈过）。
#
# 保留（白名单，绝不动）：
#   - 用户自己的 agent：stub-demo-agent
#   - 用户自己的供应商/模型：deepseek / deepseek-deepseek-flash
#   - 用户自己的会话、消息、上传文件与产物
# 删除：
#   - 除白名单外、且命中测试前缀的 agent / provider / model / 会话（会话随 agent 级联）
#   - 对应的 agent_state 行
#   - 工作区里属于这些测试 agent 的目录
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APPLY="${1:-}"
DB="agent_platform"
# 凭据从环境变量读取（不要把真实口令写进仓库）
DB_HOST="${DB_HOST:-localhost}"
DB_USER="${DB_USERNAME:-agent}"
DB_PASS="${DB_PASSWORD:?请先设置环境变量 DB_PASSWORD}"
JDK="/c/Program Files/Java/jdk-21.0.3+9/bin"
JAR="C:\\Users\\Administrator\\.m2\\repository\\org\\postgresql\\postgresql\\42.7.11\\postgresql-42.7.11.jar"

[ "$APPLY" = "--yes" ] && MODE="删除" || MODE="预览"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cat > "$WORK/Clean.java" <<'JEOF'
import java.sql.*;

/** 只保留白名单；其余命中测试前缀的 agent/provider/model/会话按 MODE 处理。 */
public class Clean {

  // 凭据从环境变量读取（不要把真实口令写进仓库）
  static String env(String key, String fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }
  static String dbHost() { return env("DB_HOST", "localhost"); }
  static String dbUser() { return env("DB_USERNAME", "agent"); }
  static String dbPassword() { return env("DB_PASSWORD", "change-me"); }
  static String jdbcUrl() {
    // 优先用完整 DB_URL（.env 里通常这么配）；否则用 DB_HOST/DB_NAME 拼
    String url = System.getenv("DB_URL");
    if (url != null && !url.isBlank()) return url;
    return "jdbc:postgresql://" + dbHost() + ":5432/" + env("DB_NAME", "agent_platform")
        + "?stringtype=unspecified";
  }

    static final String KEEP_AGENT = "stub-demo-agent";
    static final String KEEP_PROVIDER = "deepseek";
    static final String KEEP_MODEL_PREFIX = "deepseek/";
    static final String[] TEST_AGENT_PREFIXES = {
        "p3-", "p5-", "p6-", "p7-", "p7b-", "p7c-", "p7d-", "p8-", "p9-", "p10-", "p11-", "p11b-", "p11c-", "p12-", "rc-", "rc2-", "rc4-", "rv-", "k-agent-", "p15-", "p15-agent-", "p12-", "rr-", "mp-model-", "rp-",
        "e2e-", "verifier-", "ns-agent-", "a1-", "a5-", "a9-", "v2-", "TestV2",
        "skill-test-agent", "dbg-", "factory-", "rollback-", "demo-agent-", "Agent-", "E2E-", "Manual-", "vcontract-", "vdc-", "vdc2-", "mp-agent-", "rr-agent-", "c-agent-", "k-agent-", "rv-agent-", "rc-agent-", "rc2-agent-", "rc4-agent-", "P15-", "p15-agent-", "MP-", "RR-", "t1v-"
    };
    static final String[] TEST_PROVIDER_PREFIXES = {
        "P3-", "P5-", "P6-", "P7-", "P72-", "P8-", "P9-", "P10-", "P11", "P12-", "E2E-", "D1-", "DBG-", "RC-", "RC2-", "RC4-", "RV-", "K-Prov-",
        "FactoryTest-", "AgentTest-", "SkillTest-", "rollback-", "TestV2-", "ns-prov-", "A1-", "A5-", "V2-", "Verifier-", "示例",
        "Test-", "TestI2-", "V-Prov-", "V-Bad-", "e2e-p10-prov-", "Manual-Provider-", "VContract-", "VDC-", "VDC2-", "vdc-", "vdc2-", "ns-", "A1-", "A5-",
        "e2e-ai-prov-", "e2e-cb-prov-", "e2e-chat-prov-", "quota-prov-", "cat-server-", "cat-disabled-", "cat-empty-", "cat-mix-",
        "MM-", "VContract-", "VDC-", "e2e-prov-", "e2e-codeblock-", "e2e-page-", "e2e-mm-prov-", "t1v-prov-", "t1v-"
    };

    /** 测试建的 MCP 服务器（历次 e2e 遗留最多的一类：本机曾有 42 个）。 */
    static final String[] TEST_MCP_PREFIXES = {
        "e2e-", "p3-mcp-", "p5-mcp-", "p6-mcp-", "p7-mcp-", "p7b-mcp-", "p8-mcp-", "p9-mcp-", "p10-mcp-", "p11-mcp-", "p12-mcp-",
        "ns-mcp-", "mcp-everything-", "mcp-bad-", "mcp-stdio-", "mcp-sse-", "mcp-http-", "MCP-", "cat-server-", "cat-disabled-", "cat-empty-", "cat-mix-",
        "VContract-", "e2e-page-"
    };

    /** 测试建的模型（App 直接清：否则 provider 因"还有 model 引用它"删不掉，孤儿模型会一直留在库里）。 */
    static final String[] TEST_MODEL_PREFIXES = {
        "stub-model-", "mm-model-", "p17-model-", "uitest-", "mp-model-", "rr-model-", "e2e-p10-model-", "e2e-model-", "quota-model-", "stub-skill-json-", "stub-code-fence-", "e2e-mm-model-", "t1v-mm", "t1v-nomm"
    };

    /** 测试建的技能（本人用 AI/手建技能时也会造）。 */
    static final String[] TEST_SKILL_PREFIXES = {
        "e2e-", "P5-", "p5-", "p7-", "skill-a-", "skill-b-", "skill-test", "skill-nodesc-", "P8-", "P9-", "P10-", "P11-", "P12-",
        "mp-skill-", "rr-skill-", "mdx-verify-", "ai-e2e-", "dbg-", "Manual-", "Test-", "Demo-",
        "VContract-", "VDC-", "A9-", "confirm-card-flow", "p9-skill-", "p10-skill-", "p11-skill-", "p12-skill-"
    };

    static boolean hasPrefix(String value, String[] prefixes) {
        if (value == null) return false;
        for (String p : prefixes) if (value.startsWith(p)) return true;
        // 通用测试命名：p3-/p5-/p9-/p10-/p15- 这类（历次阶段脚本都这么起名）
        return value.matches("(?i)^[pav]\\d+[-_].*");
    }

    public static void main(String[] args) throws Exception {
        boolean apply = args.length > 0 && "--yes".equals(args[0]);
        String url = jdbcUrl();
        try (Connection c = DriverManager.getConnection(url, dbUser(), dbPassword())) {
            c.setAutoCommit(true);
            int agents = 0, providers = 0, models = 0, sessions = 0, states = 0;

            // 1) 测试 agent（会话随外键级联删除）
            StringBuilder killedAgents = new StringBuilder();
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select id, agent_id from agent order by id")) {
                while (r.next()) {
                    long id = r.getLong(1);
                    String agentId = r.getString(2);
                    if (KEEP_AGENT.equals(agentId)) continue;
                    if (!hasPrefix(agentId, TEST_AGENT_PREFIXES)) {
                        System.out.println("  [跳过] 非测试前缀的 agent: " + agentId);
                        continue;
                    }
                    agents++;
                    killedAgents.append(agentId).append(' ');
                    if (apply) {
                        // agent_state 的 session_id 就是会话的 thread_id，按它删才准。
                        // （原来写的是 agent_id in (select user_id::text ...)，条件本身是错的，会留下垃圾行）
                        try (PreparedStatement d = c.prepareStatement(
                                "delete from agent_state where session_id in "
                              + "(select thread_id from chat_session where agent_id = ?)")) {
                            d.setLong(1, id); states += d.executeUpdate();
                        }
                        try (PreparedStatement d = c.prepareStatement("delete from chat_session where agent_id = ?")) {
                            d.setLong(1, id); sessions += d.executeUpdate();
                        }
                        try (PreparedStatement d = c.prepareStatement("delete from agent where id = ?")) {
                            d.setLong(1, id); d.executeUpdate();
                        }
                    }
                }
            }

            // 1.5) 测试模型：只删没有被任何 agent 引用的（有引用的留给 agent 清理后再处理）
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select id, model_id from llm_model order by id")) {
                while (r.next()) {
                    long id = r.getLong(1);
                    String modelId = r.getString(2);
                    if (!hasPrefix(modelId, TEST_MODEL_PREFIXES)) {
                        continue;
                    }
                    models++;
                    if (apply) {
                        try (PreparedStatement d = c.prepareStatement(
                                "delete from llm_model m where m.id = ? "
                              + "and not exists (select 1 from agent a where a.model_id = m.id)")) {
                            d.setLong(1, id); d.executeUpdate();
                        }
                    }
                }
            }

            // 2) 测试 provider（先解绑/删其模型）
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select id, name from llm_provider order by id")) {
                while (r.next()) {
                    long id = r.getLong(1);
                    String name = r.getString(2);
                    if (KEEP_PROVIDER.equals(name)) continue;
                    if (!hasPrefix(name, TEST_PROVIDER_PREFIXES)) {
                        System.out.println("  [跳过] 非测试前缀的 provider: " + name);
                        continue;
                    }
                    providers++;
                    if (apply) {
                        try (PreparedStatement d = c.prepareStatement(
                                "delete from llm_model m where m.provider_id = ? "
                              + "and not exists (select 1 from agent a where a.model_id = m.id)")) {
                            d.setLong(1, id); models += d.executeUpdate();
                        }
                        try (PreparedStatement d = c.prepareStatement(
                                "delete from llm_provider p where p.id = ? "
                              + "and not exists (select 1 from llm_model m where m.provider_id = p.id)")) {
                            d.setLong(1, id); d.executeUpdate();
                        }
                    }
                }
            }

            // 3) 残留的测试会话（标题前缀；不动「会话 xxxx」这类用户会话）
            String[] titlePrefixes = {
                "p5-", "p6-", "p7-", "p8-", "p9-", "p10 ", "p11", "p12 ", "p72-", "verifier-", "e2e-",
                // 近几轮 e2e 造的会话标题（它们会把侧边栏塞满，也会把「扫会话」类用例挤红）
                "验证提示噪音", "请用 ", "后台跑一轮", "带技能发一条", "run-persist-test", "quota-test",
                "img-limit-test", "img-fix-verify", "card-proof", "verify-ctx", "probe-title", "mm-test", "mm-off",
            };
            int straySessions = 0;
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select id, coalesce(title,'') from chat_session")) {
                while (r.next()) {
                    long id = r.getLong(1);
                    String title = r.getString(2);
                    if (!hasPrefix(title, titlePrefixes)) continue;
                    straySessions++;
                    if (apply) {
                        // 会话删了，它的框架状态也得删（否则那些行会永远留在库里）
                        try (PreparedStatement d = c.prepareStatement(
                                "delete from agent_state where session_id in "
                              + "(select thread_id from chat_session where id = ?)")) {
                            d.setLong(1, id); states += d.executeUpdate();
                        }
                        try (PreparedStatement d = c.prepareStatement("delete from chat_session where id = ?")) {
                            d.setLong(1, id); d.executeUpdate();
                        }
                    }
                }
            }

            // 4) 测试建的 MCP 服务器（历次 e2e 遗留最多的一类）
            int mcpServers = 0;
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select id, name from mcp_server order by id")) {
                while (r.next()) {
                    long id = r.getLong(1);
                    String name = r.getString(2);
                    if (!hasPrefix(name, TEST_MCP_PREFIXES)) {
                        System.out.println("  [跳过] 非测试前缀的 MCP 服务器: " + name);
                        continue;
                    }
                    mcpServers++;
                    if (apply) {
                        try (PreparedStatement d = c.prepareStatement("delete from mcp_server where id = ?")) {
                            d.setLong(1, id); d.executeUpdate();
                        }
                    }
                }
            }

            // 5) 测试建的技能（先解绑，再删）
            int skills = 0;
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select id, name from skill order by id")) {
                while (r.next()) {
                    long id = r.getLong(1);
                    String name = r.getString(2);
                    if (!hasPrefix(name, TEST_SKILL_PREFIXES)) {
                        System.out.println("  [跳过] 非测试前缀的技能: " + name);
                        continue;
                    }
                    skills++;
                    if (apply) {
                        try (PreparedStatement d = c.prepareStatement("delete from agent_skill where skill_id = ?")) {
                            d.setLong(1, id); d.executeUpdate();
                        }
                        try (PreparedStatement d = c.prepareStatement("delete from skill where id = ?")) {
                            d.setLong(1, id); d.executeUpdate();
                        }
                    }
                }
            }

            System.out.println("  模式=" + (apply ? "删除" : "预览"));
            System.out.println("  测试 agent=" + agents + "，其会话=" + sessions + "，agent_state 清理=" + states);
            System.out.println("  测试 provider=" + providers + "（连带 model=" + models + "）");
            System.out.println("  残留测试会话=" + straySessions);
            System.out.println("  测试 MCP 服务器=" + mcpServers + "，测试技能=" + skills);
            try (Statement s = c.createStatement()) {
                for (String t : new String[]{"agent", "chat_session", "llm_provider", "llm_model", "artifact", "agent_state", "chat_message", "token_usage", "mcp_server", "skill"}) {
                    ResultSet x = s.executeQuery("select count(*) from " + t); x.next();
                    System.out.println("  剩余 " + t + "=" + x.getInt(1));
                }
                ResultSet k = s.executeQuery("select agent_id from agent");
                StringBuilder keep = new StringBuilder();
                while (k.next()) keep.append(k.getString(1)).append(' ');
                System.out.println("  保留的 agent: " + keep);
            }
        }
    }
}
JEOF
[ "$APPLY" = "--yes" ] && ARG="--yes" || ARG=""
"$JDK/java" -cp "$JAR" "$WORK/Clean.java" $ARG
echo
echo "（$MODE 完成。确认无误后可执行：bash scripts/cleanup-test-data.sh --yes）"
