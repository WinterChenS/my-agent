package com.myagent.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.schedule.entity.ScheduledTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 定时任务数据访问（含分布式抢占 SQL）。 */
@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {

    /**
     * 抢占「该跑且没被租」的任务。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 是多副本安全的关键：多个节点同时 tick 时，
     * 每个节点只会拿到**别人没有锁住**的行，绝不会重复执行同一条任务。
     * 不引入任何额外组件，只用现有 PostgreSQL。
     */
    @Select("""
            SELECT * FROM scheduled_task
             WHERE status = 'ACTIVE' AND enabled = TRUE
               AND next_run_at IS NOT NULL AND next_run_at <= now()
               AND (lease_until IS NULL OR lease_until < now())
             ORDER BY next_run_at
             FOR UPDATE SKIP LOCKED
             LIMIT #{limit}
            """)
    List<ScheduledTask> lockDueTasks(@Param("limit") int limit);

    /** 租约过期仍停留在 RUNNING 的执行记录（节点崩溃留下的），标记为 ABANDONED。 */
    @Update("""
            UPDATE scheduled_task_run r
               SET status = 'ABANDONED', finished_at = now(),
                   error = COALESCE(r.error, '节点异常退出，执行未完成')
             WHERE r.status = 'RUNNING'
               AND r.started_at < now() - make_interval(mins => #{staleMinutes})
            """)
    int abandonStaleRuns(@Param("staleMinutes") int staleMinutes);

    /** 清理租约（任务行）：租约过期即可被别人抢。 */
    @Update("""
            UPDATE scheduled_task
               SET lease_until = NULL
             WHERE status = 'ACTIVE' AND lease_until IS NOT NULL AND lease_until < now()
            """)
    int releaseExpiredLeases();
}
