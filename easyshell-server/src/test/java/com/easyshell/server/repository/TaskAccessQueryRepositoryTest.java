package com.easyshell.server.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskAccessQueryRepositoryTest {
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private String visible;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:access_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(ds);
        for (String ddl : List.of(
                "CREATE TABLE task (id VARCHAR(64), created_by BIGINT, total_count INT, status INT, created_at INT, source VARCHAR(32))",
                "CREATE TABLE sys_user (id BIGINT, status INT, role VARCHAR(32))",
                "CREATE TABLE host_acl_state (id INT, completed INT)",
                "CREATE TABLE task_target_snapshot (task_id VARCHAR(64), target_count INT, completed INT)",
                "CREATE TABLE task_target_snapshot_item (task_id VARCHAR(64), agent_id VARCHAR(64), generation BIGINT)",
                "CREATE TABLE host_identity (agent_id VARCHAR(64), generation BIGINT, active INT)",
                "CREATE TABLE agent (id VARCHAR(64))",
                "CREATE TABLE host_acl_grant (agent_id VARCHAR(64), generation BIGINT, permission VARCHAR(32), subject_type VARCHAR(8), subject_id VARCHAR(64))",
                "CREATE TABLE job (id VARCHAR(64), task_id VARCHAR(64), agent_id VARCHAR(64), created_at INT)",
                "CREATE TABLE workflow_node_run (task_id VARCHAR(64), run_id VARCHAR(64))",
                "CREATE TABLE workflow_run_provenance_task (run_id VARCHAR(64), task_id VARCHAR(64))")) jdbc.execute(ddl);
        jdbc.update("INSERT INTO sys_user VALUES (1, 1, 'operator'), (2, 1, 'admin'), (3, 0, 'admin')");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        jdbc.update("INSERT INTO agent VALUES ('a'), ('b')");
        jdbc.update("INSERT INTO host_identity VALUES ('a', 1, 1), ('b', 2, 1)");
        jdbc.update("INSERT INTO host_acl_grant VALUES ('a', 1, 'VIEW', 'ROLE', 'operator'), ('b', 2, 'VIEW', 'USER', '1'), ('a', 1, 'VIEW', 'ROLE', 'admin')");
        Field field = TaskAccessQueryRepository.class.getDeclaredField("VISIBLE");
        field.setAccessible(true);
        visible = (String) field.get(null);
    }

    private void task(String id, int owner, int count, int order) {
        jdbc.update("INSERT INTO task VALUES (?, ?, ?, 1, ?, 'manual')", id, owner, count, order);
    }

    private void snapshot(String id, int count, int completed, String... agents) {
        jdbc.update("INSERT INTO task_target_snapshot VALUES (?, ?, ?)", id, count, completed);
        for (String agent : agents) jdbc.update("INSERT INTO task_target_snapshot_item VALUES (?, ?, ?)", id, agent, agent.equals("a") ? 1 : 2);
    }

    private List<String> ids(long actor, String suffix) {
        return named.queryForList("SELECT t.id" + visible + suffix,
                Map.of("actor", actor, "subject", Long.toString(actor)), String.class);
    }

    @Test
    void filteringPrecedesPaginationAndCountAndJobs() {
        task("visible", 1, 2, 4); snapshot("visible", 2, 1, "a", "b");
        task("other", 2, 1, 3); snapshot("other", 1, 1, "a");
        task("unknown", 1, 1, 5);
        task("partial", 1, 2, 6); snapshot("partial", 2, 1, "a");
        task("extraJob", 1, 1, 7); snapshot("extraJob", 1, 1, "a");
        task("workflow", 1, 1, 8); snapshot("workflow", 1, 1, "a");
        jdbc.update("UPDATE task SET source = 'workflow' WHERE id = 'workflow'");
        task("linked", 1, 1, 9); snapshot("linked", 1, 1, "a");
        jdbc.update("INSERT INTO workflow_node_run VALUES ('linked', 'run')");
        task("provenance", 1, 1, 10); snapshot("provenance", 1, 1, "a");
        jdbc.update("INSERT INTO workflow_run_provenance_task VALUES ('run', 'provenance')");
        jdbc.update("INSERT INTO job VALUES ('j1', 'visible', 'a', 1), ('j2', 'extraJob', 'b', 1)");
        assertEquals(List.of("visible"), ids(1, "ORDER BY t.created_at DESC LIMIT 1 OFFSET 0"));
        assertTrue(ids(1, "ORDER BY t.created_at DESC LIMIT 1 OFFSET 1").isEmpty());
        assertEquals(1L, named.queryForObject("SELECT COUNT(*)" + visible,
                Map.of("actor", 1L, "subject", "1"), Long.class));
        assertEquals(List.of("j1"), named.queryForList("SELECT jobs.id FROM job jobs WHERE jobs.agent_id = 'a' AND jobs.task_id IN (SELECT t.id" + visible + ")",
                Map.of("actor", 1L, "subject", "1"), String.class));
        assertEquals(List.of("other"), ids(2, "ORDER BY t.created_at DESC"));
        assertTrue(ids(3, "").isEmpty());
        jdbc.update("UPDATE host_identity SET active = 0 WHERE agent_id = 'b'");
        assertTrue(ids(1, "").isEmpty());
    }

    @Test
    void dashboardCountsUseTheSameAclAndRejectUnknownOrRecreatedTargets() throws Exception {
        task("success", 1, 1, 10); snapshot("success", 1, 1, "a");
        task("failed", 1, 1, 11); snapshot("failed", 1, 1, "b");
        task("older", 1, 1, 1); snapshot("older", 1, 1, "a");
        task("unknown", 1, 1, 12);
        task("other-owner", 2, 1, 13); snapshot("other-owner", 1, 1, "a");
        jdbc.update("UPDATE task SET status=2 WHERE id IN ('success','older','other-owner','unknown')");
        jdbc.update("UPDATE task SET status=4 WHERE id='failed'");
        Field field=TaskAccessQueryRepository.class.getDeclaredField("DASHBOARD_SELECT");field.setAccessible(true);
        String query=(String)field.get(null);
        var parameters=Map.of("actor",1L,"subject","1","day",5);
        List<Number> counts=named.queryForObject(query,parameters,(rs,row)->List.of(
                (Number)rs.getObject(1),(Number)rs.getObject(2),(Number)rs.getObject(3),
                (Number)rs.getObject(4),(Number)rs.getObject(5)));
        assertEquals(List.of(3L,2L,1L,1L,2L),counts.stream().map(Number::longValue).toList());
        jdbc.update("UPDATE host_identity SET generation=3 WHERE agent_id='b'");
        assertEquals(2L,named.<Long>queryForObject(query,parameters,(rs,row)->rs.getLong(1)).longValue());
        jdbc.update("DELETE FROM host_acl_grant WHERE agent_id='a'");
        assertEquals(0L,named.<Long>queryForObject(query,parameters,(rs,row)->rs.getLong(1)).longValue());
    }

    @Test
    void incompleteMigrationAndDatabaseFailureAreNotEmptyResults() {
        var em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        var query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        org.mockito.Mockito.when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(query);
        org.mockito.Mockito.when(query.getResultList()).thenReturn(List.of(0));
        var repo = new TaskAccessQueryRepository(em);
        assertThrows(com.easyshell.server.common.exception.BusinessException.class, () -> repo.list(1));
        org.mockito.Mockito.when(query.getResultList()).thenThrow(new IllegalStateException("DB unavailable"));
        assertThrows(IllegalStateException.class, () -> repo.list(1));
    }
}
