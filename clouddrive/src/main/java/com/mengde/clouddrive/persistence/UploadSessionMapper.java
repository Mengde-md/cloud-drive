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
 * 上传会话与分片清单的 SQL 合同（Day18）。
 * 会话查询同样带 owner_id：别人的 sessionId 即使被猜到也查不出来。
 */
@Mapper
public interface UploadSessionMapper {

    @Insert("""
            INSERT INTO upload_sessions (id, owner_id, parent_id, display_name, total_chunks, status)
            VALUES (#{id}, #{ownerId}, #{parentId}, #{displayName}, #{totalChunks}, #{status})
            """)
    int insertSession(UploadSessionRow row);

    @Select("""
            SELECT id, owner_id AS ownerId, parent_id AS parentId, display_name AS displayName,
                   total_chunks AS totalChunks, status, created_at AS createdAt
            FROM upload_sessions
            WHERE id = #{sessionId} AND owner_id = #{ownerId}
            """)
    Optional<UploadSessionRow> findByOwnerAndId(@Param("ownerId") long ownerId,
                                                @Param("sessionId") String sessionId);

    @Update("UPDATE upload_sessions SET status = #{status} WHERE id = #{sessionId}")
    int updateStatus(@Param("sessionId") String sessionId, @Param("status") String status);

    @Insert("""
            INSERT INTO upload_chunks (session_id, chunk_index, byte_size, content_md5, storage_key)
            VALUES (#{sessionId}, #{chunkIndex}, #{byteSize}, #{contentMd5}, #{storageKey})
            """)
    int insertChunk(UploadChunkRow row);

    @Select("""
            SELECT session_id AS sessionId, chunk_index AS chunkIndex, byte_size AS byteSize,
                   content_md5 AS contentMd5, storage_key AS storageKey
            FROM upload_chunks
            WHERE session_id = #{sessionId}
            ORDER BY chunk_index
            """)
    List<UploadChunkRow> findChunks(@Param("sessionId") String sessionId);

    @Select("""
            SELECT session_id AS sessionId, chunk_index AS chunkIndex, byte_size AS byteSize,
                   content_md5 AS contentMd5, storage_key AS storageKey
            FROM upload_chunks
            WHERE session_id = #{sessionId} AND chunk_index = #{chunkIndex}
            """)
    Optional<UploadChunkRow> findChunk(@Param("sessionId") String sessionId,
                                       @Param("chunkIndex") int chunkIndex);

    @Delete("DELETE FROM upload_chunks WHERE session_id = #{sessionId}")
    int deleteChunks(@Param("sessionId") String sessionId);

    @Delete("DELETE FROM upload_chunks")
    int deleteAllChunks();

    @Delete("DELETE FROM upload_sessions")
    int deleteAllSessions();
}
