package com.myagent.channelconfig.controller;

import com.myagent.auth.JwtPrincipal;
import com.myagent.channelconfig.ChannelTypeDescriptor;
import com.myagent.channelconfig.ChannelTypeRegistry;
import com.myagent.channelconfig.dto.ChannelAppRequest;
import com.myagent.channelconfig.dto.ChannelAppResponse;
import com.myagent.channelconfig.dto.ChannelPeerResponse;
import com.myagent.channelconfig.dto.ChannelPushRequest;
import com.myagent.channelconfig.service.ChannelAppService;
import com.myagent.channelconfig.service.ChannelPushPort;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 渠道配置（管理页）。
 *
 * <p>只有 ADMIN 能访问（{@code SecurityConfig} 里已把 {@code /api/channels/**} 归入配置路径）。
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelAppController {

    private final ChannelAppService service;
    private final ObjectProvider<ChannelPushPort> pushPort;
    private final ChannelTypeRegistry typeRegistry;

    public ChannelAppController(
            ChannelAppService service,
            ObjectProvider<ChannelPushPort> pushPort,
            ChannelTypeRegistry typeRegistry) {
        this.service = service;
        this.pushPort = pushPort;
        this.typeRegistry = typeRegistry;
    }

    /**
     * 已支持的渠道类型及其配置项（管理页可据此动态渲染表单，加渠道不用改前端）。
     */
    @GetMapping("/types")
    public Result<List<ChannelTypeDescriptor>> types() {
        return Result.ok(typeRegistry.all());
    }

    @GetMapping
    public Result<List<ChannelAppResponse>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    public Result<ChannelAppResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PostMapping
    public Result<ChannelAppResponse> create(
            @Valid @RequestBody ChannelAppRequest request,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return Result.ok(service.create(request, principal == null ? null : principal.userId()));
    }

    @PutMapping("/{id}")
    public Result<ChannelAppResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ChannelAppRequest request,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return Result.ok(service.update(id, request, principal == null ? null : principal.userId()));
    }

    @PostMapping("/{id}/enabled")
    public Result<ChannelAppResponse> setEnabled(
            @PathVariable Long id, @RequestParam boolean value) {
        return Result.ok(service.setEnabled(id, value));
    }

    @PostMapping("/{id}/restart")
    public Result<Void> restart(@PathVariable Long id) {
        service.restart(id);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /** 手工绑定 IM 接收人（未聊过天的用户也能被推送）。 */
    public record BindPeerRequest(String peerKey, String peerName) {}

    @PostMapping("/{id}/peers")
    public Result<ChannelPeerResponse> bindPeer(
            @PathVariable Long id, @RequestBody BindPeerRequest request) {
        return Result.ok(service.bindPeer(id, request.peerKey(), request.peerName()));
    }

    /** 修改某个 IM 会话的显示名（「对方」那一列）。 */
    public record RenamePeerRequest(String peerName) {}

    @PutMapping("/{id}/peers/{peerId}")
    public Result<ChannelPeerResponse> renamePeer(
            @PathVariable Long id,
            @PathVariable Long peerId,
            @RequestBody RenamePeerRequest request) {
        return Result.ok(service.renamePeer(id, peerId, request.peerName()));
    }

    @GetMapping("/{id}/peers")
    public Result<List<ChannelPeerResponse>> peers(@PathVariable Long id) {
        return Result.ok(service.peers(id));
    }

    /** 主动推送（运营/系统调用；Agent 自己推用 {@code send_im_message} 工具）。 */
    @PostMapping("/{id}/push")
    public Result<Map<String, Integer>> push(
            @PathVariable Long id, @RequestBody ChannelPushRequest request) {
        ChannelPushPort port = pushPort.getIfAvailable();
        if (port == null) {
            throw BizException.badRequest("渠道运行时未启用，无法推送");
        }
        int sent = port.push(id, request.peerKey(), request.text(), request.artifactIds());
        return Result.ok(Map.of("sent", sent));
    }
}
