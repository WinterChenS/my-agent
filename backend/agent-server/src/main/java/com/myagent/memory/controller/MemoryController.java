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
import org.springframework.web.bind.annotation.RestController;

/**
 * 「记忆可视化」接口：看 / 改 / 删该用户的长期记忆。
 *
 * <p>只操作**当前登录用户自己**的记忆（`userId` 取自 JWT，不接受前端传参），
 * 因此不存在跨用户读写。真正干活的是 {@link MemoryAdminService}
 * （它才碰官方 SPI；本类只做 HTTP 到 DTO 的映射，保持门禁 G2）。
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
    public Result<List<MemoryDocument>> list(@AuthenticationPrincipal JwtPrincipal principal) {
        return Result.ok(service.list(requireUserId(principal)));
    }

    /** 单份记忆（带正文）。`name` 只能是 `MEMORY.md` 或 `YYYY-MM-DD.md`。 */
    @GetMapping("/{name}")
    public Result<MemoryDocument> read(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable String name) {
        return Result.ok(service.read(requireUserId(principal), name));
    }

    /** 覆盖保存。台账与 MEMORY.md 都可改；台账删得掉，MEMORY.md 不能删。 */
    @PutMapping("/{name}")
    public Result<MemoryDocument> save(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable String name,
            @RequestBody MemorySaveRequest body) {
        return Result.ok(service.save(requireUserId(principal), name, body == null ? null : body.content()));
    }

    @DeleteMapping("/{name}")
    public Result<Void> delete(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable String name) {
        service.delete(requireUserId(principal), name);
        return Result.ok();
    }

    private Long requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return principal.userId();
    }
}
