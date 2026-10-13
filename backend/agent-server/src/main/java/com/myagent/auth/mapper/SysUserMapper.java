package com.myagent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/** sys_user 数据访问。 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    /**
     * 显式更新额度字段（支持写 NULL）。
     *
     * <p>不用 {@code updateById}：它默认忽略 null 字段，导致「清空额度」不生效（实测）。
     */
    @org.apache.ibatis.annotations.Update("""
            <script>
            UPDATE sys_user
            <set>
              <foreach collection="sets" index="k" item="v" separator=",">
                ${k} = #{v}
              </foreach>
            </set>
            WHERE id = #{id}
            </script>
            """)
    int updateQuota(@org.apache.ibatis.annotations.Param("id") Long id,
                    @org.apache.ibatis.annotations.Param("sets") java.util.Map<String, Object> sets);
}
