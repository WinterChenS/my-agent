package com.myagent.common;

/**
 * 统一响应体：{@code {"code":0,"msg":"ok","data":...}}。
 *
 * <p>{@code code} 为 0 表示成功，非 0 表示业务错误。
 *
 * @param <T> 数据类型
 */
public record Result<T>(int code, String msg, T data) {

    public static final int CODE_OK = 0;

    public static <T> Result<T> ok(T data) {
        return new Result<>(CODE_OK, "ok", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(CODE_OK, "ok", null);
    }

    public static <T> Result<T> error(int code, String msg) {
        return new Result<>(code, msg, null);
    }
}
