package com.easyshell.server.service;

import com.easyshell.server.repository.HostAclRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostAclRepositoryTest {
    @Test
    void terminalRequiresCurrentEligibleRoleEvenWithUserOrViewerRoleGrant() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:ceiling_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE host_acl_state (id INT PRIMARY KEY, completed INT)");
        jdbc.execute("CREATE TABLE host_identity (agent_id VARCHAR(64), generation BIGINT, active INT)");
        jdbc.execute("CREATE TABLE agent (id VARCHAR(64))");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT, role VARCHAR(32), status INT)");
        jdbc.execute("CREATE TABLE host_acl_grant (agent_id VARCHAR(64), generation BIGINT, subject_type VARCHAR(8), subject_id VARCHAR(64), permission VARCHAR(32))");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        jdbc.update("INSERT INTO host_identity VALUES ('host', 2, 1)");
        jdbc.update("INSERT INTO agent VALUES ('host')");
        jdbc.update("INSERT INTO sys_user VALUES (1, 'operator', 1)");
        jdbc.update("INSERT INTO host_acl_grant VALUES ('host', 2, 'USER', '1', 'TERMINAL'), "
                + "('host', 2, 'ROLE', 'viewer', 'TERMINAL'), ('host', 2, 'USER', '1', 'VIEW'), "
                + "('host', 2, 'USER', '1', 'EXECUTE')");
        var acl = new HostAclRepository(jdbc);
        assertEquals(OptionalLong.of(2), acl.authorizedGeneration(1, "host", "TERMINAL"));
        jdbc.update("UPDATE sys_user SET role = 'viewer'");
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
        assertEquals(OptionalLong.of(2), acl.authorizedGeneration(1, "host", "VIEW"));
        assertEquals(OptionalLong.of(2), acl.authorizedGeneration(1, "host", "EXECUTE"));
        jdbc.update("UPDATE sys_user SET role = 'operator', status = 0");
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
        jdbc.update("UPDATE sys_user SET status = 1");
        assertEquals(OptionalLong.of(2), acl.authorizedGeneration(1, "host", "TERMINAL"));
        jdbc.update("DELETE FROM host_acl_grant WHERE permission = 'TERMINAL' AND subject_type = 'USER'");
        jdbc.update("UPDATE sys_user SET role = 'viewer'");
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
        jdbc.update("UPDATE sys_user SET role = 'admin'");
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
    }

    @Test
    void catalogRequiresCurrentAdministratorAndCompletedMigrationWithoutGrant() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:catalog_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE host_acl_state (id INT PRIMARY KEY, completed INT)");
        jdbc.execute("CREATE TABLE host_identity (agent_id VARCHAR(64) PRIMARY KEY, generation BIGINT, active INT)");
        jdbc.execute("CREATE TABLE agent (id VARCHAR(64) PRIMARY KEY, hostname VARCHAR(128), secret VARCHAR(128))");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, role VARCHAR(32), status INT)");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        jdbc.update("INSERT INTO sys_user VALUES (1, 'admin', 1)");
        jdbc.update("INSERT INTO agent VALUES ('a', 'host-a', 'secret'), ('b', 'host-b', 'secret')");
        jdbc.update("INSERT INTO host_identity VALUES ('a', 3, 1), ('b', 4, 0), ('missing', 1, 1)");
        var acl = new HostAclRepository(jdbc);
        assertEquals(java.util.List.of(new HostAclRepository.CatalogHost("a", "host-a", 3)), acl.catalog(1, 10, 0));
        assertTrue(acl.catalog(1, 10, 10).isEmpty());
        jdbc.update("UPDATE sys_user SET role = 'viewer'");
        assertEquals(403, assertThrows(com.easyshell.server.common.exception.BusinessException.class,
                () -> acl.catalog(1, 10, 10)).getCode());
        jdbc.update("UPDATE sys_user SET role = 'super_admin', status = 0");
        assertEquals(403, assertThrows(com.easyshell.server.common.exception.BusinessException.class,
                () -> acl.catalog(1, 10, 0)).getCode());
        jdbc.update("UPDATE sys_user SET status = 1");
        assertEquals(1, acl.catalog(1, 10, 0).size());
        jdbc.update("UPDATE host_acl_state SET completed = 0");
        assertEquals(503, assertThrows(com.easyshell.server.common.exception.BusinessException.class,
                () -> acl.catalog(1, 10, 0)).getCode());
    }

    @Test
    void authorizationRequiresMatchingActiveGenerationUserAndGrant() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:acl_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE host_acl_state (id INT PRIMARY KEY, completed INT)");
        jdbc.execute("CREATE TABLE host_identity (agent_id VARCHAR(64) PRIMARY KEY, generation BIGINT, active INT)");
        jdbc.execute("CREATE TABLE agent (id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, role VARCHAR(32), status INT)");
        jdbc.execute("CREATE TABLE host_acl_grant (agent_id VARCHAR(64), generation BIGINT, subject_type VARCHAR(8), subject_id VARCHAR(64), permission VARCHAR(32))");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        jdbc.update("INSERT INTO host_identity VALUES ('host', 2, 1)");
        jdbc.update("INSERT INTO agent VALUES ('host')");
        jdbc.update("INSERT INTO sys_user VALUES (1, 'admin', 1)");
        var acl = new HostAclRepository(jdbc);
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
        jdbc.update("INSERT INTO host_acl_grant VALUES ('host', 1, 'USER', '1', 'TERMINAL')");
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
        jdbc.update("INSERT INTO host_acl_grant VALUES ('host', 2, 'ROLE', 'admin', 'TERMINAL')");
        assertEquals(OptionalLong.of(2), acl.authorizedGeneration(1, "host", "TERMINAL"));
        assertTrue(acl.authorizedGeneration(1, "host", "EXECUTE").isEmpty());
        jdbc.update("UPDATE sys_user SET status = 0");
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
        jdbc.update("UPDATE sys_user SET status = 1");
        jdbc.update("UPDATE host_identity SET active = 0");
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
        jdbc.update("UPDATE host_identity SET active = 1");
        jdbc.update("DELETE FROM agent");
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
        jdbc.update("INSERT INTO agent VALUES ('host')");
        jdbc.update("UPDATE host_acl_state SET completed = 0");
        assertTrue(acl.authorizedGeneration(1, "host", "TERMINAL").isEmpty());
        assertTrue(acl.authorizedGeneration(1, "unknown", "TERMINAL").isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM host_identity", Integer.class));
    }
}
