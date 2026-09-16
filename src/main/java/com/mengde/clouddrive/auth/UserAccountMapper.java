package com.mengde.clouddrive.auth;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;
import java.util.Optional;

@Mapper
public interface UserAccountMapper {
    @Insert("INSERT INTO user_account(username, password_hash, role) VALUES(#{username}, #{passwordHash}, #{role})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserAccountRow row);
    @Select("SELECT id, username, password_hash AS passwordHash, role FROM user_account WHERE username = #{username}")
    Optional<UserAccountRow> findByUsername(String username);
    @Delete("DELETE FROM user_account") int deleteAll();
}
