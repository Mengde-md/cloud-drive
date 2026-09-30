package com.mengde.clouddrive.authservice;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * auth-service 侧的 user_account 访问。
 *
 * 与单体 com.mengde.clouddrive.auth.UserAccountMapper 的差别是**故意的**，
 * 差在两处，都对应 Day23 的归属判定：
 *   1. 没有 lockQuotaById —— 配额是 user-service 的数据，auth-service 不得触碰；
 *   2. 没有 deleteAll —— 那是单体测试清理用的后门，不属于生产契约，不该出现在
 *      一个独立部署单元的 Mapper 里。
 * SELECT 也只取身份四列，不写 `SELECT *`：拆分后列的增减由各自服务决定，
 * `SELECT *` 会让另一个服务加一列就把这边的映射打翻。
 */
@Mapper
public interface UserAccountMapper {

    @Insert("INSERT INTO user_account(username, password_hash, role) VALUES(#{username}, #{passwordHash}, #{role})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserAccountRow row);

    @Select("SELECT id, username, password_hash AS passwordHash, role FROM user_account WHERE username = #{username}")
    Optional<UserAccountRow> findByUsername(String username);

    @Select("SELECT id, username, password_hash AS passwordHash, role FROM user_account WHERE id = #{id}")
    Optional<UserAccountRow> findById(long id);
}
