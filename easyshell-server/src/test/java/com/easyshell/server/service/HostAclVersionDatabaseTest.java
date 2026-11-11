package com.easyshell.server.service;

import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.HostAclRepository;
import com.easyshell.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Optional;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostAclVersionDatabaseTest {
    @Test
    void presetChecksSnapshotPreservesUnknownAndRollsBackOnAuditFailure() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:acl_preset_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE host_identity (agent_id VARCHAR(64) PRIMARY KEY, acl_version BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE host_acl_state (id INT PRIMARY KEY, completed INT)");
        jdbc.execute("CREATE TABLE host_acl_grant (agent_id VARCHAR(64), generation BIGINT, subject_type VARCHAR(8), subject_id VARCHAR(64), permission VARCHAR(32), PRIMARY KEY (agent_id, generation, subject_type, subject_id, permission))");
        jdbc.update("INSERT INTO host_identity VALUES ('host', 0)");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        jdbc.update("INSERT INTO host_acl_grant VALUES ('host', 1, 'ROLE', 'operator', 'FUTURE')");
        var identities = mock(HostIdentityService.class);
        var agents = mock(AgentRepository.class);
        var users = mock(UserRepository.class);
        var audit = mock(AuditLogService.class);
        var admin = new User(); admin.setId(1L); admin.setRole("admin"); admin.setStatus(1); admin.setUsername("admin");
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(agents.existsById("host")).thenReturn(true);
        when(identities.lock("host")).thenAnswer(inv -> {
            jdbc.queryForObject("SELECT acl_version FROM host_identity WHERE agent_id = 'host' FOR UPDATE", Long.class);
            return new HostIdentityService.Identity("host", 1, true);
        });
        var factory = new ProxyFactory(new HostAclService(new HostAclRepository(jdbc), identities, agents, users, audit));
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        var service = (HostAclService) factory.getProxy();
        var snapshot = service.snapshot(1L, "host", "ROLE", "operator");
        assertEquals(Set.of("FUTURE"), snapshot.permissions());
        service.applyPreset(1L, "host", "ROLE", "operator", HostAclService.Preset.OPERATE, 1L, 0L, snapshot.permissions(), true);
        assertEquals(Set.of("FUTURE", "VIEW", "EXECUTE", "TERMINAL"), service.snapshot(1L, "host", "ROLE", "operator").permissions());
        assertThrows(com.easyshell.server.common.exception.BusinessException.class, () -> service.applyPreset(1L, "host", "ROLE", "operator", HostAclService.Preset.READ_ONLY, 1L, 0L, snapshot.permissions(), false));
        var updated = service.snapshot(1L, "host", "ROLE", "operator");
        assertThrows(com.easyshell.server.common.exception.BusinessException.class, () -> service.applyPreset(1L, "host", "ROLE", "operator", HostAclService.Preset.READ_ONLY, 1L, updated.aclVersion(), Set.of("VIEW"), false));
        assertThrows(com.easyshell.server.common.exception.BusinessException.class, () -> service.applyPreset(1L, "host", "ROLE", "operator", HostAclService.Preset.READ_ONLY, 2L, updated.aclVersion(), updated.permissions(), false));
        service.applyPreset(1L, "host", "ROLE", "operator", HostAclService.Preset.OPERATE, 1L, updated.aclVersion(), updated.permissions(), true);
        assertEquals(updated, service.snapshot(1L, "host", "ROLE", "operator"));
        doThrow(new DataAccessResourceFailureException("audit failed"))
                .when(audit).logSync(any(), any(), any(), any(), any(), any(), isNull(), any());
        assertThrows(DataAccessResourceFailureException.class, () -> service.applyPreset(1L, "host", "ROLE", "operator", HostAclService.Preset.READ_ONLY, 1L, updated.aclVersion(), updated.permissions(), false));
        assertEquals(updated, service.snapshot(1L, "host", "ROLE", "operator"));
        assertThrows(com.easyshell.server.common.exception.BusinessException.class, () -> service.applyPreset(1L, "host", "ROLE", "viewer", HostAclService.Preset.OPERATE, 1L, updated.aclVersion(), Set.of(), true));
    }
    @Test
    void actualSingleChangesAdvanceVersionAndAuditFailureRollsBackBoth() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:acl_version_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE host_identity (agent_id VARCHAR(64) PRIMARY KEY, acl_version BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE host_acl_state (id INT PRIMARY KEY, completed INT)");
        jdbc.execute("CREATE TABLE host_acl_grant (agent_id VARCHAR(64), generation BIGINT, subject_type VARCHAR(8), subject_id VARCHAR(64), permission VARCHAR(32), PRIMARY KEY (agent_id, generation, subject_type, subject_id, permission))");
        jdbc.update("INSERT INTO host_identity VALUES ('host', 0)");
        jdbc.update("INSERT INTO host_acl_state VALUES (1, 1)");
        var identities = mock(HostIdentityService.class);
        var agents = mock(AgentRepository.class);
        var users = mock(UserRepository.class);
        var audit = mock(AuditLogService.class);
        var admin = new User(); admin.setId(1L); admin.setRole("admin"); admin.setStatus(1); admin.setUsername("admin");
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(agents.existsById("host")).thenReturn(true);
        when(identities.lock("host")).thenAnswer(inv -> {
            jdbc.queryForObject("SELECT acl_version FROM host_identity WHERE agent_id = 'host' FOR UPDATE", Long.class);
            return new HostIdentityService.Identity("host", 1, true);
        });
        var factory = new ProxyFactory(new HostAclService(new HostAclRepository(jdbc), identities, agents, users, audit));
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        var service = (HostAclService) factory.getProxy();

        service.change(1L, "host", "ROLE", "operator", HostAclService.Permission.VIEW, true, 1L);
        service.change(1L, "host", "ROLE", "operator", HostAclService.Permission.VIEW, true, 1L);
        assertEquals(1L, jdbc.queryForObject("SELECT acl_version FROM host_identity", Long.class));
        doThrow(new DataAccessResourceFailureException("audit failed"))
                .when(audit).logSync(any(), any(), any(), any(), any(), any(), isNull(), any());
        assertThrows(DataAccessResourceFailureException.class, () -> service.change(1L, "host", "ROLE", "operator", HostAclService.Permission.VIEW, false, 1L));
        assertEquals(1L, jdbc.queryForObject("SELECT acl_version FROM host_identity", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM host_acl_grant", Integer.class));
        reset(audit);
        service.change(1L, "host", "ROLE", "operator", HostAclService.Permission.VIEW, false, 1L);
        service.change(1L, "host", "ROLE", "operator", HostAclService.Permission.VIEW, false, 1L);
        assertEquals(2L, jdbc.queryForObject("SELECT acl_version FROM host_identity", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM host_acl_grant", Integer.class));
    }
}
