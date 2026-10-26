package com.mengde.clouddrive.domain;

/**
 * 云盘条目的统一协议（Day02）。
 *
 * <p>目录和文件在业务上有大量共同点：都归属某个用户、都可能有父目录、都有显示名、都能被重命名。
 * 把这些共同点抽成接口，调用方就可以用同一套代码遍历、展示、重命名，而不必关心具体是目录还是文件。
 * 差异部分由实现类负责：目录没有字节数，文件有。</p>
 */
public interface DriveEntry {

    long id();

    long ownerId();

    /** 根条目返回 null，表示没有父目录。 */
    Long parentId();

    String displayName();

    EntryKind kind();

    /** 重命名。实现类负责去空白与非空校验，调用方不需要自己清洗输入。 */
    void rename(String requestedName);
}
