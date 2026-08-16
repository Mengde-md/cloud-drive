package com.base.common.util;

public class FileUtil {

    /**
     * 获取文件后缀（包含点号）
     */
    public static String getFileSuffix(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".")).toLowerCase();
    }

    /**
     * 将字节数转换为可读的文件大小描述
     */
    public static String byteCountToDisplaySize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        double kb = size / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format("%.1f GB", gb);
        }
        double tb = gb / 1024.0;
        return String.format("%.1f TB", tb);
    }

    /**
     * 根据文件后缀获取 Content-Type
     */
    public static String getContentType(String filename) {
        String suffix = getFileSuffix(filename);
        return switch (suffix) {
            case ".html", ".htm" -> "text/html";
            case ".css" -> "text/css";
            case ".js" -> "application/javascript";
            case ".json" -> "application/json";
            case ".xml" -> "application/xml";
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".bmp" -> "image/bmp";
            case ".ico" -> "image/x-icon";
            case ".svg" -> "image/svg+xml";
            case ".pdf" -> "application/pdf";
            case ".doc" -> "application/msword";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xls" -> "application/vnd.ms-excel";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".ppt" -> "application/vnd.ms-powerpoint";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".mp4" -> "video/mp4";
            case ".mp3" -> "audio/mpeg";
            case ".txt" -> "text/plain";
            case ".csv" -> "text/csv";
            default -> "application/octet-stream";
        };
    }
}
