package com.myagent.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.audit.entity.TokenUsage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** token_usage 数据访问与聚合查询（聚合走 SQL，不在应用层算）。 */
@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsage> {

    @Select("""
            SELECT coalesce(sum(prompt_tokens), 0)     AS prompt_tokens,
                   coalesce(sum(completion_tokens), 0) AS completion_tokens,
                   coalesce(sum(total_tokens), 0)      AS total_tokens
            FROM token_usage
            WHERE user_id = #{userId}
              AND created_at >= #{from}
              AND created_at <= #{to}
            """)
    Map<String, Object> totals(
            @Param("userId") Long userId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    @Select("""
            SELECT t.model_id                          AS model_id,
                   coalesce(sum(t.prompt_tokens), 0)     AS prompt_tokens,
                   coalesce(sum(t.completion_tokens), 0) AS completion_tokens,
                   coalesce(sum(t.total_tokens), 0)      AS total_tokens,
                   -- Phase 15：价格生效——按模型价格（每百万 token）算成本，货币取模型配置
                   m.price_currency                     AS currency,
                   m.price_input                        AS price_input,
                   m.price_output                       AS price_output,
                   coalesce(sum(
                       t.prompt_tokens     / 1000000.0 * m.price_input
                     + t.completion_tokens / 1000000.0 * m.price_output
                   ), 0)                                AS cost_total
            FROM token_usage t
            LEFT JOIN llm_model m ON m.id = t.model_id
            WHERE t.user_id = #{userId}
              AND t.created_at >= #{from}
              AND t.created_at <= #{to}
            GROUP BY t.model_id, m.price_currency, m.price_input, m.price_output
            ORDER BY total_tokens DESC
            """)
    List<Map<String, Object>> byModel(
            @Param("userId") Long userId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    @Select("""
            SELECT to_char(created_at, 'YYYY-MM-DD')    AS day,
                   coalesce(sum(prompt_tokens), 0)     AS prompt_tokens,
                   coalesce(sum(completion_tokens), 0) AS completion_tokens,
                   coalesce(sum(total_tokens), 0)      AS total_tokens
            FROM token_usage
            WHERE user_id = #{userId}
              AND created_at >= #{from}
              AND created_at <= #{to}
            GROUP BY to_char(created_at, 'YYYY-MM-DD')
            ORDER BY day
            """)
    List<Map<String, Object>> byDay(
            @Param("userId") Long userId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /** 有消耗的用户列表（管理员看全部）。 */
    @Select("""
            SELECT u.id            AS user_id,
                   u.username      AS username,
                   u.display_name  AS display_name,
                   coalesce(sum(t.total_tokens), 0) AS total_tokens
            FROM token_usage t
            JOIN sys_user u ON u.id = t.user_id
            GROUP BY u.id, u.username, u.display_name
            ORDER BY total_tokens DESC
            """)
    List<Map<String, Object>> usersWithUsage();

    /**
     * 指定区间内的成本合计（tier1-upgrades ④ 配额用）。
     *
     * <p>口径与 {@link #byModel} 完全一致（每百万 token 单价 × 用量），只是这里不分模型、直接求和；
     * {@code agentId} 为 null 时只按用户汇总，否则再叠加 Agent 维度。
     */
    @Select("""
            <script>
            SELECT coalesce(sum(
                       t.prompt_tokens     / 1000000.0 * coalesce(m.price_input, 0)
                     + t.completion_tokens / 1000000.0 * coalesce(m.price_output, 0)
                   ), 0) AS cost_total
            FROM token_usage t
            LEFT JOIN llm_model m ON m.id = t.model_id
            WHERE t.user_id = #{userId}
              AND t.created_at &gt;= #{from}
              AND t.created_at &lt;= #{to}
              <if test="agentId != null"> AND t.agent_id = #{agentId} </if>
            </script>
            """)
    java.math.BigDecimal costInRange(
            @Param("userId") Long userId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("agentId") Long agentId);
}