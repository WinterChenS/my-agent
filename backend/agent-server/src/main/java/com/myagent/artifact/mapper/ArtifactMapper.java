package com.myagent.artifact.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.artifact.entity.Artifact;
import org.apache.ibatis.annotations.Mapper;

/** artifact 数据访问。 */
@Mapper
public interface ArtifactMapper extends BaseMapper<Artifact> {}
