package com.mengde.clouddrive.upload;

import java.nio.file.Path;

public record MergedChunks(Path temporaryFile, long byteSize, String md5) {
}
