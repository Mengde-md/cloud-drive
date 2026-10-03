package com.mengde.clouddrive.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

public interface QuotaOperationMapper {
    @Insert("INSERT INTO file_quota_operations(reservation_id, owner_id, phase, storage_key) VALUES(#{id}, #{owner}, 'PREPARED', #{key})")
    void prepare(@Param("id") String id, @Param("owner") long owner, @Param("key") String key);

    @Select("SELECT reservation_id AS id, owner_id AS owner, phase, storage_key AS storageKey FROM file_quota_operations WHERE reservation_id = #{id} FOR UPDATE")
    Operation lock(String id);

    @Update("UPDATE file_quota_operations SET phase = 'COMMIT_PENDING' WHERE reservation_id = #{id} AND phase = 'PREPARED'")
    int committed(String id);

    @Update("UPDATE file_quota_operations SET phase = 'RELEASE_PENDING' WHERE reservation_id = #{id} AND phase = 'PREPARED'")
    int cancelling(String id);

    @Update("UPDATE file_quota_operations SET phase = 'DONE' WHERE reservation_id = #{id} AND phase = #{phase}")
    int done(@Param("id") String id, @Param("phase") String phase);

    @Select("SELECT reservation_id AS id, owner_id AS owner, phase, storage_key AS storageKey FROM file_quota_operations WHERE owner_id = #{owner} AND phase <> 'DONE' ORDER BY created_at, reservation_id")
    List<Operation> pending(long owner);

    record Operation(String id, long owner, String phase, String storageKey) { }
}
