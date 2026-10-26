package com.mengde.clouddrive;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.memory.InMemoryEntryList;

/**
 * Day01 的工程基线入口（命令行）。
 *
 * <p>它和 Web 入口 {@code com.mengde.clouddrive.web.DriveEntryWebApplication} 是两个
 * 各自独立可运行的 main：这个类只用来验证"工程能编译、能跑、能被测试断言"，
 * 真正对外提供服务的是 Web 入口。</p>
 */
public final class CloudDriveApplication {

    static final String PROJECT_NAME = "cloud-drive-learning";
    static final int REQUIRED_JAVA_FEATURE = 17;

    private CloudDriveApplication() {
    }

    public static void main(String[] args) {
        System.out.println(startupBanner());

        InMemoryEntryList entries = new InMemoryEntryList();
        entries.add(new DirectoryEntry(11L, 7L, null, "学习资料"));
        entries.add(new DocumentEntry(12L, 7L, 11L, "JVM 笔记", 2048L));

        for (DriveEntry entry : entries.snapshot()) {
            System.out.printf("%s: %s%n", entry.kind(), entry.displayName());
        }
    }

    static String startupBanner() {
        return PROJECT_NAME + " Day01 bootstrap: Java " + REQUIRED_JAVA_FEATURE;
    }
}
