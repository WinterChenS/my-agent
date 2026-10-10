package com.myagent.skillconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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

    /** 所有启用中的 Skill 名称，供同步到官方仓库。 */
    @Select("SELECT name FROM skill WHERE status = 'ENABLED' ORDER BY name")
    List<String> findEnabledNames();
}
