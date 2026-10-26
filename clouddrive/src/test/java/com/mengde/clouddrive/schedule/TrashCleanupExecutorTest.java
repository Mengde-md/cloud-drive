package com.mengde.clouddrive.schedule;

import com.mengde.clouddrive.application.TrashCleanupExecutorConfiguration;
import com.mengde.clouddrive.application.TrashPurgeService;
import com.mengde.clouddrive.persistence.DriveEntryMapper;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.storage.LocalObjectStorage;
import com.mengde.clouddrive.storage.StorageProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real production cleanup executor, H2 and a one-connection Hikari pool; no network services. */
class TrashCleanupExecutorTest {
    @TempDir Path root;

    @Test
    void singleConnectionPoolCanCommitAndThenCleanOnTheWorkerWithoutBorrowingOnTheCommitThread() throws Exception {
        try (HikariDataSource source = new HikariDataSource();
             AnnotationConfigApplicationContext workers = new AnnotationConfigApplicationContext(
                     TrashCleanupExecutorConfiguration.class)) {
            source.setJdbcUrl("jdbc:h2:mem:trash_one_connection_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            source.setMaximumPoolSize(1);
            source.setMinimumIdle(1);
            source.setConnectionTimeout(1000);
            JdbcTemplate db = new JdbcTemplate(source);
            db.execute("CREATE TABLE drive_entries (id BIGINT PRIMARY KEY, owner_id BIGINT, "
                    + "deleted_at TIMESTAMP, storage_key VARCHAR(255))");

            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.addMapper(DriveEntryMapper.class);
            factory.setConfiguration(configuration);
            DriveEntryRepository entries = new DriveEntryRepository(
                    new SqlSessionTemplate(factory.getObject()).getMapper(DriveEntryMapper.class));
            StorageProperties properties = new StorageProperties();
            properties.setRoot(root.toString());
            AtomicReference<String> deletionThread = new AtomicReference<>();
            LocalObjectStorage storage = new LocalObjectStorage(properties) {
                @Override public void delete(String key) throws IOException {
                    deletionThread.set(Thread.currentThread().getName());
                    super.delete(key);
                }
            };
            var object = storage.store(new ByteArrayInputStream("disposable".getBytes(StandardCharsets.UTF_8)), "x.txt");
            db.update("INSERT INTO drive_entries VALUES (1, 1, CURRENT_TIMESTAMP, ?)", object.storageKey());
            ThreadPoolTaskExecutor executor = workers.getBean("trashObjectCleanupExecutor", ThreadPoolTaskExecutor.class);
            var transactions = new DataSourceTransactionManager(source);
            TrashPurgeService purge = new TrashPurgeService(entries, storage, transactions, executor);

            new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                assertEquals(1, purge.purgeOwner(1));
                assertEquals(1, source.getHikariPoolMXBean().getActiveConnections());
                assertTrue(Files.exists(root.resolve(object.storageKey())));
            });
            // FIFO single-worker barrier waits for the cleanup task, not for an arbitrary delay.
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM drive_entries", Integer.class));
            assertFalse(Files.exists(root.resolve(object.storageKey())));
            assertNotNull(deletionThread.get());
            assertTrue(deletionThread.get().startsWith("trash-object-cleanup-"));
            assertEquals(1, source.getHikariPoolMXBean().getTotalConnections());
        }
    }

    @Test
    void actualBoundedExecutorRejectsOverflowWithoutCallerRunsFallback() throws Exception {
        try (AnnotationConfigApplicationContext workers = new AnnotationConfigApplicationContext(
                TrashCleanupExecutorConfiguration.class)) {
            ThreadPoolTaskExecutor executor = workers.getBean("trashObjectCleanupExecutor", ThreadPoolTaskExecutor.class);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean rejectedTaskRan = new AtomicBoolean();
            executor.execute(() -> {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test worker was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 64; i++) {
                    executor.execute(() -> { });
                }
                assertThrows(TaskRejectedException.class, () -> executor.execute(() -> rejectedTaskRan.set(true)));
                assertFalse(rejectedTaskRan.get());
            } finally {
                release.countDown();
            }
        }
    }
}
