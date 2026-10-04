package com.mengde.clouddrive.persistence;

import com.mengde.clouddrive.search.SearchDocument;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface FileSearchMapper {
    @Insert("INSERT INTO file_search_state(owner_id) VALUES(#{owner}) ON DUPLICATE KEY UPDATE owner_id = owner_id")
    void ensure(long owner);

    @Insert("INSERT INTO file_search_state(owner_id, revision) VALUES(#{owner}, 1) ON DUPLICATE KEY UPDATE revision = revision + 1")
    void changed(long owner);

    @Select("SELECT revision, indexed_revision AS indexedRevision, generation FROM file_search_state WHERE owner_id = #{owner} FOR UPDATE")
    State lock(long owner);

    @Update("UPDATE file_search_state SET indexed_revision = #{revision}, generation = #{generation} WHERE owner_id = #{owner}")
    void publish(@Param("owner") long owner, @Param("revision") long revision, @Param("generation") String generation);

    String COLUMNS = "id AS fileId, owner_id AS ownerId, parent_id AS parentId, display_name AS displayName, "
            + "byte_size AS byteSize, NULL AS generation, kind";
    String ACTIVE = " FROM drive_entries WHERE owner_id = #{owner} AND kind = 'DOCUMENT' AND deleted_at IS NULL";

    @Select("SELECT " + COLUMNS + ACTIVE + " ORDER BY id DESC LIMIT 1001")
    List<SearchDocument> snapshot(long owner);

    @Select("SELECT " + COLUMNS + ACTIVE + " AND id = #{id}")
    SearchDocument owned(@Param("owner") long owner, @Param("id") long id);

    @Select("SELECT COUNT(*)" + ACTIVE + " AND LOCATE(CAST(#{q} AS BINARY), CAST(display_name AS BINARY)) > 0")
    long count(@Param("owner") long owner, @Param("q") String q);

    @Select("SELECT " + COLUMNS + ACTIVE + " AND LOCATE(CAST(#{q} AS BINARY), CAST(display_name AS BINARY)) > 0 ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<SearchDocument> page(@Param("owner") long owner, @Param("q") String q,
                              @Param("offset") int offset, @Param("size") int size);

    record State(long revision, long indexedRevision, String generation) {
        public boolean ready() { return generation != null && revision == indexedRevision; }
    }
}
