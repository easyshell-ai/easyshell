package com.easyshell.server.workflow;

import com.easyshell.server.ai.service.AiSchedulerService;
import com.easyshell.server.config.DataInitializer;
import com.easyshell.server.config.HostAclMigrationRunner;
import com.easyshell.server.config.HostAclStartupBarrier;
import com.easyshell.server.config.WorkflowSchemaFilterProvider;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.BaseEntity;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.HostAclRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostIdentityService;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConnectionDetails;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.easyshell.server.service.HostAclService.Permission.EXECUTE;
import static com.easyshell.server.service.HostAclService.Permission.TERMINAL;
import static com.easyshell.server.service.HostAclService.Permission.VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Isolated H2 evidence for the Java ACL backfill after real Flyway migrations.
 * The pre-Spring agent/sys_user tables and records are explicitly simulated 1.1.2 data,
 * not a copy of any deployed database. The manually invoked real runner uses mocked
 * initializer and scheduler; this is not a full production startup test.
 */
class Release112AclBackfillH2Test {
    private static final List<String> AGENT_IDS = List.of("agent-a", "agent-b", "agent-c");
    private static final List<String> MIGRATION_VERSIONS = List.of(
            "1.2.0", "1.2.1", "1.2.2", "1.2.10", "1.2.11", "1.2.12",
            "1.2.13", "1.2.14", "1.2.15", "1.2.17", "1.2.19", "1.2.20");
    private static final long VIEWER = 101L;
    private static final long OPERATOR = 102L;
    private static final long ADMIN = 103L;
    private static final long DISABLED_OPERATOR = 104L;
    private static final long LATER_USER = 105L;

