package com.myagent.agentconfig.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** agent_mcp_server 关联表访问。 */
@Mapper
public interface AgentMcpServerMapper {

    @Insert("INSERT INTO agent_mcp_server (agent_id, mcp_server_id) VALUES (#{agentId}, #{mcpServerId})")
    int insertLink(@Param("agentId") Long agentId, @Param("mcpServerId") Long mcpServerId);

    @Delete("DELETE FROM agent_mcp_server WHERE agent_id = #{agentId}")
    int deleteByAgentId(@Param("agentId") Long agentId);

    @Select("SELECT mcp_server_id FROM agent_mcp_server WHERE agent_id = #{agentId} ORDER BY mcp_server_id")
    List<Long> findMcpServerIds(@Param("agentId") Long agentId);

    /** 绑定前校验 MCP Server 是否存在。 */
    @Select("SELECT count(*) FROM mcp_server WHERE id = #{mcpServerId}")
    long countExistingMcpServer(@Param("mcpServerId") Long mcpServerId);
}
