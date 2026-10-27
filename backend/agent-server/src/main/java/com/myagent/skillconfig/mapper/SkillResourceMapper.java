package com.myagent.skillconfig.mapper;

import com.myagent.skillconfig.entity.SkillResource;
import com.myagent.skillconfig.entity.SkillResourceCount;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code skill_resource} 访问。
 *
 * <p>复合主键表刻意不使用 {@code BaseMapper}（MyBatis-Plus 需要单一主键），改为显式 SQL。
 */
@Mapper
public interface SkillResourceMapper {

    /** 某 Skill 的全部资源（按路径排序，便于稳定输出与比对）。 */
    @Select("SELECT skill_id, resource_path, resource_content FROM skill_resource "
            + "WHERE skill_id = #{skillId} ORDER BY resource_path")
    List<SkillResource> findBySkillId(@Param("skillId") Long skillId);

    /** 批量取多个 Skill 的资源（同步到官方仓库时一次取完，避免 N+1）。 */
    @Select({
        "<script>",
        "SELECT skill_id, resource_path, resource_content FROM skill_resource WHERE skill_id IN",
        "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
        "ORDER BY skill_id, resource_path",
        "</script>"
    })
    List<SkillResource> findBySkillIds(@Param("ids") List<Long> ids);

    /** 资源数量（单个 Skill）。 */
    @Select("SELECT count(*) FROM skill_resource WHERE skill_id = #{skillId}")
    int countBySkillId(@Param("skillId") Long skillId);

    /** 资源数量（批量，列表页用，避免 N+1）。 */
    @Select({
        "<script>",
        "SELECT skill_id, count(*) AS resource_count FROM skill_resource WHERE skill_id IN",
        "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
        "GROUP BY skill_id",
        "</script>"
    })
    List<SkillResourceCount> countBySkillIds(@Param("ids") List<Long> ids);

    @Insert("INSERT INTO skill_resource (skill_id, resource_path, resource_content) "
            + "VALUES (#{skillId}, #{resourcePath}, #{resourceContent})")
    int insertOne(
            @Param("skillId") Long skillId,
            @Param("resourcePath") String resourcePath,
            @Param("resourceContent") String resourceContent);

    @Delete("DELETE FROM skill_resource WHERE skill_id = #{skillId}")
    int deleteBySkillId(@Param("skillId") Long skillId);
}
