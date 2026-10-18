package com.myagent.agentconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.agentconfig.entity.AgentUser;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** agent_user 白名单访问（按资源授权）。 */
@Mapper
public interface AgentUserMapper extends BaseMapper<AgentUser> {

    /** 该 Agent 允许的用户（空 = 对所有人开放）。 */
    @Select("SELECT user_id FROM agent_user WHERE agent_id = #{agentId} ORDER BY user_id")
    List<Long> findUserIds(@Param("agentId") Long agentId);

    /** 该用户被**显式授权**的 Agent（用于非管理员列 Agent 时的过滤）。 */
    @Select("SELECT agent_id FROM agent_user WHERE user_id = #{userId}")
    List<Long> findAgentIds(@Param("userId") Long userId);

    /** 有白名单限制的 Agent（非管理员列 Agent 时，这些也要一并考虑）。 */
    @Select("SELECT DISTINCT agent_id FROM agent_user")
    List<Long> restrictedAgentIds();

    default void deleteByAgent(Long agentId) {
        delete(Wrappers.<AgentUser>lambdaQuery().eq(AgentUser::getAgentId, agentId));
    }
}
