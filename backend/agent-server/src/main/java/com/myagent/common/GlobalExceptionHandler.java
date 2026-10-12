package com.myagent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 全局异常处理：任何异常都转换为 {@link Result}，不向客户端抛裸 500 堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        return ResponseEntity.status(ex.status())
                .body(Result.error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fieldMessage(fe))
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.error(400, msg));
    }

    /** 请求体不是合法 JSON / 编码不对：属于客户端错误，不应报 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体无法解析: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, "请求体不是合法的 JSON（请确认编码为 UTF-8）"));
    }

    /** 未匹配到任何 handler/静态资源：返回 404 而不是 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "接口不存在"));
    }

    private String fieldMessage(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }

    /**
     * 客户端中途断开（SSE 场景常见）：响应已不可写，属于正常情况，
     * 不能当成未预期异常，也不应写入响应体。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException ex) {
        log.debug("客户端已断开，异步响应不可写: {}", ex.getMessage());
    }

    /**
     * 上传超出容器上限（spring.servlet.multipart.*）。
     *
     * <p>实测坑：容器默认单文件上限只有 1MB，而业务上限是 50MB —— 超过 1MB 的图片在**进入我们代码之前**
     * 就被容器拒掉，且原先直接泛化成 500（用户上传截图即触发）。这里给明确业务错误。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("上传超出容器上限: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, "上传文件超过上限（单文件最大 55MB，可用 MYAGENT_MULTIPART_MAX_FILE 调整）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        // Tomcat 在客户端断开时可能抛出 IOException 包装（ClientAbortException），
        // 同样属于正常断开，不应记为服务端故障。
        if (isClientAbort(ex)) {
            log.debug("客户端已断开（I/O 中断）: {}", ex.getMessage());
            return null;
        }
        log.error("未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "服务器内部错误"));
    }

    private static boolean isClientAbort(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String name = current.getClass().getName();
            if (name.endsWith("ClientAbortException") || current instanceof java.io.IOException) {
                String message = current.getMessage();
                if (message != null
                        && (message.contains("中止") || message.contains("aborted")
                                || message.contains("Broken pipe") || message.contains("Connection reset")
                                || message.contains("不可用"))) {
                    return true;
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}
