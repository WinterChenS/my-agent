package com.myagent.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 上传超限要给出明确业务错误，而不是泛化的 500。
 *
 * <p>回归的是用户实测问题：容器默认单文件上限只有 1MB（而业务上限 50MB），
 * 上传一张 1.2MB 的截图就在进入业务代码前被容器拒掉，原先直接 500。
 */
class GlobalExceptionHandlerUploadTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 超出容器上限返回400业务错误而不是500() {
        ResponseEntity<Result<Void>> response =
                handler.handleUploadTooLarge(new MaxUploadSizeExceededException(1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Result<Void> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(400);
        assertThat(body.msg()).contains("超过上限");
    }
}
