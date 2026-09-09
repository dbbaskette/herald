package com.herald.cron;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CronCommandPollerTest {
    @TempDir Path temp;
    JdbcTemplate jdbc;
    CronService service;
    CronCommandPoller poller;
    @BeforeEach void setup() {
        var source = DataSourceBuilder.create().driverClassName("org.sqlite.JDBC").url("jdbc:sqlite:" + temp.resolve("commands.db")).build();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE commands(id INTEGER PRIMARY KEY,type TEXT,payload TEXT,status TEXT DEFAULT 'pending',completed_at TEXT)");
        jdbc.execute("CREATE TABLE cron_execution(job_id INTEGER PRIMARY KEY,status TEXT,message TEXT,updated_at TEXT)");
        service = mock(CronService.class);
        poller = new CronCommandPoller(jdbc,service);
    }
    @Test void consumesOnlyCronCommandsAndSynchronizesCurrentJobOrDeletion() {
        var job = new CronJob(1,"job","0 9 * * *","hello",null,false,true);
        when(service.findJobById(1)).thenReturn(job);
        jdbc.update("INSERT INTO commands(id,type,payload) VALUES(1,'SYNC_CRON','1'),(2,'SYNC_CRON','2'),(3,'OTHER','1')");
        poller.poll();
        verify(service).rescheduleJob(job);
        verify(service).cancelJob(2);
        assertThat(jdbc.queryForList("SELECT status FROM commands ORDER BY id",String.class)).containsExactly("completed","completed","pending");
    }
    @Test void runIsRunningDuringExecutionAndFailureIsNotCompleted() {
        var job = new CronJob(1,"job","0 9 * * *","hello",null,true,false);
        when(service.findJobById(1)).thenReturn(job);
        doAnswer(call -> {
            assertThat(jdbc.queryForObject("SELECT status FROM commands WHERE id=1",String.class)).isEqualTo("running");
            jdbc.update("INSERT INTO cron_execution(job_id,status) VALUES(1,'failed')");
            return "failed";
        }).when(service).executeJob(job);
        jdbc.update("INSERT INTO commands(id,type,payload) VALUES(1,'RUN_CRON','1')");
        poller.poll(); poller.poll();
        verify(service,times(1)).executeJob(job);
        assertThat(jdbc.queryForObject("SELECT status FROM commands WHERE id=1",String.class)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM commands WHERE id=1",String.class)).isNotNull();
    }
    @Test void syncFailureAndSuccessfulRunHaveTruthfulTerminalStates() {
        var job = new CronJob(1,"job","0 9 * * *","hello",null,true,false);
        when(service.findJobById(1)).thenReturn(job);
        doThrow(new IllegalArgumentException("invalid cron")).when(service).rescheduleJob(job);
        doAnswer(call -> { jdbc.update("INSERT INTO cron_execution(job_id,status) VALUES(1,'completed')"); return "completed"; }).when(service).executeJob(job);
        jdbc.update("INSERT INTO commands(id,type,payload) VALUES(1,'SYNC_CRON','1'),(2,'RUN_CRON','1')");
        poller.poll();
        assertThat(jdbc.queryForList("SELECT status FROM commands ORDER BY id",String.class)).containsExactly("failed","completed");
    }

    @Test void restartMarksInterruptedRunsUnknownWithoutReplayingThem() {
        jdbc.update("INSERT INTO commands(id,type,payload,status) VALUES(1,'RUN_CRON','1','running'),(2,'OTHER','x','running')");
        jdbc.update("INSERT INTO cron_execution(job_id,status) VALUES(1,'running')");
        poller.recoverInterrupted();
        assertThat(jdbc.queryForObject("SELECT status FROM commands WHERE id=1",String.class)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT status FROM commands WHERE id=2",String.class)).isEqualTo("running");
        assertThat(jdbc.queryForObject("SELECT message FROM cron_execution WHERE job_id=1",String.class)).contains("outcome is unknown");
        poller.poll(); verifyNoInteractions(service);
    }
}
