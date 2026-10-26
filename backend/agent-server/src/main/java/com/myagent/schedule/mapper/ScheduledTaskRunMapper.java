package com.myagent.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.schedule.entity.ScheduledTaskRun;
import org.apache.ibatis.annotations.Mapper;

/** 定时任务执行记录。 */
@Mapper
public interface ScheduledTaskRunMapper extends BaseMapper<ScheduledTaskRun> {}
