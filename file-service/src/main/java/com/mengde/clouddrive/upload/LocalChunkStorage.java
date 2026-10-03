package com.mengde.clouddrive.upload;

import org.springframework.beans.factory.annotation.Value;
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

@Component
public class LocalChunkStorage implements ChunkStorage {
    private final Path root;

    public LocalChunkStorage(@Value("${clouddrive.chunk-storage.root:build-output/upload-chunks}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredChunk store(String sessionId, int chunkIndex, InputStream input) throws IOException {
        Path session = sessionDirectory(sessionId);
        Files.createDirectories(session);
        Path target = session.resolve(chunkIndex + ".part").normalize();
        if (!target.startsWith(session)) throw new IOException("invalid chunk target");
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
            Files.deleteIfExists(target);
            throw failure;
        }
        return new StoredChunk(sessionId + "/" + chunkIndex + ".part", size, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public MergedChunks merge(String sessionId, List<UploadChunkRow> chunks) throws IOException {
        Path temporary = root.resolve(sessionId + ".merged").normalize();
        if (!temporary.startsWith(root)) throw new IOException("invalid merge target");
        MessageDigest digest = md5();
        long size = 0L;
        byte[] buffer = new byte[8192];
        try (var output = Files.newOutputStream(temporary)) {
            for (UploadChunkRow chunk : chunks) {
                Path source = root.resolve(chunk.getStorageKey()).normalize();
                if (!source.startsWith(root)) throw new IOException("invalid chunk key");
                try (InputStream input = Files.newInputStream(source)) {
                    for (int read; (read = input.read(buffer)) != -1; ) {
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        size += read;
                    }
                }
            }
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        return new MergedChunks(temporary, size, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public void clearSession(String sessionId) throws IOException {
        Path session = sessionDirectory(sessionId);
        if (!Files.exists(session)) return;
        try (var paths = Files.walk(session)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException failure) { throw new ChunkCleanupException(failure); }
            });
        } catch (ChunkCleanupException failure) {
            throw failure.getCause();
        }
    }

    @Override
    public void deleteTemporary(MergedChunks merged) throws IOException {
        Files.deleteIfExists(merged.temporaryFile());
    }

    private Path sessionDirectory(String sessionId) throws IOException {
        Path session = root.resolve(sessionId).normalize();
        if (!session.startsWith(root)) throw new IOException("invalid session id");
        return session;
    }

    private static MessageDigest md5() {
        try { return MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class ChunkCleanupException extends RuntimeException {
        private ChunkCleanupException(IOException cause) { super(cause); }
        @Override public IOException getCause() { return (IOException) super.getCause(); }
    }
}
