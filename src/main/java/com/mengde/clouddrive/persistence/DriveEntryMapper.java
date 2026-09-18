package com.mengde.clouddrive.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis Mapper：把 {@code drive_entries} 表的 CRUD 操作以注解方式声明。
 * 不依赖 XML 映射文件；列名到属性名的映射由
 * {@code mybatis.configuration.map-underscore-to-camel-case} 自动完成。
 */
public interface DriveEntryMapper {

    @Insert("""
            INSERT INTO drive_entries (id, owner_id, parent_id, display_name, kind, byte_size)
            VALUES (#{id}, #{ownerId}, #{parentId}, #{displayName}, #{kind}, #{byteSize})
            """)
    int insert(DriveEntryRow row);

    @Insert("""
            INSERT INTO drive_entries (owner_id, parent_id, display_name, kind, byte_size)
            VALUES (#{ownerId}, #{parentId}, #{displayName}, #{kind}, #{byteSize})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGenerated(DriveEntryInsertRow row);

    @Insert("""
            INSERT INTO drive_entries (owner_id, parent_id, display_name, kind, byte_size, storage_key, content_md5)
            VALUES (#{ownerId}, #{parentId}, #{displayName}, 'DOCUMENT', #{byteSize}, #{storageKey}, #{contentMd5})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertStoredDocument(StoredDocumentRow row);

    @Select("SELECT id, owner_id, parent_id, display_name, kind, byte_size FROM drive_entries WHERE id = #{id} AND deleted_at IS NULL")
    Optional<DriveEntryRow> findById(long id);

    @Select("SELECT id, owner_id, parent_id, display_name, kind, byte_size FROM drive_entries WHERE deleted_at IS NULL ORDER BY id")
    List<DriveEntryRow> findAll();

    @Select("SELECT id, owner_id, parent_id, display_name, kind, byte_size FROM drive_entries WHERE id = #{id} AND owner_id = #{ownerId} AND deleted_at IS NULL")
    Optional<DriveEntryRow> findByOwnerAndId(@Param("ownerId") long ownerId, @Param("id") long id);

    @Select("SELECT id, owner_id, parent_id, display_name, kind, byte_size FROM drive_entries WHERE owner_id = #{ownerId} AND deleted_at IS NULL ORDER BY parent_scope_id, id")
    List<DriveEntryRow> findAllByOwner(long ownerId);

    @Select("SELECT COALESCE(SUM(COALESCE(byte_size, 0)), 0) FROM drive_entries WHERE owner_id = #{ownerId} AND deleted_at IS NULL")
    long usedBytesByOwner(long ownerId);

    @Select("""
            SELECT id, owner_id AS ownerId, parent_id AS parentId, display_name AS displayName,
                   byte_size AS byteSize, storage_key AS storageKey, content_md5 AS contentMd5
            FROM drive_entries WHERE owner_id = #{ownerId} AND content_md5 = #{md5} AND deleted_at IS NULL
            AND storage_key IS NOT NULL ORDER BY id LIMIT 1
            """)
    Optional<StoredDocumentRow> findStoredDocumentByOwnerAndMd5(@Param("ownerId") long ownerId, @Param("md5") String md5);

    @Select("""
            SELECT id, owner_id AS ownerId, parent_id AS parentId, display_name AS displayName,
                   byte_size AS byteSize, storage_key AS storageKey, content_md5 AS contentMd5
            FROM drive_entries WHERE owner_id = #{ownerId} AND id = #{id} AND deleted_at IS NULL
            AND storage_key IS NOT NULL
            """)
    Optional<StoredDocumentRow> findStoredDocumentByOwnerAndId(@Param("ownerId") long ownerId, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM drive_entries WHERE owner_id = #{ownerId} AND parent_id = #{parentId} AND deleted_at IS NULL")
    int countChildrenByOwnerAndParent(@Param("ownerId") long ownerId, @Param("parentId") long parentId);

    @Update("UPDATE drive_entries SET display_name = #{displayName} WHERE id = #{id} AND deleted_at IS NULL")
    int updateDisplayName(@Param("id") long id, @Param("displayName") String displayName);

    @Update("UPDATE drive_entries SET display_name = #{displayName} WHERE id = #{id} AND owner_id = #{ownerId} AND deleted_at IS NULL")
    int updateDisplayNameByOwner(@Param("ownerId") long ownerId, @Param("id") long id, @Param("displayName") String displayName);

    @Delete("DELETE FROM drive_entries WHERE id = #{id}")
    int deleteById(long id);

    @Update("UPDATE drive_entries SET deleted_at = CURRENT_TIMESTAMP WHERE id = #{id} AND owner_id = #{ownerId} AND deleted_at IS NULL")
    int deleteByOwnerAndId(@Param("ownerId") long ownerId, @Param("id") long id);

    @Select("SELECT id, parent_id, display_name, kind, byte_size, deleted_at FROM drive_entries WHERE owner_id = #{ownerId} AND deleted_at IS NOT NULL ORDER BY deleted_at DESC, id DESC LIMIT #{size} OFFSET #{offset}")
    java.util.List<TrashEntryRow> findTrashByOwner(@Param("ownerId") long ownerId, @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM drive_entries WHERE owner_id = #{ownerId} AND deleted_at IS NOT NULL")
    long countTrashByOwner(@Param("ownerId") long ownerId);

    @Update("UPDATE drive_entries SET deleted_at = NULL WHERE id = #{id} AND owner_id = #{ownerId} AND deleted_at IS NOT NULL")
    int restoreByOwnerAndId(@Param("ownerId") long ownerId, @Param("id") long id);

    @Delete("DELETE FROM drive_entries WHERE owner_id = #{ownerId} AND deleted_at IS NOT NULL")
    int purgeTrashByOwner(@Param("ownerId") long ownerId);

    @Delete("DELETE FROM drive_entries")
    int deleteAll();

    @Select("SELECT COUNT(*) FROM drive_entries")
    int count();
}
