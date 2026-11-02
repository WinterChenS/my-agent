package com.myagent.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理员账号的启动引导。
 *
 * <p>背景：Flyway 的 {@code V2__seed.sql} 会插入一个 {@code admin} 账号，口令是写死在迁移里的
 * 默认值（{@code admin123}）。开源项目里这是真实风险 —— 有人部署到公网却忘了改密码。
 *
 * <p>这里做两件事（都不改已有数据的行为，除非你显式要求）：
 * <ol>
 *   <li>配了 {@code MYAGENT_ADMIN_PASSWORD} 且管理员**仍是默认口令** → 按环境变量重设，
 *       让 Docker 部署可以不依赖"登录后再改"；</li>
 *   <li>没配、且管理员仍是默认口令 → 每次启动打一条 WARN，提醒去改。</li>
 * </ol>
 *
 * <p>已经改过密码的库不会被覆盖（避免误重置）。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /** V2__seed.sql 里写死的初始口令（仅用于判断"还是不是默认值"）。 */
    private static final String DEFAULT_PASSWORD = "admin123";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String initialPassword;

    public AdminBootstrap(
            SysUserMapper userMapper,
            PasswordEncoder passwordEncoder,
            @Value("${myagent.auth.admin-initial-password:}") String initialPassword) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.initialPassword = initialPassword == null ? "" : initialPassword.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        SysUser admin = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, "admin"));
        if (admin == null) {
            // 不是本平台的库（或还没跑迁移），不插手
            return;
        }
        boolean stillDefault = isDefaultPassword(admin.getPasswordHash());
        if (!stillDefault) {
            return;
        }
        if (!initialPassword.isEmpty()) {
            if (initialPassword.length() < 8) {
                log.warn("MYAGENT_ADMIN_PASSWORD 太短（<8 位），已忽略；请改用更强的口令");
                return;
            }
            admin.setPasswordHash(passwordEncoder.encode(initialPassword));
            userMapper.updateById(admin);
            log.info("已按 MYAGENT_ADMIN_PASSWORD 设置管理员口令（不再是默认口令）");
            return;
        }
        log.warn("⚠ 管理员账号仍是默认口令 admin123 —— 请立即修改，"
                + "或在部署时设置环境变量 MYAGENT_ADMIN_PASSWORD（仅对默认口令生效）");
    }

    private boolean isDefaultPassword(String hash) {
        if (hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return passwordEncoder.matches(DEFAULT_PASSWORD, hash);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
