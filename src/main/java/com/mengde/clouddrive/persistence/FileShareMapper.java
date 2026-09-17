package com.mengde.clouddrive.persistence;

import com.mengde.clouddrive.share.FileShareRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.Optional;

public interface FileShareMapper {
    @Insert("INSERT INTO file_shares (token, document_id, owner_id, passcode_hash, expires_at) VALUES (#{token}, #{documentId}, #{ownerId}, #{passcodeHash}, #{expiresAt})")
    int insert(FileShareRow row);
    @Select("SELECT token, document_id AS documentId, owner_id AS ownerId, passcode_hash AS passcodeHash, expires_at AS expiresAt FROM file_shares WHERE token = #{token}")
    Optional<FileShareRow> findByToken(@Param("token") String token);
}
