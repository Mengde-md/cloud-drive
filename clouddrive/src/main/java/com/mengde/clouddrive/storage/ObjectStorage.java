package com.mengde.clouddrive.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 对象存储抽象（Day17 建立，Day19 加入 open 以支持 Range 下载）。
 *
 * <p>为什么要这层接口：业务代码只需要"存进去、按范围读出来、删掉"三件事，
 * 不需要知道文件在本地磁盘还是在对象存储服务上。
 * 以后换成 MinIO 或 OSS 只要再写一个实现类，上层不用改。</p>
 */
public interface ObjectStorage {

    StoredObject store(InputStream input, String originalFilename) throws IOException;

    /** 打开从 start 开始、最多 length 字节的输入流，用于 Range 下载。 */
    InputStream open(String storageKey, long start, long length) throws IOException;

    void delete(String storageKey) throws IOException;
}
