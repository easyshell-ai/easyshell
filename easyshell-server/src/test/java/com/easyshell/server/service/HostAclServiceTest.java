package com.easyshell.server.service;

import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.HostAclRepository;
import com.easyshell.server.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostAclServiceTest {
    private final HostAclRepository acl = mock(HostAclRepository.class);
    private final HostIdentityService identities = mock(HostIdentityService.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final HostAclService service = new HostAclService(acl, identities, agents, users, audit);

    @Test
    void presetPreservesUnknownPermissionsWithoutIncludingTheirContentsInAudit() {
        User admin = user(1L, "admin", 1);
        admin.setUsername("alice");
        when(acl.completed()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(agents.existsById("host")).thenReturn(true);
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host", 4, true));
        when(acl.version("host")).thenReturn(7L);
        String sensitive = "secret-token\r\nforged-audit\tvalue";
        String otherUnknown = "VIEW\0";
        when(acl.subjectPermissions("host", 4, "ROLE", "operator"))
                .thenReturn(List.of("VIEW", "EXECUTE", sensitive, otherUnknown));

        service.applyPreset(1L, "host", "ROLE", "operator", HostAclService.Preset.READ_ONLY,
                4L, 7L, Set.of("VIEW", "EXECUTE", sensitive, otherUnknown), false);

        verify(acl).revoke("host", 4, "ROLE", "operator", "EXECUTE");
        verify(acl, times(1)).revoke(anyString(), anyLong(), anyString(), anyString(), anyString());
        verify(acl, never()).grant(anyString(), anyLong(), anyString(), anyString(), anyString());
        verify(acl).advanceVersion("host");
        verify(audit).logSync(1L, "alice", "HOST_ACL_PRESET", "agent", "host",
                "generation=4, version=7, subjectType=ROLE, subjectId=operator, preset=READ_ONLY"
                        + ", before=[EXECUTE, VIEW], unknownCount=2, riskConfirmed=false", null, "SUCCESS");
    }

    @Test
    void viewerTerminalGrantsAreRejectedButExistingGrantsCanBeRevoked() {
        when(acl.completed()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, "admin", 1)));
        when(users.existsById(2L)).thenReturn(true);
        when(users.findById(2L)).thenReturn(Optional.of(user(2L, "viewer", 1)));
        when(agents.existsById("host")).thenReturn(true);
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host", 1, true));
        for (String[] subject : List.of(new String[]{"USER", "2"}, new String[]{"ROLE", "viewer"})) {
            var error = assertThrows(BusinessException.class, () -> service.change(
                    1L, "host", subject[0], subject[1], HostAclService.Permission.TERMINAL, true, 1L));
            assertEquals(400, error.getCode());
            assertTrue(error.getMessage().contains("Terminal requires"));
            service.change(1L, "host", subject[0], subject[1], HostAclService.Permission.TERMINAL, false, 1L);
            verify(acl).revoke("host", 1, subject[0], subject[1], "TERMINAL");
        }
        verify(acl, never()).grant(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void migrationSnapshotsLegacyPermissionsAndCompletesOnce() {
        Agent agent = new Agent(); agent.setId("host");
        User viewer = user(1L, "viewer", 1);
        User operator = user(2L, "operator", 1);
        User admin = user(3L, "admin", 1);
        when(agents.findAll()).thenReturn(List.of(agent));
        when(users.findAll()).thenReturn(List.of(viewer, operator, admin));
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host", 3, true));
        when(agents.existsById("host")).thenReturn(true);
        service.migrateExisting();
        verify(identities).initializeExisting(List.of("host"));
        verify(acl).grant("host", 3, "USER", "1", "EXECUTE");
        verify(acl, never()).grant("host", 3, "USER", "1", "TERMINAL");
        verify(acl).grant("host", 3, "USER", "2", "TERMINAL");
        verify(acl).grant("host", 3, "USER", "3", "TERMINAL");
        verify(acl).complete();
    }

    @Test
    void completedMigrationDoesNotSnapshotNewUsers() {
        when(acl.lockCompleted()).thenReturn(true);
        service.migrateExisting();
        verifyNoInteractions(agents, users, identities);
        verify(acl, never()).complete();
    }

    @Test
    void authorizationUsesCurrentUserAndGeneration() {
        when(acl.authorizedGeneration(1L, "host", "EXECUTE")).thenReturn(OptionalLong.of(2));
        assertTrue(service.allowed(1L, "host", HostAclService.Permission.EXECUTE));
        assertEquals(OptionalLong.of(2), service.authorizedGeneration(1L, "host", HostAclService.Permission.EXECUTE));
        assertTrue(service.allowedForGeneration(1L, "host", 2, HostAclService.Permission.EXECUTE));
        assertFalse(service.allowedForGeneration(1L, "host", 1, HostAclService.Permission.EXECUTE));
        verifyNoInteractions(identities, agents, users);
    }

    @Test
    void invalidInputsAndDatabaseFailureDeny() {
        assertFalse(service.authorizedGeneration(null, "host", HostAclService.Permission.VIEW).isPresent());
        assertFalse(service.allowedForGeneration(1L, " ", 1, HostAclService.Permission.VIEW));
        assertFalse(service.allowed(1L, "host", null));
        verifyNoInteractions(acl);
        when(acl.authorizedGeneration(1L, "host", "VIEW"))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertFalse(service.allowedForGeneration(1L, "host", 1, HostAclService.Permission.VIEW));
        assertFalse(service.authorizedGeneration(1L, "host", HostAclService.Permission.VIEW).isPresent());
    }

    @Test
    void adminCanManageWithoutHostGrantAndBothActionsAreAudited() {
        User admin = user(1L, "admin", 1);
        admin.setUsername("alice");
        when(acl.completed()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(agents.existsById("host")).thenReturn(true);
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host", 4, true));
        service.change(1L, "host", "ROLE", "operator", HostAclService.Permission.TERMINAL, true, 4L);
        service.change(1L, "host", "ROLE", "operator", HostAclService.Permission.TERMINAL, false, 4L);
        verify(acl).grant("host", 4, "ROLE", "operator", "TERMINAL");
        verify(acl).revoke("host", 4, "ROLE", "operator", "TERMINAL");
        String detail = "generation=4, subjectType=ROLE, subjectId=operator, permission=TERMINAL";
        verify(audit).logSync(1L, "alice", "HOST_ACL_GRANT", "agent", "host", detail, null, "SUCCESS");
        verify(audit).logSync(1L, "alice", "HOST_ACL_REVOKE", "agent", "host", detail, null, "SUCCESS");
        verify(acl, never()).authorizedGeneration(anyLong(), anyString(), anyString());
    }

    @Test
    void deletedUserGrantCanBeRevokedButNotGranted() {
        User admin = user(1L, "admin", 1);
        admin.setUsername("alice");
        when(acl.completed()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(agents.existsById("host")).thenReturn(true);
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host", 4, true));

        service.change(1L, "host", "USER", "42", HostAclService.Permission.VIEW, false, 4L);
        verify(users, never()).existsById(42L);
        verify(identities).lock("host");
        verify(acl).revoke("host", 4, "USER", "42", "VIEW");
        verify(audit).logSync(1L, "alice", "HOST_ACL_REVOKE", "agent", "host",
                "generation=4, subjectType=USER, subjectId=42, permission=VIEW", null, "SUCCESS");

        assertEquals(400, assertThrows(BusinessException.class, () -> service.change(
                1L, "host", "USER", "42", HostAclService.Permission.VIEW, true, 4L)).getCode());
        verify(users).existsById(42L);
        verify(acl, never()).grant(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void deletedUserRevokeStillValidatesIdAndGeneration() {
        when(acl.completed()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, "admin", 1)));
        when(agents.existsById("host")).thenReturn(true);
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host", 4, true));

        for (String subject : List.of("0", "-1", "01", "+1", " 1", "abc", "9223372036854775808")) {
            assertEquals(400, assertThrows(BusinessException.class, () -> service.change(
                    1L, "host", "USER", subject, HostAclService.Permission.VIEW, false, 4L)).getCode());
        }
        assertEquals(409, assertThrows(BusinessException.class, () -> service.change(
                1L, "host", "USER", "42", HostAclService.Permission.VIEW, false, 3L)).getCode());
        verify(identities).lock("host");
        verify(users, never()).existsById(anyLong());
        verify(acl, never()).revoke(anyString(), anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void auditFailureRollsBackGrantAndRevoke() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:acl_tx_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE grants (id INT PRIMARY KEY)");
        when(acl.completed()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, "admin", 1)));
        when(agents.existsById("host")).thenReturn(true);
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host", 1, true));
        doAnswer(inv -> { jdbc.update("INSERT INTO grants VALUES (1)"); return null; })
                .when(acl).grant("host", 1, "ROLE", "operator", "VIEW");
        doAnswer(inv -> { jdbc.update("DELETE FROM grants WHERE id = 1"); return null; })
                .when(acl).revoke("host", 1, "USER", "42", "VIEW");
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(audit).logSync(any(), any(), any(), any(), any(), any(), isNull(), any());
        var proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        HostAclService transactional = (HostAclService) proxyFactory.getProxy();
        assertThrows(DataAccessResourceFailureException.class,
                () -> transactional.change(1L, "host", "ROLE", "operator", HostAclService.Permission.VIEW, true, 1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM grants", Integer.class));
        jdbc.update("INSERT INTO grants VALUES (1)");
        assertThrows(DataAccessResourceFailureException.class,
                () -> transactional.change(1L, "host", "USER", "42", HostAclService.Permission.VIEW, false, 1L));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM grants", Integer.class));
        verify(users, never()).existsById(42L);
    }

    @Test
    void generationPreconditionRejectsStaleAndMissingWithoutMutationOrAudit() {
        when(acl.completed()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, "admin", 1)));
        when(agents.existsById("host")).thenReturn(true);
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host", 2, true));
        for (boolean grant : new boolean[]{true, false}) {
            var stale = assertThrows(BusinessException.class, () -> service.change(
                    1L, "host", "ROLE", "operator", HostAclService.Permission.VIEW, grant, 1L));
            assertEquals(409, stale.getCode());
        }
        assertEquals(400, assertThrows(BusinessException.class, () -> service.change(
                1L, "host", "ROLE", "operator", HostAclService.Permission.VIEW, true, null)).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.change(
                1L, "host", "ROLE", "operator", HostAclService.Permission.VIEW, true)).getCode());
        verify(acl, never()).grant(anyString(), anyLong(), anyString(), anyString(), anyString());
        verify(acl, never()).revoke(anyString(), anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(audit);
    }

    private static User user(long id, String role, int status) {
        User user = new User(); user.setId(id); user.setRole(role); user.setStatus(status); return user;
    }
}