    @Test
    void legacySnapshotIsPerAgentAndRoleAndDoesNotGrantLaterUsers() throws Exception {
        withFixture(new BackfillFailureProbe(false), context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            HostAclService acl = context.getBean(HostAclService.class);
            HostIdentityService identities = context.getBean(HostIdentityService.class);
            AgentRepository agents = context.getBean(AgentRepository.class);
            UserRepository users = context.getBean(UserRepository.class);

            assertThat(agents.findAll()).extracting(Agent::getId).containsExactlyInAnyOrderElementsOf(AGENT_IDS);
            assertThat(users.findAll()).extracting(User::getId)
                    .containsExactlyInAnyOrder(VIEWER, OPERATOR, ADMIN, DISABLED_OPERATOR);
            Agent first = agents.findById("agent-a").orElseThrow();
            Agent sameIpOffline = agents.findById("agent-b").orElseThrow();
            assertThat(first.getIp()).isEqualTo("192.0.2.10").isEqualTo(sameIpOffline.getIp());
            assertThat(sameIpOffline.getStatus()).isZero();
            assertThatThrownBy(() -> identities.lock("outside-transaction"))
                    .isInstanceOf(IllegalTransactionStateException.class);

            Snapshot before = snapshot(jdbc);
            assertThat(before).isEqualTo(new Snapshot(List.of(), List.of(), 0));
            HostAclStartupBarrier barrier = new HostAclStartupBarrier();
            DataInitializer initializer = mock(DataInitializer.class);
            AiSchedulerService scheduler = mock(AiSchedulerService.class);
            HostAclMigrationRunner runner = new HostAclMigrationRunner(acl, initializer, barrier, scheduler);
            assertThat(barrier.isOpen()).isFalse();

            runner.run(null);
            assertThat(barrier.isOpen()).isTrue();
            assertMigratedSnapshot(jdbc);
            assertLegacyAgents(jdbc);
            assertLegacyUsers(jdbc);
            verify(initializer).initialize();
            verify(scheduler).init();

            for (String id : AGENT_IDS) {
                assertThat(acl.allowed(VIEWER, id, VIEW)).as("viewer VIEW on %s", id).isTrue();
                assertThat(acl.allowed(VIEWER, id, EXECUTE)).as("viewer EXECUTE on %s", id).isTrue();
                assertThat(acl.allowed(VIEWER, id, TERMINAL)).as("viewer TERMINAL on %s", id).isFalse();
                assertThat(acl.allowed(OPERATOR, id, TERMINAL)).as("operator TERMINAL on %s", id).isTrue();
                assertThat(acl.allowed(ADMIN, id, TERMINAL)).as("admin TERMINAL on %s", id).isTrue();
                for (HostAclService.Permission permission : HostAclService.Permission.values()) {
                    assertThat(acl.allowed(DISABLED_OPERATOR, id, permission))
                            .as("disabled user %s on %s", permission, id).isFalse();
                }
            }

            Snapshot committed = snapshot(jdbc);
            jdbc.update("INSERT INTO sys_user (id, username, password, role, status) VALUES (?, ?, ?, ?, ?)",
                    LATER_USER, "later-user", "synthetic-only", "operator", 1);
            runner.run(null);
            assertThat(barrier.isOpen()).isTrue();
            assertThat(snapshot(jdbc)).isEqualTo(committed);
            assertLegacyUsers(jdbc);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM host_acl_grant "
                    + "WHERE subject_type = 'USER' AND subject_id = ?", Integer.class, Long.toString(LATER_USER)))
                    .isZero();
            for (String id : AGENT_IDS) {
                assertThat(acl.allowed(LATER_USER, id, VIEW)).isFalse();
                assertThat(acl.allowed(LATER_USER, id, EXECUTE)).isFalse();
                assertThat(acl.allowed(LATER_USER, id, TERMINAL)).isFalse();
            }
            verify(initializer, times(2)).initialize();
            verify(scheduler, times(2)).init();
        });
    }

    @Test
    void failureAfterRealGrantRollsBackNewIdentitiesGrantsVersionsAndState() throws Exception {
        BackfillFailureProbe probe = new BackfillFailureProbe(true);
        withFixture(probe, context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Snapshot before = snapshot(jdbc);
            assertThat(before).isEqualTo(new Snapshot(List.of(), List.of(), 0));
            HostAclStartupBarrier barrier = new HostAclStartupBarrier();
            DataInitializer initializer = mock(DataInitializer.class);
            AiSchedulerService scheduler = mock(AiSchedulerService.class);
            HostAclMigrationRunner runner = new HostAclMigrationRunner(
                    context.getBean(HostAclService.class), initializer, barrier, scheduler);

            assertThatThrownBy(() -> runner.run(null)).isSameAs(probe.failure);
            assertFailureObservedInsideTransaction(probe, new IdentityRow("agent-a", 1L, true, 11L), 33);
            assertThat(snapshot(jdbc)).isEqualTo(before);
            assertLegacyAgents(jdbc);
            assertLegacyUsers(jdbc);
            assertThat(barrier.isOpen()).isFalse();
            verify(initializer, never()).initialize();
            verify(scheduler, never()).init();
        });
    }

    @Test
    void failureAlsoRestoresExistingTombstoneGrantAndNonzeroVersion() throws Exception {
        BackfillFailureProbe probe = new BackfillFailureProbe(true);
        withFixture(probe, context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            // Additional adversarial post-Flyway fixture; not a claim about a pure 1.1.2 database.
            jdbc.update("INSERT INTO host_identity (agent_id, generation, active, acl_version) VALUES (?, ?, ?, ?)",
                    "agent-a", 7L, 0, 9L);
            jdbc.update("INSERT INTO host_acl_grant (agent_id, generation, subject_type, subject_id, permission) "
                    + "VALUES (?, ?, ?, ?, ?)", "agent-a", 7L, "USER", "999", "VIEW");
            Snapshot before = snapshot(jdbc);
            assertThat(before.identities()).containsExactly(new IdentityRow("agent-a", 7L, false, 9L));
            assertThat(before.grants()).containsExactly(new GrantRow("agent-a", 7L, "USER", "999", "VIEW"));
            assertThat(before.completed()).isZero();
            HostAclStartupBarrier barrier = new HostAclStartupBarrier();
            DataInitializer initializer = mock(DataInitializer.class);
            AiSchedulerService scheduler = mock(AiSchedulerService.class);
            HostAclMigrationRunner runner = new HostAclMigrationRunner(
                    context.getBean(HostAclService.class), initializer, barrier, scheduler);

            assertThatThrownBy(() -> runner.run(null)).isSameAs(probe.failure);
            assertFailureObservedInsideTransaction(probe, new IdentityRow("agent-a", 8L, true, 20L), 34);
            assertThat(snapshot(jdbc)).isEqualTo(before);
            assertLegacyAgents(jdbc);
            assertLegacyUsers(jdbc);
            assertThat(barrier.isOpen()).isFalse();
            verify(initializer, never()).initialize();
            verify(scheduler, never()).init();
        });
    }

    @Test
    void initializerFailureAfterAclCommitKeepsBackfillAndClosesBarrier() throws Exception {
        assertPostCommitFailure(false);
    }

    @Test
    void schedulerFailureAfterAclCommitKeepsBackfillAndClosesBarrier() throws Exception {
        assertPostCommitFailure(true);
    }

    private static void assertPostCommitFailure(boolean failScheduler) throws Exception {
        withFixture(new BackfillFailureProbe(false), context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            HostAclStartupBarrier barrier = new HostAclStartupBarrier();
            DataInitializer initializer = mock(DataInitializer.class);
            AiSchedulerService scheduler = mock(AiSchedulerService.class);
            IllegalStateException failure = new IllegalStateException(
                    failScheduler ? "synthetic scheduler failure" : "synthetic initializer failure");
            if (failScheduler) {
                doAnswer(invocation -> {
                    assertCommittedBeforeNextStage(jdbc, barrier);
                    throw failure;
                }).when(scheduler).init();
            } else {
                doAnswer(invocation -> {
                    assertCommittedBeforeNextStage(jdbc, barrier);
                    throw failure;
                }).when(initializer).initialize();
            }
            HostAclMigrationRunner runner = new HostAclMigrationRunner(
                    context.getBean(HostAclService.class), initializer, barrier, scheduler);
            assertThat(snapshot(jdbc)).isEqualTo(new Snapshot(List.of(), List.of(), 0));

            assertThatThrownBy(() -> runner.run(null)).isSameAs(failure);
            assertMigratedSnapshot(jdbc);
            assertLegacyAgents(jdbc);
            assertLegacyUsers(jdbc);
            assertThat(barrier.isOpen()).isFalse();
            verify(initializer).initialize();
            if (failScheduler) verify(scheduler).init();
            else verify(scheduler, never()).init();

            // A fresh runner cannot reopen this already-aborted barrier or repeat the backfill.
            Snapshot committed = snapshot(jdbc);
            new HostAclMigrationRunner(context.getBean(HostAclService.class), mock(DataInitializer.class),
                    barrier, mock(AiSchedulerService.class)).run(null);
            assertThat(barrier.isOpen()).isFalse();
            assertThat(snapshot(jdbc)).isEqualTo(committed);
        });
    }

    private static void assertCommittedBeforeNextStage(JdbcTemplate jdbc, HostAclStartupBarrier barrier) {
        // This callback is after migrateExisting returned: JdbcTemplate observes the committed state
        // without an enclosing test transaction, not uncommitted rows on the migration connection.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(barrier.isOpen()).isFalse();
        assertMigratedSnapshot(jdbc);
    }

    private static void assertFailureObservedInsideTransaction(BackfillFailureProbe probe, IdentityRow identity,
                                                               int grantCount) {
        assertThat(probe.calls).isEqualTo(1);
        assertThat(probe.transactionActive).isTrue();
        assertThat(probe.identityCount).isEqualTo(3);
        assertThat(probe.grantCount).isEqualTo(grantCount);
        assertThat(probe.state).isEqualTo(1);
        assertThat(probe.identity).isEqualTo(identity);
        assertThat(probe.jpaSession).isNotNull();
        assertThat(probe.jdbcSession).isEqualTo(probe.jpaSession);
    }

    private static void assertMigratedSnapshot(JdbcTemplate jdbc) {
        Snapshot actual = snapshot(jdbc);
        assertThat(actual.completed()).isEqualTo(1);
        assertThat(actual.identities()).containsExactly(
                new IdentityRow("agent-a", 1L, true, 11L),
                new IdentityRow("agent-b", 1L, true, 11L),
                new IdentityRow("agent-c", 1L, true, 11L));
        List<GrantRow> expected = expectedLegacyGrants();
        assertThat(actual.grants()).hasSize(33).containsExactlyInAnyOrderElementsOf(expected);
        for (IdentityRow identity : actual.identities()) {
            long insertedGrants = actual.grants().stream()
                    .filter(grant -> grant.agentId().equals(identity.agentId())).count();
            assertThat(identity.aclVersion()).as("actual grant count for %s", identity.agentId())
                    .isEqualTo(insertedGrants);
        }
    }

    private static List<GrantRow> expectedLegacyGrants() {
        List<GrantRow> expected = new ArrayList<>();
        for (String agentId : AGENT_IDS) {
            for (long user : List.of(VIEWER, OPERATOR, ADMIN, DISABLED_OPERATOR)) {
                expected.add(new GrantRow(agentId, 1L, "USER", Long.toString(user), "VIEW"));
                expected.add(new GrantRow(agentId, 1L, "USER", Long.toString(user), "EXECUTE"));
                if (user != VIEWER) {
                    expected.add(new GrantRow(agentId, 1L, "USER", Long.toString(user), "TERMINAL"));
                }
            }
        }
        return expected;
    }

    private static Snapshot snapshot(JdbcTemplate jdbc) {
        List<IdentityRow> identities = jdbc.query(
                "SELECT agent_id, generation, active, acl_version FROM host_identity ORDER BY agent_id",
                (rs, row) -> new IdentityRow(rs.getString(1), rs.getLong(2), rs.getBoolean(3), rs.getLong(4)));
        List<GrantRow> grants = jdbc.query(
                "SELECT agent_id, generation, subject_type, subject_id, permission FROM host_acl_grant "
                        + "ORDER BY agent_id, generation, subject_type, subject_id, permission",
                (rs, row) -> new GrantRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)));
        Integer completed = jdbc.queryForObject("SELECT completed FROM host_acl_state WHERE id = 1", Integer.class);
        assertThat(completed).isNotNull();
        return new Snapshot(identities, grants, completed);
    }

    private static void assertLegacyAgents(JdbcTemplate jdbc) {
        List<LegacyAgent> agents = jdbc.query("SELECT id, hostname, ip, status FROM agent ORDER BY id",
                (rs, row) -> new LegacyAgent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
        assertThat(agents).containsExactly(
                new LegacyAgent("agent-a", "alpha", "192.0.2.10", 1),
                new LegacyAgent("agent-b", "beta", "192.0.2.10", 0),
                new LegacyAgent("agent-c", "gamma", "198.51.100.20", 0));
    }

    private static void assertLegacyUsers(JdbcTemplate jdbc) {
        List<LegacyUser> users = jdbc.query(
                "SELECT id, username, password, role, status FROM sys_user WHERE id BETWEEN 101 AND 104 ORDER BY id",
                (rs, row) -> new LegacyUser(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5)));
        assertThat(users).containsExactly(
                new LegacyUser(VIEWER, "viewer-user", "synthetic-only", "viewer", 1),
                new LegacyUser(OPERATOR, "operator-user", "synthetic-only", "operator", 1),
                new LegacyUser(ADMIN, "admin-user", "synthetic-only", "admin", 1),
                new LegacyUser(DISABLED_OPERATOR, "disabled-user", "synthetic-only", "operator", 0));
    }

    private static void withFixture(BackfillFailureProbe probe, Consumer<AssertableApplicationContext> assertions)
            throws SQLException {
        String url = "jdbc:h2:mem:release112_acl_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
        // The keeper holds this unique memory database only until its independently created context closes.
        try (Connection keeper = DriverManager.getConnection(url, "sa", "")) {
            createSimulated112LegacyTables(keeper);
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
            dataSource.setDriverClassName("org.h2.Driver");

            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                            JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                            TransactionAutoConfiguration.class, FlywayAutoConfiguration.class))
                    .withUserConfiguration(AclTestConfiguration.class)
                    .withBean(DataSource.class, () -> dataSource)
                    .withBean(BackfillFailureProbe.class, () -> probe)
                    .withInitializer(context -> {
                        // Remove only this context's inherited property sources. No global properties change.
                        context.getEnvironment().getPropertySources()
                                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                        context.getEnvironment().getPropertySources()
                                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    })
                    .withPropertyValues(
                            "spring.jpa.hibernate.ddl-auto=update",
                            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                            "spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider="
                                    + WorkflowSchemaFilterProvider.class.getName(),
                            "spring.jpa.open-in-view=false",
                            "spring.jpa.show-sql=false",
                            "spring.flyway.enabled=true",
                            "spring.flyway.baseline-on-migrate=true",
                            "spring.flyway.validate-on-migrate=true",
                            "spring.flyway.locations=classpath:db/migration")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(DataSource.class)).isSameAs(dataSource);
                        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                                .isEqualTo("update");
                        EntityManagerFactory emf = context.getBean(EntityManagerFactory.class);
                        assertThat(emf.getProperties().get("hibernate.hbm2ddl.auto")).isEqualTo("update");
                        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
                        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
                        assertThat(((JpaTransactionManager) transactionManager).getDataSource()).isSameAs(dataSource);
                        assertThat(AopUtils.isAopProxy(context.getBean(HostAclService.class))).isTrue();
                        assertThat(AopUtils.isAopProxy(context.getBean(HostIdentityService.class))).isTrue();

                        Flyway flyway = context.getBean(Flyway.class);
                        assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);
                        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isTrue();
                        assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
                        assertThat(flyway.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("1");
                        assertThatCode(flyway::validate).doesNotThrowAnyException();
                        assertThat(Arrays.stream(flyway.info().applied())
                                .filter(info -> info.getType() == CoreMigrationType.BASELINE)
                                .map(info -> info.getVersion().getVersion()).toList()).containsExactly("1");
                        assertThat(Arrays.stream(flyway.info().applied())
                                .filter(info -> info.getType() == CoreMigrationType.SQL)
                                .map(info -> info.getVersion().getVersion()).toList())
                                .containsExactlyElementsOf(MIGRATION_VERSIONS);
                        assertThat(flyway.info().pending()).isEmpty();
                        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                        assertLegacyAgents(jdbc);
                        assertLegacyUsers(jdbc);
                        assertThat(snapshot(jdbc)).isEqualTo(new Snapshot(List.of(), List.of(), 0));
                        assertions.accept(context);
                    });
        }
    }

    private static void createSimulated112LegacyTables(Connection connection) throws SQLException {
        // Complete enough for the two mapped entities, but not a real 1.1.2 database dump.
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent (id VARCHAR(64) PRIMARY KEY, hostname VARCHAR(128) NOT NULL, "
                    + "ip VARCHAR(64) NOT NULL, os VARCHAR(64), arch VARCHAR(32), kernel VARCHAR(128), "
                    + "cpu_model VARCHAR(128), cpu_cores INT, mem_total BIGINT, agent_version VARCHAR(32), "
                    + "status INT NOT NULL, last_heartbeat TIMESTAMP, cpu_usage DOUBLE, mem_usage DOUBLE, "
                    + "disk_usage DOUBLE, registered_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)");
            statement.execute("CREATE TABLE sys_user (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                    + "username VARCHAR(64) NOT NULL UNIQUE, password VARCHAR(128) NOT NULL, email VARCHAR(128), "
                    + "role VARCHAR(32) NOT NULL, status INT NOT NULL, last_login_at TIMESTAMP, "
                    + "created_at TIMESTAMP, updated_at TIMESTAMP)");
            statement.executeUpdate("INSERT INTO agent (id, hostname, ip, status) VALUES "
                    + "('agent-a', 'alpha', '192.0.2.10', 1), "
                    + "('agent-b', 'beta', '192.0.2.10', 0), "
                    + "('agent-c', 'gamma', '198.51.100.20', 0)");
            statement.executeUpdate("INSERT INTO sys_user (id, username, password, role, status) VALUES "
                    + "(101, 'viewer-user', 'synthetic-only', 'viewer', 1), "
                    + "(102, 'operator-user', 'synthetic-only', 'operator', 1), "
                    + "(103, 'admin-user', 'synthetic-only', 'admin', 1), "
                    + "(104, 'disabled-user', 'synthetic-only', 'operator', 0)");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = AgentRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {AgentRepository.class, UserRepository.class}))
    @Import({HostIdentityService.class, HostAclService.class})
    static class AclTestConfiguration {
        @Bean
        PersistenceManagedTypes persistenceManagedTypes() {
            return PersistenceManagedTypes.of(Agent.class.getName(), User.class.getName(), BaseEntity.class.getName());
        }

        @Bean
        FlywayConnectionDetails flywayConnectionDetails() {
            // Force Boot Flyway to use the same explicitly supplied in-memory DataSource,
            // even if the surrounding process has a SPRING_FLYWAY_URL.
            return new FlywayConnectionDetails() {
                @Override public String getUsername() { return null; }
                @Override public String getPassword() { return null; }
                @Override public String getJdbcUrl() { return null; }
            };
        }

        @Bean
        AuditLogService auditLogService() {
            // Required constructor dependency; the legacy backfill does not call it.
            return mock(AuditLogService.class);
        }

        @Bean
        HostAclRepository hostAclRepository(JdbcTemplate jdbc, EntityManagerFactory emf, BackfillFailureProbe probe) {
            HostAclRepository real = new HostAclRepository(jdbc);
            if (!probe.enabled) return real;
            HostAclRepository failing = spy(real);
            doAnswer(invocation -> {
                // All grants and the real completion update execute before the injected failure.
                invocation.callRealMethod();
                probe.calls++;
                probe.observeInsideTransaction(jdbc, emf);
                throw probe.failure;
            }).when(failing).complete();
            return failing;
        }
    }

    private static final class BackfillFailureProbe {
        private final boolean enabled;
        private final IllegalStateException failure = new IllegalStateException("synthetic failure after ACL completion");
        private int calls;
        private boolean transactionActive;
        private Integer identityCount;
        private Integer grantCount;
        private Integer state;
        private IdentityRow identity;
        private Integer jpaSession;
        private Integer jdbcSession;

        private BackfillFailureProbe(boolean enabled) { this.enabled = enabled; }

        private void observeInsideTransaction(JdbcTemplate jdbc, EntityManagerFactory emf) {
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            identityCount = jdbc.queryForObject("SELECT COUNT(*) FROM host_identity", Integer.class);
            grantCount = jdbc.queryForObject("SELECT COUNT(*) FROM host_acl_grant", Integer.class);
            state = jdbc.queryForObject("SELECT completed FROM host_acl_state WHERE id = 1", Integer.class);
            identity = jdbc.queryForObject("SELECT agent_id, generation, active, acl_version "
                            + "FROM host_identity WHERE agent_id = ?",
                    (rs, row) -> new IdentityRow(rs.getString(1), rs.getLong(2), rs.getBoolean(3), rs.getLong(4)),
                    "agent-a");
            jpaSession = SharedEntityManagerCreator.createSharedEntityManager(emf).unwrap(Session.class)
                    .doReturningWork(connection -> {
                        try (Statement statement = connection.createStatement();
                             var result = statement.executeQuery("SELECT SESSION_ID()")) {
                            if (!result.next()) throw new SQLException("Missing H2 session id");
                            return result.getInt(1);
                        }
                    });
            jdbcSession = jdbc.queryForObject("SELECT SESSION_ID()", Integer.class);
        }
    }

    private record IdentityRow(String agentId, long generation, boolean active, long aclVersion) { }
    private record GrantRow(String agentId, long generation, String subjectType, String subjectId, String permission) { }
    private record LegacyAgent(String id, String hostname, String ip, int status) { }
    private record LegacyUser(long id, String username, String password, String role, int status) { }
    private record Snapshot(List<IdentityRow> identities, List<GrantRow> grants, int completed) { }
}
