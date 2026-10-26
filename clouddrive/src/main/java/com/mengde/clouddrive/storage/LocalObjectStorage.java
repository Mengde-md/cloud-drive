package com.mengde.clouddrive.storage;

import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 本地磁盘实现（Day17 写入 / Day19 按范围读取）。
 *
 * <p>三个安全与正确性要点：</p>
 * <ol>
 *   <li><b>路径逃逸防护</b>：每次都 resolve + normalize，再断言仍在 root 之下。
 *       否则 storageKey 传入 "../../etc/passwd" 就能读写存储目录之外的文件。</li>
 *   <li><b>边写边算 MD5 与字节数</b>：只读一遍流。如果先存盘再重新读一遍算 MD5，
 *       大文件要多一次完整 IO；而且 MultipartFile 的流通常只能读一次。</li>
 *   <li><b>写失败即删除半成品</b>：异常时 deleteIfExists，
 *       不留下"看起来存在但内容不完整"的对象——这种文件比不存在更危险。</li>
 * </ol>
 */
@Component
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalObjectStorage(StorageProperties properties) {
        this.root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(InputStream input, String originalFilename) throws IOException {
        Files.createDirectories(root);
        String key = UUID.randomUUID() + safeSuffix(originalFilename);
        Path target = resolveInsideRoot(key);

        MessageDigest digest = md5Digest();
        long size = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream source = input; var output = Files.newOutputStream(target)) {
            for (int read; (read = source.read(buffer)) != -1; ) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException | RuntimeException cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return new StoredObject(key, size, HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * 按范围打开对象（Day19）。
     *
     * <p>包装流里重写 read 并维护 remaining：如果只 skip 到起点而不限制上界，
     * 客户端请求 "bytes=0-9" 也会把整个文件读完发出去，Content-Length 与实际内容就不一致了。</p>
     */
    @Override
    public InputStream open(String storageKey, long start, long length) throws IOException {
        Path target = resolveInsideRoot(storageKey);
        if (start < 0 || length < 0) {
            throw new IOException("invalid object range");
        }
        InputStream input = Files.newInputStream(target);
        try {
            input.skipNBytes(start);
        } catch (IOException failure) {
            input.close();
            throw failure;
        }
        return new BufferedInputStream(input) {
            private long remaining = length;

            @Override
            public int read(byte[] bytes, int offset, int count) throws IOException {
                if (remaining == 0) {
                    return -1;
                }
                int read = super.read(bytes, offset, (int) Math.min(count, remaining));
                if (read > 0) {
                    remaining -= read;
                }
                return read;
            }

            @Override
            public int read() throws IOException {
                if (remaining == 0) {
                    return -1;
                }
                int read = super.read();
                if (read != -1) {
                    remaining--;
                }
                return read;
            }
        };
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolveInsideRoot(storageKey));
    }

    public Path root() {
        return root;
    }

    private Path resolveInsideRoot(String storageKey) throws IOException {
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("invalid storage key");
        }
        return target;
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 只保留扩展名，丢掉客户端提供的路径与文件名本体，避免把用户输入直接当路径用。 */
    private static String safeSuffix(String filename) {
        if (filename == null) {
            return "";
        }
        String leaf = Path.of(filename).getFileName().toString();
        int dot = leaf.lastIndexOf('.');
        return dot >= 0 && dot < leaf.length() - 1 ? leaf.substring(dot) : "";
    }
}
