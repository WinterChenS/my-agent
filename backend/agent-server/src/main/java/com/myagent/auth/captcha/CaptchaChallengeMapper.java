package com.myagent.auth.captcha;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** captcha_challenge 数据访问。 */
@Mapper
public interface CaptchaChallengeMapper extends BaseMapper<CaptchaChallenge> {

    /**
     * 原子抢占：只有「未使用 + 未验证 + 未过期」的题目能被抢到。
     *
     * <p>并发提交同一道题时数据库保证只有一个请求 updates=1 —— 其余拿到 0，直接判失败。
     * 这样就不依赖应用层锁，也不会出现"两个请求都通过"的竞态。
     */
    @Update("""
            UPDATE captcha_challenge
               SET verified = TRUE,
                   used = TRUE,
                   ticket = #{ticket},
                   consumed_at = NOW(),
                   attempts = attempts + 1
             WHERE id = #{id}
               AND used = FALSE
               AND verified = FALSE
               AND expires_at > NOW()
            """)
    int claim(@Param("id") String id, @Param("ticket") String ticket);

    /** 记一次失败尝试（同样只对未使用且未过期的题目生效），返回是否命中。 */
    @Update("""
            UPDATE captcha_challenge
               SET attempts = attempts + 1,
                   risk_score = #{riskScore}
             WHERE id = #{id}
               AND used = FALSE
               AND expires_at > NOW()
            """)
    int recordFailure(@Param("id") String id, @Param("riskScore") Integer riskScore);

    /** 作废题目（尝试次数超限 / 命中明显机器人行为）。 */
    @Update("UPDATE captcha_challenge SET used = TRUE, consumed_at = NOW() WHERE id = #{id} AND used = FALSE")
    int invalidate(@Param("id") String id);

    /**
     * 登录时消费 ticket：**同一条 SQL 里**校验绑定关系 + 有效期并删除，保证一次一用。
     *
     * @return 1 表示消费成功；0 表示 ticket 不存在 / 不属于该会话 / 已过期 / 已用过
     */
    @Delete("""
            DELETE FROM captcha_challenge
             WHERE ticket = #{ticket}
               AND session_key = #{sessionKey}
               AND used = TRUE
               AND expires_at > NOW()
            """)
    int consumeTicket(@Param("ticket") String ticket, @Param("sessionKey") String sessionKey);
}
