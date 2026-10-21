package com.myagent.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import com.myagent.common.PageResult;
import com.myagent.user.dto.UserRequest;
import com.myagent.user.dto.UserResponse;
import com.myagent.user.service.UserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户管理（C1）：CRUD + 校验 + **两道「别把管理员锁在门外」的保护**。
 *
 * <p>保护那两条是最值得测的：它们不是普通校验，而是「一旦失效就再也进不去」的场景。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private SysUserMapper userMapper;

    private SysUser newAdmin() {
        SysUser user = new SysUser();
        user.setUsername("u-admin-" + System.nanoTime());
        user.setPasswordHash("x");
        user.setRole("ADMIN");
        user.setStatus("ENABLED");
        userMapper.insert(user);
        return userMapper.selectById(user.getId());
    }

    private static UserRequest request(String username, String password) {
        return new UserRequest(username, password, null, "USER", "ENABLED");
    }

    @Test
    void 创建用户_响应不含密码且密码是哈希() {
        String name = "u-new-" + System.nanoTime();
        UserResponse created = userService.create(request(name, "Passw0rd!"));

        assertThat(created.username()).isEqualTo(name);
        assertThat(created.role()).isEqualTo("USER");
        assertThat(created.status()).isEqualTo("ENABLED");
        // 响应对象里根本没有 password 字段（结构上就不可能泄漏）
        assertThat(created.toString()).doesNotContain("Passw0rd!");

        SysUser stored = userMapper.selectById(created.id());
        assertThat(stored.getPasswordHash()).isNotEqualTo("Passw0rd!");
        assertThat(stored.getPasswordHash()).startsWith("$2"); // BCrypt
    }

    @Test
    void 列表支持按名称与角色筛选() {
        String name = "u-filter-" + System.nanoTime();
        userService.create(new UserRequest(name, "Passw0rd!", "筛选测试", "ADMIN", "ENABLED"));

        PageResult<UserResponse> byKeyword = userService.list(1, 20, name, null, null);
        assertThat(byKeyword.records()).hasSize(1);
        assertThat(byKeyword.records().get(0).username()).isEqualTo(name);

        PageResult<UserResponse> byRole = userService.list(1, 50, name, "USER", null);
        assertThat(byRole.records()).isEmpty();
    }

    @Test
    void 用户名重复被拒() {
        String name = "u-dup-" + System.nanoTime();
        userService.create(request(name, "Passw0rd!"));
        assertThatThrownBy(() -> userService.create(request(name, "Passw0rd!")))
                .hasMessageContaining("用户名已存在");
    }

    @Test
    void 校验分支_空名_非法字符_短密码_非法角色_非法状态() {
        assertThatThrownBy(() -> userService.create(request("  ", "Passw0rd!")))
                .hasMessageContaining("用户名不能为空");
        assertThatThrownBy(() -> userService.create(request("bad name!", "Passw0rd!")))
                .hasMessageContaining("用户名只能是");
        assertThatThrownBy(() -> userService.create(request("u-short-" + System.nanoTime(), "123")))
                .hasMessageContaining("密码至少 6 位");
        assertThatThrownBy(() -> userService.create(
                        new UserRequest("u-role-" + System.nanoTime(), "Passw0rd!", null, "ROOT", "ENABLED")))
                .hasMessageContaining("角色非法");
        assertThatThrownBy(() -> userService.create(
                        new UserRequest("u-st-" + System.nanoTime(), "Passw0rd!", null, "USER", "PAUSED")))
                .hasMessageContaining("状态非法");
    }

    @Test
    void 保护_不能禁用或删除自己() {
        SysUser me = newAdmin();
        assertThatThrownBy(() -> userService.update(
                        me.getId(), me.getId(), new UserRequest(null, null, null, "ADMIN", "DISABLED")))
                .hasMessageContaining("不能禁用当前登录的账号");
        assertThatThrownBy(() -> userService.delete(me.getId(), me.getId()))
                .hasMessageContaining("不能删除当前登录的账号");
    }

    @Test
    void 保护_不能降级自己() {
        SysUser me = newAdmin();
        assertThatThrownBy(() -> userService.update(
                        me.getId(), me.getId(), new UserRequest(null, null, null, "USER", "ENABLED")))
                .hasMessageContaining("不能把自己降级");
    }

    @Test
    void 保护_不能干掉最后一个可用管理员() {
        // 测试库里有种子 admin；把其它启用中的 ADMIN 都临时停掉，只留一个，再尝试禁用/删除它
        List<SysUser> admins = userMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getRole, "ADMIN")
                        .eq(SysUser::getStatus, "ENABLED"));
        for (SysUser admin : admins) {
            admin.setRole("USER");
            userMapper.updateById(admin);
        }
        SysUser only = newAdmin();

        // 用「另一个操作者」的 id 触发（避免先撞到自我保护那条）
        Long otherOperator = only.getId() + 1000;
        assertThatThrownBy(() -> userService.update(
                        otherOperator, only.getId(), new UserRequest(null, null, null, "USER", "ENABLED")))
                .hasMessageContaining("最后一个可用的管理员");
        assertThatThrownBy(() -> userService.delete(otherOperator, only.getId()))
                .hasMessageContaining("最后一个可用的管理员");
    }

    @Test
    void 重置密码后哈希变化() {
        String name = "u-pwd-" + System.nanoTime();
        UserResponse created = userService.create(request(name, "Passw0rd!"));
        String before = userMapper.selectById(created.id()).getPasswordHash();
        userService.resetPassword(created.id(), "AnotherPass1!");
        String after = userMapper.selectById(created.id()).getPasswordHash();
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void 修改时密码留空表示不改密码() {
        String name = "u-keep-" + System.nanoTime();
        UserResponse created = userService.create(request(name, "Passw0rd!"));
        String before = userMapper.selectById(created.id()).getPasswordHash();
        userService.update(1L, created.id(), new UserRequest(null, null, "改个显示名", "USER", "ENABLED"));
        assertThat(userMapper.selectById(created.id()).getPasswordHash()).isEqualTo(before);
        assertThat(userMapper.selectById(created.id()).getDisplayName()).isEqualTo("改个显示名");
    }

    @Test
    void 关键字是纯数字或井号数字时_按用户ID精确匹配() {
        // 场景：排查问题时手里通常只有“用户 #37”这种 id（日志/审计/工单里都是 id）
        String name = "u-id-" + System.nanoTime();
        UserResponse created = userService.create(request(name, "Passw0rd!"));

        assertThat(userService.list(1, 20, String.valueOf(created.id()), null, null).records())
                .extracting(UserResponse::id)
                .contains(created.id());
        // 前端列表里显示成 #37，照着抄也能搜到
        assertThat(userService.list(1, 20, "#" + created.id(), null, null).records())
                .extracting(UserResponse::id)
                .contains(created.id());
        // 用户名仍然能搜到（模糊）
        assertThat(userService.list(1, 20, name.substring(0, 8), null, null).records())
                .extracting(UserResponse::username)
                .contains(name);
    }

    @Test
    void parseId_只认数字和井号数字_其余当普通关键字() {
        assertThat(UserService.parseId("37")).isEqualTo(37L);
        assertThat(UserService.parseId(" #37 ")).isEqualTo(37L);
        // 不是数字、空、或超出 long 范围 → 不当成 id（否则会抛异常或误命中）
        assertThat(UserService.parseId("张三")).isNull();
        assertThat(UserService.parseId("37abc")).isNull();
        assertThat(UserService.parseId("")).isNull();
        assertThat(UserService.parseId(null)).isNull();
        assertThat(UserService.parseId("999999999999999999999")).isNull();
    }
}
