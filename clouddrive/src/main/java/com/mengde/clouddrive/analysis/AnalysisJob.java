package com.mengde.clouddrive.analysis;

record AnalysisJob(long entryId, long ownerId, String fileName, String storageKey, long byteSize,
                   String generation, boolean reviewEnabled) {
}
