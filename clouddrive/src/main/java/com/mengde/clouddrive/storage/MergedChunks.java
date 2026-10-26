package com.mengde.clouddrive.storage;

import java.nio.file.Path;

/**
 * 分片合并出的临时完整文件（Day18）。
 *
 * <p>它只是中间产物：随后会被当作普通输入流写入 ObjectStorage 得到正式对象键，
 * 然后无论成功失败都必须在 finally 里删掉，否则每次上传都留一份垃圾。</p>
 */
public record MergedChunks(Path temporaryFile, long byteSize, String md5) {
}
