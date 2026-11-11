package com.easyshell.server.ai.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InspectReportQueryRepositoryTest {
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private final Map<String, Object> actor = Map.of("actor", 1L, "subject", "1");

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:reports_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); named = new NamedParameterJdbcTemplate(ds);
        for (String ddl : List.of(
                "CREATE TABLE ai_inspect_report (id BIGINT PRIMARY KEY, created_by BIGINT, created_at INT)",
                "CREATE TABLE ai_inspect_report_task_binding (report_id BIGINT PRIMARY KEY, task_id VARCHAR(64) UNIQUE)",
                "CREATE TABLE task (id VARCHAR(64) PRIMARY KEY, created_by BIGINT, total_count INT, source VARCHAR(32))",
                "CREATE TABLE workflow_node_run (task_id VARCHAR(64))",
                "CREATE TABLE workflow_run_provenance_task (task_id VARCHAR(64))",
                "CREATE TABLE workflow_dispatch_approval_binding (dispatch_task_id VARCHAR(64))",
                "CREATE TABLE task_target_snapshot (task_id VARCHAR(64) PRIMARY KEY, completed INT, target_count INT)",
                "CREATE TABLE task_target_snapshot_item (task_id VARCHAR(64), agent_id VARCHAR(64), generation BIGINT, PRIMARY KEY(task_id, agent_id))",
                "CREATE TABLE job (id VARCHAR(64), task_id VARCHAR(64), agent_id VARCHAR(64))",
                "CREATE TABLE sys_user (id BIGINT, status INT, role VARCHAR(32))",
                "CREATE TABLE host_acl_state (id INT, completed INT)",
                "CREATE TABLE host_identity (agent_id VARCHAR(64), generation BIGINT, active INT)",
                "CREATE TABLE agent (id VARCHAR(64))",
                "CREATE TABLE host_acl_grant (agent_id VARCHAR(64), generation BIGINT, permission VARCHAR(32), subject_type VARCHAR(8), subject_id VARCHAR(64))")) jdbc.execute(ddl);
        jdbc.update("INSERT INTO sys_user VALUES (1, 1, 'operator'), (2, 1, 'admin'), (3, 0, 'admin')");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        jdbc.update("INSERT INTO agent VALUES ('a'), ('b')");
        jdbc.update("INSERT INTO host_identity VALUES ('a', 1, 1), ('b', 2, 1)");
        jdbc.update("INSERT INTO host_acl_grant VALUES ('a', 1, 'VIEW', 'ROLE', 'operator'), ('b', 2, 'VIEW', 'USER', '1'), ('a', 1, 'VIEW', 'ROLE', 'admin')");
    }

    private void report(long id, long owner, String... hosts) {
        String task = "t" + id;
        jdbc.update("INSERT INTO ai_inspect_report VALUES (?, ?, ?)", id, owner, id);
        jdbc.update("INSERT INTO task VALUES (?, ?, ?, 'user')", task, owner, hosts.length);
        jdbc.update("INSERT INTO ai_inspect_report_task_binding VALUES (?, ?)", id, task);
        jdbc.update("INSERT INTO task_target_snapshot VALUES (?, 1, ?)", task, hosts.length);
        for (String host : hosts) {
            jdbc.update("INSERT INTO task_target_snapshot_item VALUES (?, ?, ?)", task, host, host.equals("a") ? 1 : 2);
            jdbc.update("INSERT INTO job VALUES (?, ?, ?)", task + host, task, host);
        }
    }
    private List<Long> ids(Map<String, Object> user, String suffix) {
        return named.queryForList("SELECT r.id" + InspectReportQueryRepository.VISIBLE + suffix, user, Long.class);
    }
    private long count() {
        return named.queryForObject("SELECT COUNT(*)" + InspectReportQueryRepository.VISIBLE, actor, Long.class);
    }

    @Test void filtersBeforeCountAndPagination() {
        report(1, 1, "a", "b"); report(2, 1, "a"); report(3, 2, "a");
        jdbc.update("INSERT INTO ai_inspect_report VALUES (10, 1, 10)");
        report(9, 1, "a"); jdbc.update("UPDATE task_target_snapshot SET completed = 0 WHERE task_id = 't9'");
        report(8, 1, "a"); jdbc.update("UPDATE task SET created_by = 2 WHERE id = 't8'");
        report(7, 1, "a", "b"); jdbc.update("DELETE FROM task_target_snapshot_item WHERE task_id = 't7' AND agent_id = 'b'");
        assertEquals(2, count());
        assertEquals(List.of(2L), ids(actor, " ORDER BY r.created_at DESC, r.id DESC LIMIT 1 OFFSET 0"));
        assertEquals(List.of(1L), ids(actor, " ORDER BY r.created_at DESC, r.id DESC LIMIT 1 OFFSET 1"));
        assertEquals(List.of(3L, 2L), ids(Map.of("actor", 2L, "subject", "2"), " ORDER BY r.id DESC"));
        assertTrue(ids(Map.of("actor", 3L, "subject", "3"), "").isEmpty());
    }

    @Test void requiresExactJobsAndAllCurrentGenerationGrants() {
        report(1, 1, "a", "b");
        assertEquals(1, count());
        jdbc.update("DELETE FROM job WHERE task_id = 't1' AND agent_id = 'b'"); assertEquals(0, count());
        jdbc.update("INSERT INTO job VALUES ('b', 't1', 'b'), ('extra', 't1', 'a')"); assertEquals(0, count());
        jdbc.update("DELETE FROM job WHERE id = 'extra'");
        jdbc.update("UPDATE job SET agent_id = 'unknown' WHERE id = 'b'"); assertEquals(0, count());
        jdbc.update("UPDATE job SET agent_id = 'b' WHERE id = 'b'");
        jdbc.update("UPDATE host_identity SET generation = 3 WHERE agent_id = 'b'"); assertEquals(0, count());
        jdbc.update("UPDATE host_identity SET generation = 2 WHERE agent_id = 'b'");
        jdbc.update("DELETE FROM host_acl_grant WHERE agent_id = 'b'"); assertEquals(0, count());
        jdbc.update("UPDATE host_acl_state SET completed = 0"); assertEquals(0, count());
    }

    @Test void damagedSnapshotsAndOwnerMismatchAreExcluded() {
        report(1, 1, "a");
        jdbc.update("UPDATE task_target_snapshot_item SET generation = 0 WHERE task_id = 't1'");
        assertEquals(0, count());
        jdbc.update("UPDATE task_target_snapshot_item SET generation = 1 WHERE task_id = 't1'");
        jdbc.update("UPDATE task_target_snapshot SET target_count = 2 WHERE task_id = 't1'");
        assertEquals(0, count());
        jdbc.update("UPDATE task_target_snapshot SET target_count = 1 WHERE task_id = 't1'");
        jdbc.update("UPDATE ai_inspect_report SET created_by = 2 WHERE id = 1");
        assertEquals(0, count());
        assertTrue(ids(Map.of("actor", 2L, "subject", "2"), "").isEmpty());
    }

    @Test void excludesWorkflowSourceAndEveryWorkflowAssociation() {
        report(1, 1, "a");
        assertEquals(1, count());
        jdbc.update("UPDATE task SET source = 'workflow' WHERE id = 't1'"); assertEquals(0, count());
        jdbc.update("UPDATE task SET source = NULL WHERE id = 't1'"); assertEquals(0, count());
        jdbc.update("UPDATE task SET source = 'user' WHERE id = 't1'");
        for (String table : List.of("workflow_node_run", "workflow_run_provenance_task", "workflow_dispatch_approval_binding")) {
            jdbc.update("INSERT INTO " + table + " VALUES ('t1')");
            assertEquals(0, count());
            jdbc.update("DELETE FROM " + table);
            assertEquals(1, count());
        }
    }

    @Test void migrationFailureAndDatabaseFailureDoNotProduceEmptyPages() {
        var em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        var query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        org.mockito.Mockito.when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(query);
        org.mockito.Mockito.when(query.getResultList()).thenReturn(List.of(0));
        var repository = new InspectReportQueryRepository(em);
        assertThrows(com.easyshell.server.common.exception.BusinessException.class, () -> repository.page(1, 0, 10));
        org.mockito.Mockito.when(query.getResultList()).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> repository.page(1, 0, 10));
    }
}
