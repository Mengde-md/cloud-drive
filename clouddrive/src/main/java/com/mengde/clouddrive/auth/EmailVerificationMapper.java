package com.mengde.clouddrive.auth;

import org.apache.ibatis.annotations.*;
import java.util.Optional;

@Mapper
public interface EmailVerificationMapper {
    @Insert("INSERT IGNORE INTO email_verification (email, purpose) VALUES (#{email}, #{purpose})")
    int ensureRow(@Param("email") String email, @Param("purpose") String purpose);

    @Select("SELECT email, purpose, code_hash, sent_at, expires_at, attempts, consumed "
            + "FROM email_verification WHERE email = #{email} AND purpose = #{purpose} FOR UPDATE")
    Optional<EmailVerificationRow> lock(@Param("email") String email, @Param("purpose") String purpose);

    @Update("UPDATE email_verification SET code_hash=#{codeHash}, sent_at=#{sentAt}, "
            + "expires_at=#{expiresAt}, attempts=#{attempts}, consumed=#{consumed} "
            + "WHERE email=#{email} AND purpose=#{purpose}")
    int save(EmailVerificationRow row);

    @Delete("DELETE FROM email_verification")
    int deleteAll();
}
