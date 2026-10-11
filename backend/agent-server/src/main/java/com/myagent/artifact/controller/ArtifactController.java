package com.myagent.artifact.controller;

import com.myagent.artifact.dto.ArtifactResponse;
import com.myagent.artifact.service.ArtifactService;
import com.myagent.auth.JwtPrincipal;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 产物与来源文件（技术方案 §12.4）。 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactService service;

    public ArtifactController(ArtifactService service) {
        this.service = service;
    }

    /** 会话的来源文件与产物（仅属主）。 */
    @GetMapping
    public Result<List<ArtifactResponse>> list(
            @AuthenticationPrincipal JwtPrincipal principal, @RequestParam Long sessionId) {
        return Result.ok(service.list(requireUserId(principal), sessionId));
    }

    /** 聊天上传：落库/落 MinIO 并物化到会话工作区。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ArtifactResponse> upload(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam Long sessionId,
            @RequestParam("file") MultipartFile file) {
        return Result.ok(service.upload(requireUserId(principal), sessionId, file));
    }

    /**
     * 下载：MINIO 走签名直链（302），DB 直接返回正文。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        ArtifactService.DownloadResult result = service.download(requireUserId(principal), id);
        if (result.url() != null) {
            return ResponseEntity.status(302).location(URI.create(result.url())).build();
        }
        MediaType type = result.mime() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(result.mime());
        return ResponseEntity.ok()
                .contentType(type)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.fileName() + "\"")
                .body(result.bytes());
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        service.delete(requireUserId(principal), id);
        return Result.ok();
    }

    private Long requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return principal.userId();
    }
}
