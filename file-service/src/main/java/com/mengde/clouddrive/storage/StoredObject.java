package com.mengde.clouddrive.storage;

public record StoredObject(String storageKey, long byteSize, String md5) { }
