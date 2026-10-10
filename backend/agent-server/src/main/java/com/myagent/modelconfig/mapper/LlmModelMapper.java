package com.myagent.modelconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.modelconfig.entity.LlmModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** llm_model 数据访问。 */
@Mapper
public interface LlmModelMapper extends BaseMapper<LlmModel> {

    /** 统计引用该模型的 Agent 数量，用于删除前校验。 */
    @Select("SELECT count(*) FROM agent WHERE model_id = #{modelId}")
    long countAgentsUsing(@Param("modelId") Long modelId);
}
