package com.mengde.clouddrive.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * drive_entries 表的 SQL 合同（Day07 建立，Day16/17/22 逐步收紧）。
 *
 * <p>贯穿全表的两条纪律：</p>
 * <ol>
 *   <li><b>每条按 id 的语句都带 owner_id 条件</b>（Day16）。
 *       只按 id 查再回到 Java 里比较归属，等于把越权判断交给"别忘了写 if"；
 *       把 owner_id 写进 WHERE，越权访问在 SQL 层就查不出数据，返回 404 而不是 403，
 *       连"这个 id 是否存在"都不泄露。</li>
 *   <li><b>正常条目列表和读写带 deleted_at 条件</b>（Day22）。
 *       回收站是逻辑删除，删除后的行还在表里，
 *       所以正常路径必须显式排除 deleted_at IS NOT NULL 的行；
 *       配额统计和物理对象引用检查则必须包含回收站条目。</li>
 * </ol>
 */
@Mapper
public interface DriveEntryMapper {

    /* ---------------- Day16：服务端生成 id 的插入 ---------------- */

    @Insert("""
            INSERT INTO drive_entries
                (owner_id, parent_id, display_name, kind, byte_size, storage_key, content_md5)
            VALUES
                (#{ownerId}, #{parentId}, #{displayName}, #{kind}, #{byteSize},
                 #{storageKey}, #{contentMd5})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGenerated(DriveEntryInsertRow row);

    /* ---------------- Day16：用户隔离的元数据读取 ---------------- */

    @Select("""
            SELECT id, owner_id, parent_id, display_name, kind, byte_size
            FROM drive_entries
            WHERE id = #{id} AND owner_id = #{ownerId} AND deleted_at IS NULL
            """)
    Optional<DriveEntryRow> findByOwnerAndId(@Param("ownerId") long ownerId, @Param("id") long id);

    @Select("""
            SELECT id, owner_id, parent_id, display_name, kind, byte_size
            FROM drive_entries
            WHERE owner_id = #{ownerId} AND deleted_at IS NULL
            ORDER BY parent_scope_id, id
            """)
    List<DriveEntryRow> findAllByOwner(@Param("ownerId") long ownerId);

    /** Day22：列表分页。ORDER BY 必须稳定，否则翻页会出现重复或漏项。 */
    @Select("""
            SELECT id, owner_id, parent_id, display_name, kind, byte_size
            FROM drive_entries
            WHERE owner_id = #{ownerId} AND deleted_at IS NULL
            ORDER BY parent_scope_id, id
            LIMIT #{size} OFFSET #{offset}
            """)
    List<DriveEntryRow> findPageByOwner(@Param("ownerId") long ownerId,
                                       @Param("offset") int offset,
                                       @Param("size") int size);

    @Select("""
            SELECT COUNT(*) FROM drive_entries
            WHERE owner_id = #{ownerId} AND deleted_at IS NULL
            """)
    long countByOwner(@Param("ownerId") long ownerId);

    /**
     * Day16：已占用字节数。
     *
     * <p>这里刻意不排除回收站里的文件：文件进了回收站但对象还在磁盘上，
     * 空间并没有真正释放。如果配额不算它，用户就能靠"上传-删除"循环无限占用磁盘。</p>
     */
    @Select("""
            SELECT COALESCE(SUM(COALESCE(byte_size, 0)), 0)
            FROM drive_entries
            WHERE owner_id = #{ownerId}
            """)
    long usedBytesByOwner(@Param("ownerId") long ownerId);

    @Update("""
            UPDATE drive_entries SET display_name = #{displayName}
            WHERE id = #{id} AND owner_id = #{ownerId} AND deleted_at IS NULL
            """)
    int updateDisplayNameByOwner(@Param("ownerId") long ownerId,
                                 @Param("id") long id,
                                 @Param("displayName") String displayName);

    @Select("""
            SELECT COUNT(*) FROM drive_entries
            WHERE owner_id = #{ownerId} AND parent_id <=> #{parentId}
              AND display_name = #{displayName} AND deleted_at IS NULL
            """)
    int countActiveName(@Param("ownerId") long ownerId,
                        @Param("parentId") Long parentId,
                        @Param("displayName") String displayName);

    /* ---------------- Day17：存储元数据与秒传候选 ---------------- */

    @Select("""
            SELECT id,
                   owner_id AS ownerId,
                   parent_id AS parentId,
                   display_name AS displayName,
                   byte_size AS byteSize,
                   storage_key AS storageKey,
                   content_md5 AS contentMd5
            FROM drive_entries
            WHERE owner_id = #{ownerId}
              AND content_md5 = #{md5}
              AND storage_key IS NOT NULL
              AND deleted_at IS NULL
            ORDER BY id
            LIMIT 1
            """)
    Optional<StoredDocumentRow> findStoredDocumentByOwnerAndMd5(@Param("ownerId") long ownerId,
                                                                @Param("md5") String md5);

    @Select("""
            SELECT id,
                   owner_id AS ownerId,
                   parent_id AS parentId,
                   display_name AS displayName,
                   byte_size AS byteSize,
                   storage_key AS storageKey,
                   content_md5 AS contentMd5
            FROM drive_entries
            WHERE owner_id = #{ownerId}
              AND id = #{id}
              AND storage_key IS NOT NULL
              AND deleted_at IS NULL
            """)
    Optional<StoredDocumentRow> findStoredDocumentByOwnerAndId(@Param("ownerId") long ownerId,
                                                               @Param("id") long id);

    /* ---------------- Day22：回收站（含 V8 的级联删除） ---------------- */

    /** 某个目录下还活着的直接子条目，级联删除时用它逐层往下走。 */
    @Select("""
            SELECT id FROM drive_entries
            WHERE owner_id = #{ownerId} AND parent_id = #{parentId} AND deleted_at IS NULL
            """)
    List<Long> findActiveChildIds(@Param("ownerId") long ownerId, @Param("parentId") long parentId);

    /**
     * 把一棵子树（含根本身）整体标记为已删除，并在每一行记下这次删除的根 id。
     *
     * <p>一条 UPDATE 批量改，而不是循环里一行一行更新：
     * 前者是一个语句一次往返，后者在深目录下会变成几百次数据库往返，
     * 而且中途失败会留下"删了一半"的子树。</p>
     */
    @Update("""
            <script>
            UPDATE drive_entries
            SET deleted_at = CURRENT_TIMESTAMP, trash_root_id = #{rootId}
            WHERE owner_id = #{ownerId} AND deleted_at IS NULL
              AND id IN
              <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int softDeleteSubtree(@Param("ownerId") long ownerId,
                          @Param("ids") List<Long> ids,
                          @Param("rootId") long rootId);

    /**
     * 回收站列表。
     *
     * <p>{@code trash_root_id = id} 是关键条件：只返回"用户当初点删除的那个条目"。
     * 没有这个条件的话，删掉一个有 300 个文件的目录，回收站里就会出现 301 行，
     * 用户根本找不到自己要恢复的是哪一个。</p>
     */
    @Select("""
            SELECT id, parent_id AS parentId, display_name AS displayName,
                   kind, byte_size AS byteSize, deleted_at AS deletedAt
            FROM drive_entries
            WHERE owner_id = #{ownerId} AND deleted_at IS NOT NULL AND trash_root_id = id
            ORDER BY deleted_at DESC, id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<TrashEntryRow> findTrashByOwner(@Param("ownerId") long ownerId,
                                        @Param("offset") int offset,
                                        @Param("size") int size);

    @Select("""
            SELECT COUNT(*) FROM drive_entries
            WHERE owner_id = #{ownerId} AND deleted_at IS NOT NULL AND trash_root_id = id
            """)
    long countTrashByOwner(@Param("ownerId") long ownerId);

    /** 子树里被删除的条目数，用于给用户一个"恢复了 N 项"的准确反馈。 */
    @Select("""
            SELECT COUNT(*) FROM drive_entries
            WHERE owner_id = #{ownerId} AND trash_root_id = #{rootId} AND deleted_at IS NOT NULL
            """)
    int countSubtreeInTrash(@Param("ownerId") long ownerId, @Param("rootId") long rootId);

    /**
     * 按删除批次整棵恢复。
     *
     * <p>条件用 trash_root_id 而不是"父子关系递归"：
     * 如果某个子文件是在父目录之前被单独删掉的，它的 trash_root_id 指向它自己，
     * 恢复父目录时不会被牵连——这符合"各自删的各自恢复"的直觉。</p>
     */
    @Update("""
            UPDATE drive_entries SET deleted_at = NULL, trash_root_id = NULL
            WHERE owner_id = #{ownerId} AND trash_root_id = #{rootId} AND deleted_at IS NOT NULL
            """)
    int restoreSubtree(@Param("ownerId") long ownerId, @Param("rootId") long rootId);

    /** 彻底清空回收站前先取出要删除的对象键，避免留下无人引用的磁盘文件。 */
    @Select("""
            SELECT storage_key FROM drive_entries
            WHERE owner_id = #{ownerId} AND deleted_at IS NOT NULL AND storage_key IS NOT NULL
            """)
    List<String> findTrashStorageKeys(@Param("ownerId") long ownerId);

    @Delete("""
            DELETE FROM drive_entries
            WHERE owner_id = #{ownerId} AND deleted_at IS NOT NULL
            """)
    int purgeTrashByOwner(@Param("ownerId") long ownerId);

    /* ---------------- 回收站定时清理（跨用户，供后台任务使用） ---------------- */

    /** 保留期已过的回收站条目所引用的对象键。 */
    @Select("""
            SELECT storage_key FROM drive_entries
            WHERE deleted_at IS NOT NULL AND deleted_at < #{cutoff} AND storage_key IS NOT NULL
            """)
    List<String> findExpiredTrashStorageKeys(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Delete("""
            DELETE FROM drive_entries
            WHERE deleted_at IS NOT NULL AND deleted_at < #{cutoff}
            """)
    int purgeExpiredTrash(@Param("cutoff") java.time.LocalDateTime cutoff);

    /**
     * 全局引用计数：回收站里的条目仍可恢复，也必须保护其物理对象。
     * 不加 owner_id 或 deleted_at 条件；只有最后一条元数据也被彻底删除才能清理内容。
     */
    @Select("""
            SELECT COUNT(*) FROM drive_entries
            WHERE storage_key = #{storageKey}
            """)
    int countStorageReferences(@Param("storageKey") String storageKey);

    /* ---------------- 测试辅助 ---------------- */

    @Delete("DELETE FROM drive_entries")
    int deleteAll();
}
