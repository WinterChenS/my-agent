package com.myagent.agentconfig.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * agent_skill 关联表访问。
 *
 * <p>复合主键表刻意不使用 {@code BaseMapper}（MyBatis-Plus 需要单一主键），改为显式 SQL。
 */
@Mapper
public interface AgentSkillMapper {

    @Insert("INSERT INTO agent_skill (agent_id, skill_id) VALUES (#{agentId}, #{skillId})")
    int insertLink(@Param("agentId") Long agentId, @Param("skillId") Long skillId);

    @Delete("DELETE FROM agent_skill WHERE agent_id = #{agentId}")
    int deleteByAgentId(@Param("agentId") Long agentId);

    @Select("SELECT skill_id FROM agent_skill WHERE agent_id = #{agentId} ORDER BY skill_id")
    List<Long> findSkillIds(@Param("agentId") Long agentId);

    /** 该技能被多少个 Agent 绑定（0 = 导入后还没绑，对话里用不了）。 */
    @Select("SELECT count(*) FROM agent_skill WHERE skill_id = #{skillId}")
    long countAgentsBySkillId(@Param("skillId") Long skillId);

    /** 批量：技能 id → 绑定它的 Agent 数量（列表页用，避免 N+1）。 */
    @Select({
        "<script>",
        "SELECT skill_id, count(*) AS cnt FROM agent_skill WHERE skill_id IN",
        "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
        "GROUP BY skill_id",
        "</script>"
    })
    java.util.List<java.util.Map<String, Object>> countAgentsBySkillIds(@Param("ids") java.util.List<Long> ids);

    /** 绑定前校验 Skill 是否存在。 */
    @Select("SELECT count(*) FROM skill WHERE id = #{skillId}")
    long countExistingSkill(@Param("skillId") Long skillId);
}
