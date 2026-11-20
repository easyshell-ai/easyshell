package com.easyshell.server.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.vo.HostPermissionQueryVO;
import com.easyshell.server.repository.HostPermissionQueryRepository;
import com.easyshell.server.repository.HostAclRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostPermissionQueryTest {
    @Test
    void executeTargetsUsesCurrentAuthorityWithoutRequiringViewAndFailsClosed() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:execute_targets_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE host_acl_state (id INT PRIMARY KEY, completed INT)");
        jdbc.execute("CREATE TABLE host_identity (agent_id VARCHAR(64) PRIMARY KEY, generation BIGINT, active INT)");
        jdbc.execute("CREATE TABLE agent (id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, role VARCHAR(32), status INT)");
        jdbc.execute("CREATE TABLE host_acl_grant (agent_id VARCHAR(64), generation BIGINT, subject_type VARCHAR(8), subject_id VARCHAR(64), permission VARCHAR(32))");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        jdbc.update("INSERT INTO sys_user VALUES (1, 'viewer', 1)");
        jdbc.update("INSERT INTO agent VALUES ('direct'), ('role'), ('old'), ('inactive'), ('view')");
        jdbc.update("INSERT INTO host_identity VALUES ('direct', 2, 1), ('role', 1, 1), ('old', 2, 1), "
                + "('inactive', 1, 0), ('view', 1, 1), ('missing', 1, 1)");
        jdbc.update("INSERT INTO host_acl_grant VALUES ('direct', 2, 'USER', '1', 'EXECUTE'), "
                + "('direct', 2, 'ROLE', 'viewer', 'EXECUTE'), ('role', 1, 'ROLE', 'viewer', 'EXECUTE'), "
                + "('old', 1, 'USER', '1', 'EXECUTE'), ('inactive', 1, 'USER', '1', 'EXECUTE'), "
                + "('missing', 1, 'USER', '1', 'EXECUTE'), ('view', 1, 'USER', '1', 'VIEW')");
        var service = new HostPermissionQueryService(new HostPermissionQueryRepository(jdbc));
        assertEquals(List.of("direct", "role"), service.executeTargets(1, null, 100).agentIds());
        var first = service.executeTargets(1, null, 1);
        assertEquals(List.of("direct"), first.agentIds());
        assertEquals("direct", first.nextCursor());
        var last = service.executeTargets(1, first.nextCursor(), 1);
        assertEquals(List.of("role"), last.agentIds());
        assertNull(last.nextCursor());
        assertTrue(service.executeTargets(1, "role", 1).agentIds().isEmpty());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.executeTargets(1, "", 1)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.executeTargets(1, "x".repeat(65), 1)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.executeTargets(1, null, 0)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.executeTargets(1, null, 101)).getCode());
        jdbc.update("DELETE FROM host_acl_grant WHERE agent_id = 'role'");
        assertTrue(service.executeTargets(1, first.nextCursor(), 1).agentIds().isEmpty());
        jdbc.update("INSERT INTO host_acl_grant VALUES ('role', 1, 'ROLE', 'viewer', 'EXECUTE')");
        var acl = new HostAclRepository(jdbc);
        for (String id : List.of("direct", "role", "old", "inactive", "missing", "view"))
            assertEquals(acl.authorizedGeneration(1, id, "EXECUTE").isPresent(), service.executeTargets(1, null, 100).agentIds().contains(id));
        jdbc.update("UPDATE sys_user SET role = 'operator'");
        assertEquals(List.of("direct"), service.executeTargets(1, null, 100).agentIds());
        jdbc.update("UPDATE sys_user SET role = 'unknown'");
        assertTrue(service.executeTargets(1, null, 100).agentIds().isEmpty());
        jdbc.update("UPDATE sys_user SET role = 'viewer', status = 0");
        assertTrue(service.executeTargets(1, null, 100).agentIds().isEmpty());
        assertTrue(service.executeTargets(2, null, 100).agentIds().isEmpty());
        jdbc.update("UPDATE host_acl_state SET completed = 0");
        assertEquals(503, assertThrows(BusinessException.class, () -> service.executeTargets(1, null, 100).agentIds()).getCode());
        assertEquals(503, assertThrows(BusinessException.class, () -> service.executeTargets(1, "role", 1)).getCode());
        jdbc.update("DELETE FROM host_acl_state");
        assertEquals(503, assertThrows(BusinessException.class, () -> service.executeTargets(1, null, 100).agentIds()).getCode());
        jdbc.execute("DROP TABLE host_acl_state");
        assertEquals(503, assertThrows(BusinessException.class, () -> service.executeTargets(1, null, 100).agentIds()).getCode());
    }

    @Test
    void terminalCeilingTracksRoleAndStatusWithoutChangingViewerCapabilities() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:permission_ceiling_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE host_acl_state (id INT PRIMARY KEY, completed INT)");
        jdbc.execute("CREATE TABLE host_identity (agent_id VARCHAR(64), generation BIGINT, active INT)");
        jdbc.execute("CREATE TABLE agent (id VARCHAR(64))");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT, role VARCHAR(32), status INT)");
        jdbc.execute("CREATE TABLE host_acl_grant (agent_id VARCHAR(64), generation BIGINT, subject_type VARCHAR(8), subject_id VARCHAR(64), permission VARCHAR(32))");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        jdbc.update("INSERT INTO host_identity VALUES ('host', 2, 1)");
        jdbc.update("INSERT INTO agent VALUES ('host')");
        jdbc.update("INSERT INTO sys_user VALUES (1, 'operator', 1)");
        jdbc.update("INSERT INTO host_acl_grant VALUES ('host', 2, 'USER', '1', 'VIEW'), "
                + "('host', 2, 'USER', '1', 'EXECUTE'), ('host', 2, 'USER', '1', 'TERMINAL'), "
                + "('host', 2, 'ROLE', 'viewer', 'TERMINAL')");
        var service = new HostPermissionQueryService(new HostPermissionQueryRepository(jdbc));
        var ids = List.of("host");
        assertEquals(List.of(new HostPermissionQueryVO("host", 2, true, true, true)), service.query(1, ids));
        jdbc.update("UPDATE sys_user SET role = 'viewer'");
        assertEquals(List.of(new HostPermissionQueryVO("host", 2, true, true, false)), service.query(1, ids));
        jdbc.update("UPDATE sys_user SET role = 'operator', status = 0");
        assertTrue(service.query(1, ids).isEmpty());
        jdbc.update("UPDATE sys_user SET status = 1");
        assertEquals(List.of(new HostPermissionQueryVO("host", 2, true, true, true)), service.query(1, ids));
    }

    @Test
    void usesCurrentUserActiveGenerationAndUnionOfGrants() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:permissions_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE host_acl_state (id INT PRIMARY KEY, completed INT)");
        jdbc.execute("CREATE TABLE host_identity (agent_id VARCHAR(64) PRIMARY KEY, generation BIGINT, active INT)");
        jdbc.execute("CREATE TABLE agent (id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, role VARCHAR(32), status INT)");
        jdbc.execute("CREATE TABLE host_acl_grant (agent_id VARCHAR(64), generation BIGINT, subject_type VARCHAR(8), subject_id VARCHAR(64), permission VARCHAR(32))");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        jdbc.update("INSERT INTO sys_user VALUES (1, 'operator', 1)");
        jdbc.update("INSERT INTO agent VALUES ('a'), ('b'), ('c')");
        jdbc.update("INSERT INTO host_identity VALUES ('a', 2, 1), ('b', 1, 1), ('c', 1, 0), ('missing', 1, 1)");
        jdbc.update("INSERT INTO host_acl_grant VALUES ('a', 2, 'ROLE', 'operator', 'VIEW'), "
                + "('a', 2, 'USER', '1', 'EXECUTE'), ('a', 1, 'USER', '1', 'TERMINAL'), "
                + "('b', 1, 'USER', '1', 'TERMINAL'), ('c', 1, 'USER', '1', 'VIEW'), "
                + "('missing', 1, 'USER', '1', 'VIEW')");
        var service = new HostPermissionQueryService(new HostPermissionQueryRepository(jdbc));
        var ids = List.of("a", "b", "c", "missing", "unknown");
        assertEquals(List.of(new HostPermissionQueryVO("a", 2, true, true, false)), service.query(1, ids));
        assertTrue(service.query(1, List.of()).isEmpty());
        jdbc.update("UPDATE sys_user SET role = 'viewer'");
        assertTrue(service.query(1, ids).isEmpty());
        jdbc.update("UPDATE sys_user SET role = 'operator', status = 0");
        assertTrue(service.query(1, ids).isEmpty());
        jdbc.update("UPDATE host_acl_state SET completed = 0");
        assertEquals(503, assertThrows(BusinessException.class, () -> service.query(1, List.of())).getCode());
        jdbc.execute("DROP TABLE host_acl_state");
        assertEquals(503, assertThrows(BusinessException.class, () -> service.query(1, ids)).getCode());
    }
}
