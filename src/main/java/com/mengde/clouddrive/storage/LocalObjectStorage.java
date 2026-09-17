package com.mengde.clouddrive.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Day17 local adapter; callers depend on ObjectStorage, not on Paths. */
@Component
public class LocalObjectStorage implements ObjectStorage {
    private final Path root;

    public LocalObjectStorage(@Value("${clouddrive.storage.root:build-output/local-storage}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(InputStream input, String originalFilename) throws IOException {
        Files.createDirectories(root);
        String key = UUID.randomUUID() + safeSuffix(originalFilename);
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IOException("invalid storage key");
        MessageDigest digest = md5Digest();
        long size = 0;
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
        return new StoredObject(key, size, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) throw new IOException("invalid storage key");
        Files.deleteIfExists(target);
    }

    private static MessageDigest md5Digest() {
        try { return MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String safeSuffix(String filename) {
        if (filename == null) return "";
        String leaf = Path.of(filename).getFileName().toString();
        int dot = leaf.lastIndexOf('.');
        return dot >= 0 && dot < leaf.length() - 1 ? leaf.substring(dot) : "";
    }
}
