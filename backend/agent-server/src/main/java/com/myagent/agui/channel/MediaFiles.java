package com.myagent.agui.channel;

import java.util.Locale;
import java.util.Set;

/** 媒体文件的中性工具：扩展名、是否图片、MIME 推断（与具体 IM 渠道无关）。 */
public final class MediaFiles {

    private static final Set<String> IMAGE_EXT =
            Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "tif", "tiff");

    private MediaFiles() {}

    /** 小写扩展名（不含点）；没有扩展名返回空串。 */
    public static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 是否图片（按扩展名判断）。 */
    public static boolean isImage(String fileName) {
        return IMAGE_EXT.contains(extension(fileName));
    }

    /** 按扩展名推断 MIME；未知返回 application/octet-stream。 */
    public static String guessMime(String fileName) {
        return switch (extension(fileName)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "heic" -> "image/heic";
            case "pdf" -> "application/pdf";
            case "md", "markdown" -> "text/markdown";
            case "txt", "log" -> "text/plain";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "yaml", "yml" -> "application/yaml";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "zip" -> "application/zip";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "amr" -> "audio/amr";
            case "mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }
}
