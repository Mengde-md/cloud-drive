package com.mengde.clouddrive.storage;

import com.mengde.clouddrive.persistence.UploadChunkRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 分片临时存储的本地实现（Day18）。
 *
 * <p>目录结构：{chunkRoot}/{sessionId}/{chunkIndex}.part，合并产物是 {chunkRoot}/{sessionId}.merged。
 * 用会话 id 做子目录，清理时可以整个目录删掉，不必逐片记录。</p>
 */
@Component
public class LocalChunkStorage implements ChunkStorage {

    private final Path root;

    public LocalChunkStorage(StorageProperties properties) {
        this.root = Path.of(properties.getChunkRoot()).toAbsolutePath().normalize();
    }

    @Override
    public StoredChunk store(String sessionId, int chunkIndex, InputStream input) throws IOException {
        Path session = sessionDirectory(sessionId);
        Files.createDirectories(session);
        Path target = session.resolve(chunkIndex + ".part").normalize();
        if (!target.startsWith(session)) {
            throw new IOException("invalid chunk target");
        }

        MessageDigest digest = md5();
        long size = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream source = input; var output = Files.newOutputStream(target)) {
            for (int read; (read = source.read(buffer)) != -1; ) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (IOException failure) {
            // 半个分片比没有分片更危险：它会让后续"已收到第 N 片"的判断成立，但内容是残缺的。
            removePartialPreservingFailure(target, failure);
            throw failure;
        }
        return new StoredChunk(sessionId + "/" + chunkIndex + ".part", size,
                HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public MergedChunks merge(String sessionId, List<UploadChunkRow> chunks) throws IOException {
        Files.createDirectories(root);
        Path temporary = root.resolve(sessionId + ".merged").normalize();
        if (!temporary.startsWith(root)) {
            throw new IOException("invalid merge target");
        }

        MessageDigest digest = md5();
        long size = 0L;
        byte[] buffer = new byte[8192];
        try (var output = Files.newOutputStream(temporary)) {
            // chunks 由调用方按 chunk_index 排好序传进来：顺序错了合并出的文件内容就是错的，
            // 而且因为大小一致，除非校验 MD5 否则不会被发现。
            for (UploadChunkRow chunk : chunks) {
                Path source = root.resolve(chunk.getStorageKey()).normalize();
                if (!source.startsWith(root)) {
                    throw new IOException("invalid chunk key");
                }
                try (InputStream input = Files.newInputStream(source)) {
                    for (int read; (read = input.read(buffer)) != -1; ) {
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        size += read;
                    }
                }
            }
        } catch (IOException failure) {
            removePartialPreservingFailure(temporary, failure);
            throw failure;
        }
        return new MergedChunks(temporary, size, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public void clearSession(String sessionId) throws IOException {
        Path session = sessionDirectory(sessionId);
        if (Files.notExists(session)) {
            return;
        }
        try (var paths = Files.walk(session)) {
            // 反序：先删文件再删目录，否则目录非空删不掉。
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        } catch (java.io.UncheckedIOException wrapped) {
            throw wrapped.getCause();
        }
    }

    @Override
    public void deleteTemporary(MergedChunks merged) throws IOException {
        if (merged == null) {
            return;
        }
        Files.deleteIfExists(merged.temporaryFile());
    }

    public Path root() {
        return root;
    }

    private static void removePartialPreservingFailure(Path path, IOException original) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException cleanupFailure) {
            if (cleanupFailure != original) original.addSuppressed(cleanupFailure);
        }
    }

    private Path sessionDirectory(String sessionId) throws IOException {
        Path session = root.resolve(sessionId).normalize();
        if (!session.startsWith(root)) {
            throw new IOException("invalid session directory");
        }
        return session;
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
