package com.mengde.clouddrive.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 分享记录的 SQL 合同（Day19）。
 */
@Mapper
public interface FileShareMapper {

    @Insert("""
            INSERT INTO file_shares (token, document_id, owner_id, passcode_hash, expires_at)
            VALUES (#{token}, #{documentId}, #{ownerId}, #{passcodeHash}, #{expiresAt})
            """)
    int insert(FileShareRow row);

    @Select("""
            SELECT token, document_id AS documentId, owner_id AS ownerId,
                   passcode_hash AS passcodeHash, expires_at AS expiresAt,
                   created_at AS createdAt
            FROM file_shares
            WHERE token = #{token}
            """)
    Optional<FileShareRow> findByToken(@Param("token") String token);

    /**
     * 我创建的分享列表。
     *
     * <p>联查 drive_entries 带出文件名和大小：只显示 documentId 的话，
     * 用户有三五个分享之后就分不清哪个是哪个了。</p>
     *
     * <p>用 LEFT JOIN 而不是 INNER JOIN：文件被删进回收站后这条分享已经失效，
     * 但它仍然应该出现在列表里并被标记出来，好让用户知道"这个链接为什么打不开"，
     * 而不是让它从界面上凭空消失。</p>
     */
    @Select("""
            SELECT s.token,
                   s.document_id AS documentId,
                   s.owner_id AS ownerId,
                   s.passcode_hash AS passcodeHash,
                   s.expires_at AS expiresAt,
                   s.created_at AS createdAt,
                   e.display_name AS documentDisplayName,
                   e.byte_size AS documentByteSize,
                   (e.id IS NULL OR e.deleted_at IS NOT NULL) AS documentDeleted
            FROM file_shares s
            LEFT JOIN drive_entries e ON e.id = s.document_id
            WHERE s.owner_id = #{ownerId}
            ORDER BY s.created_at DESC
            """)
    List<FileShareRow> findByOwner(@Param("ownerId") long ownerId);

    /**
     * 重置提取码。
     *
     * <p>为什么需要它：提取码只存 BCrypt 摘要，创建后无法再回显。
     * 用户忘记提取码时，如果没有这个接口就只能取消分享重建，
     * 而重建会换一个新 token，已经发出去的链接全部失效。
     * 重置只换口令、保留 token，已发出的链接仍然有效。</p>
     */
    @Update("""
            UPDATE file_shares SET passcode_hash = #{passcodeHash}
            WHERE token = #{token} AND owner_id = #{ownerId}
            """)
    int updatePasscode(@Param("ownerId") long ownerId,
                       @Param("token") String token,
                       @Param("passcodeHash") String passcodeHash);

    @Delete("DELETE FROM file_shares WHERE token = #{token} AND owner_id = #{ownerId}")
    int deleteByOwnerAndToken(@Param("ownerId") long ownerId, @Param("token") String token);

    @Delete("DELETE FROM file_shares")
    int deleteAll();
}
