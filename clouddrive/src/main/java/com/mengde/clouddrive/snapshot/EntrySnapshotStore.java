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

/**
 * 把内存中的条目落成一个文本快照文件，并能重新读回来（Day04）。
 *
 * <p>三个关键设计：</p>
 * <ol>
 *   <li><b>先写临时文件再原子替换</b>：如果直接往目标文件写，进程在写一半时被杀掉，
 *       磁盘上就留下一个"半行"的文件，下次读取会把它当成损坏数据。
 *       写临时文件 + ATOMIC_MOVE 保证目标文件要么是旧的完整快照、要么是新的完整快照。</li>
 *   <li><b>显式指定 UTF-8</b>：不写字符集就会用平台默认编码，
 *       Windows 中文环境下写出 GBK 而在别的机器上按 UTF-8 读，中文名就会变成乱码。</li>
 *   <li><b>显示名做 Base64 编码</b>：分隔符是 '|'，而文件名本身可以含 '|'。
 *       不编码的话，一个叫 "a|b" 的文件会写出多余的字段，读回来时字段数就对不上了。</li>
 * </ol>
 */
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
            temporaryPath = null;
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

    public Path snapshotPath() {
        return snapshotPath;
    }

    private void replaceSnapshot(Path temporaryPath) throws IOException {
        try {
            Files.move(temporaryPath, snapshotPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            // 跨文件系统时无法原子移动，退化为普通替换：仍然比直接写目标文件安全。
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
        // split 的 limit 传 -1，否则末尾的空字段会被丢掉，缺字段的坏行就会被误判为合法。
        String[] fields = line.split("\\|", -1);
        try {
            if (fields.length == 5 && DIRECTORY.equals(fields[0])) {
                return new DirectoryEntry(
                        parsePositive(fields[1], lineNumber),
                        parsePositive(fields[2], lineNumber),
                        parseParent(fields[3], lineNumber),
                        decodeName(fields[4], lineNumber));
            }
            if (fields.length == 6 && DOCUMENT.equals(fields[0])) {
                return new DocumentEntry(
                        parsePositive(fields[1], lineNumber),
                        parsePositive(fields[2], lineNumber),
                        parseParent(fields[3], lineNumber),
                        decodeName(fields[4], lineNumber),
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
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8));
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
