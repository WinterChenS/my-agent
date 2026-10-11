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
  static String dbHost() { return env("DB_HOST", dbHost()); }
  static String dbUser() { return env("DB_USERNAME", "agent"); }
  static String dbPassword() { return env("DB_PASSWORD", "change-me"); }
  static String jdbcUrl() {
    return "jdbc:postgresql://" + dbHost() + ":5432/" + env("DB_NAME", "agent_platform");
  }

    static final String KEEP_AGENT = "stub-demo-agent";
    static final String KEEP_PROVIDER = "deepseek";
    static final String KEEP_MODEL_PREFIX = "deepseek/";
    static final String[] TEST_AGENT_PREFIXES = {
        "p3-", "p5-", "p6-", "p7-", "p7b-", "p7c-", "p7d-", "p8-", "p9-", "p10-", "p11-", "p11b-", "p11c-", "p12-",
        "e2e-", "verifier-", "ns-agent-", "a1-", "a5-", "a9-", "v2-", "TestV2",
        "skill-test-agent", "dbg-", "factory-", "rollback-", "demo-agent-", "Agent-", "E2E-", "Manual-", "vcontract-", "vdc-", "vdc2-"
    };
    static final String[] TEST_PROVIDER_PREFIXES = {
        "P3-", "P5-", "P6-", "P7-", "P72-", "P8-", "P9-", "P10-", "P11", "P12-", "E2E-", "D1-", "DBG-",
        "FactoryTest-", "AgentTest-", "SkillTest-", "rollback-", "TestV2-", "ns-prov-", "A1-", "A5-", "V2-", "Verifier-", "示例",
        "Test-", "TestI2-", "V-Prov-", "V-Bad-", "e2e-p10-prov-", "Manual-Provider-", "VContract-", "VDC-", "VDC2-", "vdc-", "vdc2-", "ns-", "A1-", "A5-"
    };

    static boolean hasPrefix(String value, String[] prefixes) {
        if (value == null) return false;
        for (String p : prefixes) if (value.startsWith(p)) return true;
        return false;
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
                        try (PreparedStatement d = c.prepareStatement("delete from chat_session where agent_id = ?")) {
                            d.setLong(1, id); sessions += d.executeUpdate();
                        }
                        try (PreparedStatement d = c.prepareStatement("delete from agent_state where agent_id in (select user_id::text from chat_session where agent_id = ?)")) {
                            d.setLong(1, id); states += d.executeUpdate();
                        }
                        try (PreparedStatement d = c.prepareStatement("delete from agent where id = ?")) {
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
            String[] titlePrefixes = {"p5-", "p6-", "p7-", "p8-", "p9-", "p10 ", "p11", "p12 ", "p72-", "verifier-", "e2e-"};
            int straySessions = 0;
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select id, coalesce(title,'') from chat_session")) {
                while (r.next()) {
                    long id = r.getLong(1);
                    String title = r.getString(2);
                    if (!hasPrefix(title, titlePrefixes)) continue;
                    straySessions++;
                    if (apply) {
                        try (PreparedStatement d = c.prepareStatement("delete from chat_session where id = ?")) {
                            d.setLong(1, id); d.executeUpdate();
                        }
                    }
                }
            }

            System.out.println("  模式=" + (apply ? "删除" : "预览"));
            System.out.println("  测试 agent=" + agents + "，其会话=" + sessions + "，agent_state 清理=" + states);
            System.out.println("  测试 provider=" + providers + "（连带 model=" + models + "）");
            System.out.println("  残留测试会话=" + straySessions);
            try (Statement s = c.createStatement()) {
                for (String t : new String[]{"agent", "chat_session", "llm_provider", "llm_model", "artifact", "agent_state", "chat_message", "token_usage"}) {
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
