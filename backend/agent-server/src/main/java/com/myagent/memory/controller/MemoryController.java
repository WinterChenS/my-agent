package com.myagent.memory.controller;

import com.myagent.agui.memory.MemoryAdminService;
import com.myagent.agui.memory.MemoryDocument;
import com.myagent.auth.JwtPrincipal;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「记忆可视化」接口：看 / 改 / 删长期记忆。
 *
 * <p>默认只操作**当前登录用户自己**的记忆（`userId` 取自 JWT）。唯一的例外是 `ADMIN`：
 * 他可以用 `?userId=` 显式指定别的用户（页面上的「用户」下拉框就是这么用的），
 * 不传仍然是自己。普通用户传别人的 `userId` 会被 403 拒掉，不会有“静默纠正成自己”这种迷惑行为。
 *
 * <p>权限判定必须留在本类：`/api/memory/**` 不在 {@code SecurityConfig} 的 `CONFIG_PATHS`
 * （那里只管配置类接口的写操作），所以安全配置兜不住这件事。
 *
 * <p>真正干活的是 {@link MemoryAdminService}（它才碰官方 SPI；本类只做 HTTP 到 DTO 的映射，
 * 保持门禁 G2）。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryAdminService service;

    public MemoryController(MemoryAdminService service) {
        this.service = service;
    }

    /** 记忆文件清单：`MEMORY.md` 在前，每日台账按日期倒序；**不带正文**。 */
    @GetMapping
    public Result<List<MemoryDocument>> list(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(required = false) Long userId) {
        return Result.ok(service.list(targetUser(principal, userId)));
    }

    /** 单份记忆（带正文）。`name` 只能是 `MEMORY.md` 或 `YYYY-MM-DD.md`。 */
    @GetMapping("/{name}")
    public Result<MemoryDocument> read(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable String name,
            @RequestParam(required = false) Long userId) {
        return Result.ok(service.read(targetUser(principal, userId), name));
    }

    /** 覆盖保存。台账与 MEMORY.md 都可改；台账删得掉，MEMORY.md 不能删。 */
    @PutMapping("/{name}")
    public Result<MemoryDocument> save(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable String name,
            @RequestParam(required = false) Long userId,
            @RequestBody MemorySaveRequest body) {
        return Result.ok(
                service.save(targetUser(principal, userId), name, body == null ? null : body.content()));
    }

    @DeleteMapping("/{name}")
    public Result<Void> delete(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable String name,
            @RequestParam(required = false) Long userId) {
        service.delete(targetUser(principal, userId), name);
        return Result.ok();
    }

    private Long targetUser(JwtPrincipal principal, Long requestedUserId) {
        return resolveTargetUser(requireUserId(principal), principal.role(), requestedUserId);
    }

    /**
     * 解析「这次操作谁的记忆」。
     *
     * <ul>
     *   <li>不传 / 传自己 → 自己；</li>
     *   <li>{@code ADMIN} 传别人 → 别人（超管排查问题时要能看某个用户的记忆）；</li>
     *   <li>普通用户传别人 → 403（不静默改成自己，否则前端会以为看的是别人的）。</li>
     * </ul>
     */
    static Long resolveTargetUser(Long callerId, String callerRole, Long requestedUserId) {
        if (requestedUserId == null || requestedUserId.equals(callerId)) {
            return callerId;
        }
        if (!"ADMIN".equals(callerRole)) {
            throw BizException.forbidden("只能查看自己的记忆");
        }
        return requestedUserId;
    }

    private Long requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return principal.userId();
    }
}
