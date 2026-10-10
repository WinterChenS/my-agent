package com.myagent.agentconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.agentconfig.entity.Agent;
import org.apache.ibatis.annotations.Mapper;

/** agent 数据访问。 */
@Mapper
public interface AgentMapper extends BaseMapper<Agent> {}
