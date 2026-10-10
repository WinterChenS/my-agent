package com.myagent.mcpconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.mcpconfig.entity.McpServer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** mcp_server 数据访问。 */
@Mapper
public interface McpServerMapper extends BaseMapper<McpServer> {

    /** 该 Server 被多少个 Agent 绑定（删除前校验）。 */
    @Select("SELECT count(*) FROM agent_mcp_server WHERE mcp_server_id = #{id}")
    long countBindings(@Param("id") Long id);
}
