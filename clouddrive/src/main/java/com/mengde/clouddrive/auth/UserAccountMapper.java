package com.mengde.clouddrive.auth;

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
 * 账号表的 SQL 合同（Day15/Day16）。
 */
@Mapper
public interface UserAccountMapper {

    String COLUMNS = "id, username, password_hash AS passwordHash, role, email, enabled, "
            + "token_version AS tokenVersion, quota_bytes AS quotaBytes, created_at AS createdAt";

    @Insert("""
            INSERT INTO user_account (username, password_hash, role, email, enabled)
            VALUES (#{username}, #{passwordHash}, #{role}, #{email}, #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserAccountRow row);

    @Select("SELECT " + COLUMNS + " FROM user_account WHERE username = #{username}")
    Optional<UserAccountRow> findByUsername(@Param("username") String username);

    @Select("SELECT " + COLUMNS + " FROM user_account WHERE id = #{id}")
    Optional<UserAccountRow> findById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM user_account WHERE email = #{email}")
    Optional<UserAccountRow> findByEmail(@Param("email") String email);

    @Select("SELECT " + COLUMNS + " FROM user_account WHERE id = #{id} FOR UPDATE")
    Optional<UserAccountRow> lockById(@Param("id") long id);

    @Select("SELECT id FROM account_security_guard WHERE id = 1 FOR UPDATE")
    int lockSecurityGuard();

    @Select("SELECT COUNT(*) FROM user_account WHERE role = 'ADMIN' AND enabled = TRUE")
    long countEnabledAdmins();

    @Select("SELECT " + COLUMNS + " FROM user_account "
            + "WHERE username LIKE #{query} OR email LIKE #{query} "
            + "ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<UserAccountRow> list(@Param("query") String query, @Param("offset") long offset,
                              @Param("size") int size);

    @Select("SELECT COUNT(*) FROM user_account WHERE username LIKE #{query} OR email LIKE #{query}")
    long count(@Param("query") String query);

    @Update("UPDATE user_account SET password_hash = #{hash}, token_version = token_version + 1 WHERE id = #{id}")
    int updatePassword(@Param("id") long id, @Param("hash") String hash);

    @Update("UPDATE user_account SET enabled = #{enabled}, token_version = token_version + 1 WHERE id = #{id}")
    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    @Update("UPDATE user_account SET role = #{role}, token_version = token_version + 1 WHERE id = #{id}")
    int updateRole(@Param("id") long id, @Param("role") String role);

    /** Capacity changes do not change identity or revoke existing login tokens. */
    @Update("UPDATE user_account SET quota_bytes = #{quotaBytes} WHERE id = #{id} AND quota_bytes <= #{quotaBytes}")
    int updateQuota(@Param("id") long id, @Param("quotaBytes") long quotaBytes);

    /**
     * Day16：取配额并加行锁。
     *
     * <p>为什么要 FOR UPDATE：配额判断是"读已用量 → 比较 → 写新记录"三步。
     * 不加锁的话两个并发上传可以同时读到"还剩 1MB"，然后各写入 1MB，最终超额。
     * 对账号行加锁把同一用户的额度判断串行化。</p>
     */
    @Select("SELECT quota_bytes FROM user_account WHERE id = #{id} FOR UPDATE")
    Optional<Long> lockQuotaById(@Param("id") long id);

    @Delete("DELETE FROM user_account")
    int deleteAll();
}
