package com.mengde.clouddrive.upload;

public record StoredChunk(String storageKey, long byteSize, String md5) {
}
