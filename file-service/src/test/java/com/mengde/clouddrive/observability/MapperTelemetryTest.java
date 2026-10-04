package com.mengde.clouddrive.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.annotations.Select;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class MapperTelemetryTest {
    interface Probe {
        @Select("SELECT SLEEP(0.06)") int slow();
        @Select("SELECT #{value}") String echo(String value);
        @Select("SELECT * FROM day28_deliberately_missing_table") int broken();
    }
    private Probe probe;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(MapperTelemetry.class);
    @BeforeEach void setup() throws Exception {
        var data = new DriverManagerDataSource(
                System.getenv().getOrDefault("CLOUDDRIVE_DB_URL", "jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
                System.getenv("CLOUDDRIVE_DB_USERNAME"), System.getenv("CLOUDDRIVE_DB_PASSWORD"));
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(data);
        factory.setPlugins(new MapperTelemetry(meters, 0));
        var configuration = new org.apache.ibatis.session.Configuration(); configuration.addMapper(Probe.class);
        factory.setConfiguration(configuration);
        probe = new SqlSessionTemplate(factory.getObject()).getMapper(Probe.class);
        captured.start(); logger.addAppender(captured);
        MDC.put("traceId", "abcdef1234567890abcdef1234567890");
    }
    @AfterEach void close() { logger.detachAppender(captured); captured.stop(); meters.close(); MDC.clear(); }
    @Test void realMysqlSleepIsTimedExactlyOnceAndLeavesReturnValueUnchanged() {
        assertThat(probe.slow()).isZero();
        var timer = meters.get("clouddrive.mapper.calls").tag("outcome", "SUCCESS").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(50);
        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.get(0).getFormattedMessage()).contains("event=mapper_call", "slow=true", "Probe.slow");
    }
    @Test void databaseFailureIsCountedButNotConvertedIntoSuccess() {
        assertThatThrownBy(probe::broken).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(meters.get("clouddrive.mapper.calls").tag("outcome", "ERROR").timer().count()).isEqualTo(1);
        assertThat(captured.list.get(0).getFormattedMessage()).contains("outcome=ERROR").doesNotContain("SELECT", "doesn't exist");
    }
    @Test void parametersAndResultsNeverBecomeLogFieldsOrMetricTags() {
        assertThat(probe.echo("private-owner-private-search-term")).isEqualTo("private-owner-private-search-term");
        assertThat(captured.list.get(0).getFormattedMessage()).doesNotContain("private-owner-private-search-term", "SELECT");
        assertThat(meters.getMeters().get(0).getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrder("statement", "outcome");
    }
}
