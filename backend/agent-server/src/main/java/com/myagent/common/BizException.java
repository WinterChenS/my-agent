package com.myagent.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带业务码与 HTTP 状态。
 *
 * <p>{@link #code()} 与 HTTP 状态码保持一致，便于前端区分 401/403/404/409/400。
 */
public class BizException extends RuntimeException {

    private final int code;
    private final HttpStatus status;

    public BizException(int code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public int code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message, HttpStatus.BAD_REQUEST);
    }

    public static BizException unauthorized(String message) {
        return new BizException(401, message, HttpStatus.UNAUTHORIZED);
    }

    public static BizException forbidden(String message) {
        return new BizException(403, message, HttpStatus.FORBIDDEN);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message, HttpStatus.NOT_FOUND);
    }

    public static BizException conflict(String message) {
        return new BizException(409, message, HttpStatus.CONFLICT);
    }
}
