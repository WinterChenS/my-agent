package com.myagent.schedule.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 执行记录的跨任务查询（日历 + 时间轴）。 */
@Mapper
public interface ScheduledTaskRunQueryMapper {

    /** 按天聚合：总数 / 成功 / 失败 / 取消 / 仍在跑（用于日历着色）。 */
    @Select("""
            SELECT to_char(r.started_at AT TIME ZONE #{zone}, 'YYYY-MM-DD') AS day,
                   count(*)                                               AS total,
                   count(*) FILTER (WHERE r.status = 'SUCCESS')            AS success,
                   count(*) FILTER (WHERE r.status IN ('FAILED','ABANDONED')) AS failed,
                   count(*) FILTER (WHERE r.status = 'CANCELLED')          AS cancelled,
                   count(*) FILTER (WHERE r.status = 'RUNNING')            AS running
              FROM scheduled_task_run r
              JOIN scheduled_task t ON t.id = r.task_id
             WHERE r.started_at >= #{from} AND r.started_at < #{to}
               AND (#{owner} IS NULL OR t.created_by_user_id = #{owner})
             GROUP BY 1
             ORDER BY 1
            """)
    List<Map<String, Object>> summaryByDay(
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("zone") String zone,
            @Param("owner") Long owner);

    /** 时间轴：某个时间窗内的执行记录（带任务名），按时间倒序。 */
    @Select("""
            SELECT r.id, r.task_id, t.name AS task_name, r.trigger_type, r.status,
                   r.started_at, r.finished_at, r.duration_ms, r.output_preview, r.error, r.node_id
              FROM scheduled_task_run r
              JOIN scheduled_task t ON t.id = r.task_id
             WHERE r.started_at >= #{from} AND r.started_at < #{to}
               AND (#{owner} IS NULL OR t.created_by_user_id = #{owner})
             ORDER BY r.started_at DESC, r.id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> timeline(
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("owner") Long owner,
            @Param("limit") int limit);

    /** 取一条执行记录的任务名（取消接口回显用）。 */
    @Select("SELECT t.name FROM scheduled_task_run r JOIN scheduled_task t ON t.id = r.task_id WHERE r.id = #{runId}")
    String taskNameOfRun(@Param("runId") Long runId);
}
