package com.mengde.clouddrive.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("SELECT id, owner_id, parent_id, display_name, kind, byte_size FROM drive_entries WHERE id = #{id}")
    Optional<DriveEntryRow> findById(long id);

    @Select("SELECT id, owner_id, parent_id, display_name, kind, byte_size FROM drive_entries ORDER BY id")
    List<DriveEntryRow> findAll();

    @Update("UPDATE drive_entries SET display_name = #{displayName} WHERE id = #{id}")
    int updateDisplayName(@Param("id") long id, @Param("displayName") String displayName);

    @Delete("DELETE FROM drive_entries WHERE id = #{id}")
    int deleteById(long id);

    @Delete("DELETE FROM drive_entries")
    int deleteAll();

    @Select("SELECT COUNT(*) FROM drive_entries")
    int count();
}
