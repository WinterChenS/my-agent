package com.myagent.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import com.myagent.common.BizException;
import com.myagent.common.PageResult;
import com.myagent.user.dto.UserRequest;
import com.myagent.user.dto.UserResponse;
import java.util.List;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户管理（C1）：ADMIN 建/停用用户、改角色、重置密码。
 *
 * <p>这里最重要的不是 CRUD，而是**别把管理员自己锁在门外**：
 *
 * <ul>
 *   <li>不能禁用/删除自己（否则当前登录的人立刻失去权限，且无法自救）；
 *   <li>不能把最后一个可用的 ADMIN 降级/禁用/删除（否则整个平台再没人能管理）。
 * </ul>
 *
 * <p>密码一律经 {@code BCryptPasswordEncoder} 哈希后落库；对外响应**绝不包含密码哈希**。
 */
@Service
public class UserService {

    private static final Set<String> ROLES = Set.of("ADMIN", "USER");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    /** 密码最短长度：太短的密码在这个平台上等于没设。 */
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /** 列表（分页 + 名称/角色/状态筛选）。 */
    public PageResult<UserResponse> list(
            long current, long size, String keyword, String role, String status) {
        var query = Wrappers.<SysUser>lambdaQuery().orderByAsc(SysUser::getId);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            query.and(w -> w.like(SysUser::getUsername, kw).or().like(SysUser::getDisplayName, kw));
        }
        if (StringUtils.hasText(role)) {
            query.eq(SysUser::getRole, role.trim().toUpperCase());
        }
        if (StringUtils.hasText(status)) {
            query.eq(SysUser::getStatus, status.trim().toUpperCase());
        }
        var page = userMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), query);
        return PageResult.of(
                page.getRecords().stream().map(UserService::toResponse).toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    /** 新建用户。 */
    @Transactional
    public UserResponse create(UserRequest request) {
        String username = requireUsername(request.username());
        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)) > 0) {
            throw BizException.conflict("用户名已存在: " + username);
        }
        String password = requirePassword(request.password());
        String role = normalizeRole(request.role());
        String status = normalizeStatus(request.status());

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(StringUtils.hasText(request.displayName()) ? request.displayName().trim() : username);
        user.setRole(role);
        user.setStatus(status);
        userMapper.insert(user);
        return toResponse(userMapper.selectById(user.getId()));
    }

    /**
     * 修改用户（显示名 / 角色 / 状态 / 可选改密码）。
     *
     * @param operatorId 当前操作者（用于自我保护判断）
     */
    @Transactional
    public UserResponse update(Long operatorId, Long id, UserRequest request) {
        SysUser user = requireUser(id);
        String role = request.role() == null ? user.getRole() : normalizeRole(request.role());
        String status = request.status() == null ? user.getStatus() : normalizeStatus(request.status());

        // 自我保护：不能把自己禁用或降级
        if (id.equals(operatorId)) {
            if (!"ENABLED".equals(status)) {
                throw BizException.badRequest("不能禁用当前登录的账号");
            }
            if (!"ADMIN".equals(role) && "ADMIN".equals(user.getRole())) {
                throw BizException.badRequest("不能把自己降级为普通用户");
            }
        }
        // 平台保护：不能把最后一个可用 ADMIN 降级或禁用
        if ("ADMIN".equals(user.getRole()) && "ENABLED".equals(user.getStatus())
                && (!"ADMIN".equals(role) || !"ENABLED".equals(status))) {
            ensureNotLastAdmin("降级或禁用");
        }

        if (StringUtils.hasText(request.displayName())) {
            user.setDisplayName(request.displayName().trim());
        }
        user.setRole(role);
        user.setStatus(status);
        if (StringUtils.hasText(request.password())) {
            user.setPasswordHash(passwordEncoder.encode(requirePassword(request.password())));
        }
        userMapper.updateById(user);
        return toResponse(userMapper.selectById(id));
    }

    /** 重置密码。 */
    @Transactional
    public void resetPassword(Long id, String password) {
        SysUser user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(requirePassword(password)));
        userMapper.updateById(user);
    }

    /** 删除用户（同样有自我保护与最后一个管理员的保护）。 */
    @Transactional
    public void delete(Long operatorId, Long id) {
        SysUser user = requireUser(id);
        if (id.equals(operatorId)) {
            throw BizException.badRequest("不能删除当前登录的账号");
        }
        if ("ADMIN".equals(user.getRole()) && "ENABLED".equals(user.getStatus())) {
            ensureNotLastAdmin("删除");
        }
        userMapper.deleteById(id);
    }

    // ---------- 校验 ----------

    private SysUser requireUser(Long id) {
        SysUser user = id == null ? null : userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在: " + id);
        }
        return user;
    }

    private static String requireUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw BizException.badRequest("用户名不能为空");
        }
        String value = username.trim();
        // 只允许字母数字下划线中划线：用户名会进 JWT 与 URL，别引入奇怪字符
        if (!value.matches("[A-Za-z0-9_-]{2,64}")) {
            throw BizException.badRequest("用户名只能是 2-64 位的字母、数字、下划线或中划线");
        }
        return value;
    }

    private static String requirePassword(String password) {
        if (!StringUtils.hasText(password)) {
            throw BizException.badRequest("密码不能为空");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw BizException.badRequest("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        return password;
    }

    private static String normalizeRole(String role) {
        String value = StringUtils.hasText(role) ? role.trim().toUpperCase() : "USER";
        if (!ROLES.contains(value)) {
            throw BizException.badRequest("角色非法: " + role);
        }
        return value;
    }

    private static String normalizeStatus(String status) {
        String value = StringUtils.hasText(status) ? status.trim().toUpperCase() : "ENABLED";
        if (!STATUSES.contains(value)) {
            throw BizException.badRequest("状态非法: " + status);
        }
        return value;
    }

    /** 若这是最后一个可用的 ADMIN，就拒绝（否则平台再没人能管理）。 */
    private void ensureNotLastAdmin(String action) {
        Long remaining = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getRole, "ADMIN")
                .eq(SysUser::getStatus, "ENABLED"));
        if (remaining != null && remaining <= 1) {
            throw BizException.badRequest("这是最后一个可用的管理员，不能" + action);
        }
    }

    static UserResponse toResponse(SysUser user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt());
    }

    /** 供测试/诊断：把用户转成不含密码的字符串。 */
    public List<UserResponse> listAll() {
        return userMapper.selectList(Wrappers.<SysUser>lambdaQuery().orderByAsc(SysUser::getId)).stream()
                .map(UserService::toResponse)
                .toList();
    }
}
