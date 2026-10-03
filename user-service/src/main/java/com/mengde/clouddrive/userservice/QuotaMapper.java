package com.mengde.clouddrive.userservice;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@org.apache.ibatis.annotations.Mapper
interface QuotaMapper {
    @Select("SELECT quota_bytes FROM user_profile WHERE user_id = #{userId}")
    Long findQuota(long userId);
    @Select("SELECT quota_bytes FROM user_profile WHERE user_id = #{userId} FOR UPDATE")
    Long lockQuota(@Param("userId") long userId);

    @Insert("INSERT IGNORE INTO user_profile(user_id, quota_bytes) VALUES(#{userId}, #{quotaBytes})")
    int initialize(@Param("userId") long userId, @Param("quotaBytes") long quotaBytes);

    @Select("SELECT reservation_id AS reservationId, user_id AS userId, amount, state FROM quota_reservations WHERE reservation_id = #{id}")
    Reservation find(@Param("id") String id);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM quota_reservations WHERE user_id = #{userId} AND state = 'RESERVED'")
    long activeAmount(@Param("userId") long userId);

    @Insert("INSERT INTO quota_reservations(reservation_id, user_id, amount, state, expires_at) VALUES(#{id}, #{userId}, #{amount}, 'RESERVED', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY))")
    int reserve(@Param("id") String id, @Param("userId") long userId, @Param("amount") long amount);

    @Insert("INSERT INTO quota_reservations(reservation_id, user_id, amount, state, expires_at) VALUES(#{id}, #{userId}, 0, 'RELEASED', CURRENT_TIMESTAMP)")
    int tombstone(@Param("id") String id, @Param("userId") long userId);

    @Update("UPDATE quota_reservations SET state = #{state} WHERE reservation_id = #{id} AND state = 'RESERVED'")
    int transition(@Param("id") String id, @Param("state") String state);

    record Reservation(String reservationId, long userId, long amount, String state) { }
}
