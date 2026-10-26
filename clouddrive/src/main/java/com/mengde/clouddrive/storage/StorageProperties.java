package com.mengde.clouddrive.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储位置配置（Day17/Day18）。路径来自配置而不是硬编码，
 * 便于在不同机器、测试与容器里指向不同目录。
 */
@ConfigurationProperties(prefix = "clouddrive.storage")
public class StorageProperties {

    /** 最终对象（完整文件）根目录。 */
    private String root = "build-output/local-storage";

    /** 分片临时文件根目录。 */
    private String chunkRoot = "build-output/local-chunks";

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getChunkRoot() {
        return chunkRoot;
    }

    public void setChunkRoot(String chunkRoot) {
        this.chunkRoot = chunkRoot;
    }
}
