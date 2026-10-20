package com.myagent.artifact.service;

import java.util.Locale;

/**
 * 「扩展名 → MIME」的**唯一**实现。
 *
 * <p>此前交付路径（{@code PlatformArtifactDeliveryTarget}）与扫描路径（{@link ArtifactCaptureService}）
 * 各有一份，且已经不一致：前者认 {@code webp}/{@code svg}、后者不认 —— 结果是 Agent 写出的
 * svg/webp 图片在扫描路径下被当成 {@code application/octet-stream}，既不按图片显示，也谈不上预览。
 * 这里合并成一份（取两份表的并集），两边都委托过来；以后要加类型只改这里。
 */
public final class ArtifactMime {

    private ArtifactMime() {}

    /** 未知扩展名一律 {@code application/octet-stream}（存储路由会把它走 MinIO）。 */
    public static String guess(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "md", "markdown" -> "text/markdown";
            case "txt", "log" -> "text/plain";
            case "json", "jsonl" -> "application/json";
            case "csv" -> "text/csv";
            case "xml" -> "application/xml";
            case "yaml", "yml" -> "application/yaml";
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js", "mjs" -> "text/javascript";
            case "ts", "tsx", "py", "java", "sh", "sql", "properties", "ini", "conf", "toml" ->
                    "text/plain";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }
}
