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
            SELECT model_id                            AS model_id,
                   coalesce(sum(prompt_tokens), 0)     AS prompt_tokens,
                   coalesce(sum(completion_tokens), 0) AS completion_tokens,
                   coalesce(sum(total_tokens), 0)      AS total_tokens
            FROM token_usage
            WHERE user_id = #{userId}
              AND created_at >= #{from}
              AND created_at <= #{to}
            GROUP BY model_id
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
}
