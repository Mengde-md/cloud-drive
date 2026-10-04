package com.mengde.clouddrive.search;

public record SearchDocument(long fileId, long ownerId, Long parentId, String displayName,
                             long byteSize, String generation, String kind) {
    public SearchDocument inGeneration(String value) {
        return new SearchDocument(fileId, ownerId, parentId, displayName, byteSize, value, "DOCUMENT");
    }
    public static SearchDocument manifest(long owner, String generation) {
        return new SearchDocument(0, owner, null, "", 0, generation, "MANIFEST");
    }
}
