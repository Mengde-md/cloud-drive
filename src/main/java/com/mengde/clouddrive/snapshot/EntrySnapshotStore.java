package com.mengde.clouddrive.snapshot;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

public final class EntrySnapshotStore {
    private static final String DIRECTORY = "D";
    private static final String DOCUMENT = "F";

    private final Path snapshotPath;

    public EntrySnapshotStore(Path snapshotPath) {
        this.snapshotPath = Objects.requireNonNull(snapshotPath, "snapshotPath");
    }

    public void save(List<? extends DriveEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        Path parent = snapshotPath.toAbsolutePath().getParent();
        Path temporaryPath = null;
        try {
            Files.createDirectories(parent);
            temporaryPath = Files.createTempFile(parent, snapshotPath.getFileName().toString(), ".tmp");
            Files.write(temporaryPath, entries.stream().map(this::encode).toList(), StandardCharsets.UTF_8);
            replaceSnapshot(temporaryPath);
        } catch (IOException exception) {
            throw new SnapshotFormatException("could not save snapshot", exception);
        } finally {
            deleteTemporaryFile(temporaryPath);
        }
    }

    public List<DriveEntry> load() {
        if (Files.notExists(snapshotPath)) {
            return List.of();
        }
        try {
            List<DriveEntry> entries = new ArrayList<>();
            List<String> lines = Files.readAllLines(snapshotPath, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                entries.add(decode(lines.get(index), index + 1));
            }
            return List.copyOf(entries);
        } catch (IOException exception) {
            throw new SnapshotFormatException("could not load snapshot", exception);
        }
    }

    private void replaceSnapshot(Path temporaryPath) throws IOException {
        try {
            Files.move(temporaryPath, snapshotPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String encode(DriveEntry entry) {
        Objects.requireNonNull(entry, "entry");
        String common = entry.id() + "|" + entry.ownerId() + "|" + nullableParent(entry.parentId())
                + "|" + encodeName(entry.displayName());
        if (entry instanceof DirectoryEntry) {
            return DIRECTORY + "|" + common;
        }
        if (entry instanceof DocumentEntry document) {
            return DOCUMENT + "|" + common + "|" + document.byteSize();
        }
        throw new SnapshotFormatException("unsupported entry type: " + entry.getClass().getName());
    }

    private DriveEntry decode(String line, int lineNumber) {
        String[] fields = line.split("\\|", -1);
        try {
            if (fields.length == 5 && DIRECTORY.equals(fields[0])) {
                return new DirectoryEntry(parsePositive(fields[1], lineNumber), parsePositive(fields[2], lineNumber),
                        parseParent(fields[3], lineNumber), decodeName(fields[4], lineNumber));
            }
            if (fields.length == 6 && DOCUMENT.equals(fields[0])) {
                return new DocumentEntry(parsePositive(fields[1], lineNumber), parsePositive(fields[2], lineNumber),
                        parseParent(fields[3], lineNumber), decodeName(fields[4], lineNumber),
                        parseNonNegative(fields[5], lineNumber));
            }
        } catch (IllegalArgumentException exception) {
            throw new SnapshotFormatException("invalid snapshot line " + lineNumber, exception);
        }
        throw new SnapshotFormatException("invalid snapshot line " + lineNumber);
    }

    private static long parsePositive(String value, int lineNumber) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new SnapshotFormatException("non-positive value at line " + lineNumber);
        }
        return parsed;
    }

    private static long parseNonNegative(String value, int lineNumber) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new SnapshotFormatException("negative byte size at line " + lineNumber);
        }
        return parsed;
    }

    private static Long parseParent(String value, int lineNumber) {
        return value.isEmpty() ? null : parsePositive(value, lineNumber);
    }

    private static String nullableParent(Long parentId) {
        return parentId == null ? "" : parentId.toString();
    }

    private static String encodeName(String name) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeName(String encodedName, int lineNumber) {
        try {
            return new String(Base64.getUrlDecoder().decode(encodedName), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new SnapshotFormatException("invalid encoded name at line " + lineNumber, exception);
        }
    }

    private static void deleteTemporaryFile(Path temporaryPath) {
        if (temporaryPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryPath);
        } catch (IOException exception) {
            throw new SnapshotFormatException("could not clean temporary snapshot", exception);
        }
    }
}
