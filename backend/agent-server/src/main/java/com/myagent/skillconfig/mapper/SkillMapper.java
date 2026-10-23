package com.myagent.skillconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.myagent.skillconfig.entity.Skill;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** skill 数据访问。 */
@Mapper
public interface SkillMapper extends BaseMapper<Skill> {

    /** 该 Skill 被多少个 Agent 绑定（删除前校验）。 */
    @Select("SELECT count(*) FROM agent_skill WHERE skill_id = #{skillId}")
    long countBindings(@Param("skillId") Long skillId);

    /** 按 id 集合取名称，供 AgentFactory 组装 SkillFilter。 */
    @Select({
        "<script>",
        "SELECT name FROM skill WHERE id IN",
        "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
        "ORDER BY name",
        "</script>"
    })
    List<String> findNamesByIds(@Param("ids") List<Long> ids);

    /**
     * 某 Agent **能用的**技能（= 绑定到它的），用于聊天输入框的 `/` 候选。
     *
     * <p>为什么必须按绑定过滤：模型的 {@code <available_skills>} 只包含绑定的技能，
     * 未绑定的技能在对话里**根本调不到**。列表里还显示它，就是在骗用户
     * （实测用户反馈：`/` 里出现了当前 Agent 根本没绑定的技能）。
     */
    @Select({
        "<script>",
        "SELECT s.* FROM skill s JOIN agent_skill ags ON ags.skill_id = s.id",
        "WHERE ags.agent_id = #{agentId}",
        "<if test='status != null and status != \"\"'> AND s.status = #{status}</if>",
        "ORDER BY s.id",
        "</script>"
    })
    IPage<Skill> selectBoundByAgent(
            IPage<Skill> page, @Param("agentId") Long agentId, @Param("status") String status);

    /** 所有启用中的 Skill 名称，供同步到官方仓库。 */
    @Select("SELECT name FROM skill WHERE status = 'ENABLED' ORDER BY name")
    List<String> findEnabledNames();
}
