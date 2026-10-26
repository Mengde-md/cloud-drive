package com.mengde.clouddrive.analysis;

import java.util.Locale;
import java.util.Set;

public enum FileCategory {
    DOCUMENT, IMAGE, AUDIO, VIDEO, ARCHIVE, CODE, SPREADSHEET, PRESENTATION, OTHER;

    private static final Set<String> TEXT = Set.of("txt", "md", "markdown", "csv", "tsv", "json", "xml",
            "yaml", "yml", "log", "ini", "conf", "properties", "java", "kt", "kts", "py", "js", "jsx",
            "ts", "tsx", "html", "htm", "css", "scss", "sql", "c", "cpp", "h", "hpp", "cs", "go",
            "rs", "rb", "php", "sh", "ps1", "bat", "vue", "svelte", "ipynb", "tex");

    static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean supportsText(String name) { return TEXT.contains(extension(name)); }

    public static FileCategory fromName(String name) {
        String ext = extension(name);
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "heic", "tif", "tiff" -> IMAGE;
            case "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma" -> AUDIO;
            case "mp4", "mkv", "avi", "mov", "webm", "wmv", "flv", "m4v" -> VIDEO;
            case "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst" -> ARCHIVE;
            case "xls", "xlsx", "csv", "tsv", "ods" -> SPREADSHEET;
            case "ppt", "pptx", "odp", "key" -> PRESENTATION;
            case "txt", "md", "markdown", "pdf", "doc", "docx", "odt", "rtf", "epub", "tex", "log" -> DOCUMENT;
            default -> TEXT.contains(ext) ? CODE : OTHER;
        };
    }
}
