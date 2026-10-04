package com.mengde.clouddrive.search;

import com.mengde.clouddrive.domain.EntryKind;
import com.mengde.clouddrive.persistence.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.ThreadLocalRandom;

class SearchDatabaseFixture implements AutoCloseable {
    final long owner = ThreadLocalRandom.current().nextLong(9100000000L, 9190000000L);
    final long other = owner + 100000000;
    final JdbcTemplate jdbc;
    final JdbcTransactionManager manager;
    final TransactionTemplate tx;
    final FileSearchMapper mapper;
    final DriveEntryRepository entries;

    SearchDatabaseFixture() throws Exception {
        var data = new DriverManagerDataSource(
                System.getenv().getOrDefault("CLOUDDRIVE_DB_URL", "jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
                System.getenv("CLOUDDRIVE_DB_USERNAME"), System.getenv("CLOUDDRIVE_DB_PASSWORD"));
        jdbc = new JdbcTemplate(data);
        manager = new JdbcTransactionManager(data);
        tx = new TransactionTemplate(manager);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(data);
        var config = new org.apache.ibatis.session.Configuration();
        config.addMapper(FileSearchMapper.class);
        config.addMapper(DriveEntryMapper.class);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(FileSearchMapper.class);
        entries = new DriveEntryRepository(session.getMapper(DriveEntryMapper.class), mapper);
    }
    long add(String name) { return add(owner, name, EntryKind.DOCUMENT); }
    long add(long user, String name, EntryKind kind) {
        return tx.execute(status -> entries.saveGenerated(user, null, name, kind, 7).id());
    }
    FileSearchMapper.State state() { return tx.execute(status -> mapper.lock(owner)); }
    @Override public void close() {
        jdbc.update("DELETE FROM drive_entries WHERE owner_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM file_search_state WHERE owner_id IN (?, ?)", owner, other);
    }
}
