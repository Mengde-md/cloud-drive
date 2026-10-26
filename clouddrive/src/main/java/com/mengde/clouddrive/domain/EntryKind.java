package com.mengde.clouddrive.domain;

/**
 * 条目类型（Day02）。用枚举而不是字符串，是为了让"只有这两种"成为编译期事实，
 * 避免上层传进 "dir"、"folder"、"DIRECTORYY" 这类拼写变体。
 */
public enum EntryKind {
    DIRECTORY,
    DOCUMENT
}
