package com.myagent.agui.browser;

import com.myagent.common.BizException;
import com.myagent.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 浏览器画面与交互接口（给前端「产物预览区 → 浏览器 tab」用）。
 *
 * <p>帧走 SSE：CDP screencast 只在该页面**有变化时**推帧，服务端再按 maxFps 节流后转发；
 * 前端用 fetch + ReadableStream 读（EventSource 带不了 Authorization 头）。
 *
 * <p>用户的点击/键盘通过 REST 回传，注入到**同一个** Chromium（所以"你看到的"和"agent 操作的"是同一个页面）。
 */
@RestController
@RequestMapping("/api/browser")
public class BrowserController {

    private static final Logger log = LoggerFactory.getLogger(BrowserController.class);

    private final BrowserSessionRegistry registry;

    public BrowserController(BrowserSessionRegistry registry) {
        this.registry = registry;
    }

    /** 当前用户的浏览器状态（前端用它决定要不要自动切到浏览器视图）。 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status(@AuthenticationPrincipal com.myagent.auth.JwtPrincipal principal) {
        String userId = userId(principal);
        BrowserSession session = registry.find(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", registry.properties().enabled());
        body.put("active", session != null);
        if (session != null) {
            body.put("url", session.currentUrl());
            body.put("title", session.currentTitle());
            // 返回**当前真实视口**（不是"最后一帧"的尺寸）：前端据此画 1:1 画面、避免黑边
            body.put("width", session.viewportWidth());
            body.put("height", session.viewportHeight());
            body.put("userControlled", session.userControlled());
            body.put("status", session.statusText());
            body.put("allowUserInput", registry.properties().allowUserInput());
        }
        return Result.ok(body);
    }

    /**
     * 实时画面（SSE）。每帧一条：{@code {"image":"<base64 jpeg>","width":1280,"height":800}}。
     *
     * <p>只推"有变化"的帧（CDP 侧本来就只在画面变化时产生帧），并按 maxFps 节流。
     */
    @GetMapping(value = "/frames", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter frames(@AuthenticationPrincipal com.myagent.auth.JwtPrincipal principal,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        String userId = userId(principal);
        BrowserSession session = registry.find(userId);
        if (session == null) {
            emitter.complete();
            return emitter;
        }
        AtomicBoolean closed = new AtomicBoolean(false);
        BrowserSession.FrameListener listener = (base64Jpeg, width, height) -> {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("frame")
                        .data(Map.of("image", base64Jpeg, "width", width, "height", height)));
            } catch (IOException | IllegalStateException e) {
                closed.set(true);
                emitter.completeWithError(e);
            }
        };
        emitter.onCompletion(() -> {
            closed.set(true);
            session.removeFrameListener(listener);
        });
        emitter.onTimeout(() -> {
            closed.set(true);
            session.removeFrameListener(listener);
        });
        session.addFrameListener(listener);
        return emitter;
    }

    /** 用户在画面上操作（点击/滚动/输入/按键）。这会把"控制权"判给用户一会儿。 */
    @PostMapping("/input")
    public Result<Void> input(@AuthenticationPrincipal com.myagent.auth.JwtPrincipal principal,
            @RequestBody Map<String, Object> body) {
        if (!registry.properties().allowUserInput()) {
            throw BizException.forbidden("服务端未开放用户接管（myagent.browser.allow-user-input=false）");
        }
        BrowserSession session = registry.find(userId(principal));
        if (session == null) {
            throw BizException.badRequest("浏览器还没打开");
        }
        session.setUserControlled(true);
        String type = String.valueOf(body.getOrDefault("type", "click"));
        switch (type) {
            case "click" -> session.click(number(body, "x"), number(body, "y"));
            case "scroll" -> session.scroll(number(body, "x"), number(body, "y"), number(body, "deltaY"));
            case "text" -> session.insertText(String.valueOf(body.getOrDefault("text", "")));
            case "key" -> session.pressKey(String.valueOf(body.getOrDefault("key", "Enter")));
            default -> throw BizException.badRequest("不支持的输入类型：" + type);
        }
        return Result.ok(null);
    }

    /** 用户手动导航（地址栏）。 */
    @PostMapping("/navigate")
    public Result<Void> navigate(@AuthenticationPrincipal com.myagent.auth.JwtPrincipal principal,
            @RequestBody Map<String, Object> body) {
        String url = String.valueOf(body.getOrDefault("url", ""));
        String rejected = registry.rejectUrl(url);
        if (rejected != null) {
            throw BizException.badRequest(rejected);
        }
        BrowserSession session = registry.acquire(userId(principal));
        session.navigate(url);
        return Result.ok(null);
    }

    /**
     * 改真实视口尺寸（用户拖拽面板宽度/高度时用）。
     *
     * <p>不是"缩放图片"，而是真的把页面宽度改掉 → 页面 reflow，画面铺满面板、没有黑边。
     */
    @PostMapping("/viewport")
    public Result<Void> viewport(@AuthenticationPrincipal com.myagent.auth.JwtPrincipal principal,
            @RequestBody Map<String, Object> body) {
        BrowserSession session = registry.find(userId(principal));
        if (session == null) {
            throw BizException.badRequest("浏览器还没打开");
        }
        session.setViewport(number(body, "width"), number(body, "height"));
        return Result.ok(null);
    }

    /** 交还控制权给 Agent（用户不再接管）。 */
    @PostMapping("/takeover")
    public Result<Void> takeover(@AuthenticationPrincipal com.myagent.auth.JwtPrincipal principal,
            @RequestBody Map<String, Object> body) {
        BrowserSession session = registry.find(userId(principal));
        if (session != null) {
            session.setUserControlled(Boolean.parseBoolean(String.valueOf(body.getOrDefault("on", "false"))));
        }
        return Result.ok(null);
    }

    /** 关闭浏览器（用户点"关闭"或用完回收）。 */
    @PostMapping("/close")
    public Result<Void> close(@AuthenticationPrincipal com.myagent.auth.JwtPrincipal principal) {
        registry.close(userId(principal));
        return Result.ok(null);
    }

    private static String userId(com.myagent.auth.JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return String.valueOf(principal.userId());
    }

    private static int number(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? 0 : (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
