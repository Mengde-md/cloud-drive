package com.mengde.clouddrive.storage;

/**
 * 一个分片的落盘结果（Day18）。
 */
public record StoredChunk(String storageKey, long byteSize, String md5) {
}
