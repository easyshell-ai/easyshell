package com.easyshell.server.workflow;

import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.Claim;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.Member;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.State;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Opt-in proof on a disposable MySQL 8.0 instance in a loopback-only network namespace.
 * The guard must be the only existing table before this test starts. The four old tables
 * and every record are synthetic, deliberately incomplete 1.1.2 fixtures, not an old dump.
 * This does not start Spring, execute the Java ACL backfill, or test real Agents or old data.
 * It never cleans the database: even a failed run may leave partial DDL and must be discarded
 * by the owner of the dedicated temporary instance, not pointed at an existing database.
 */
class Release112MigrationMySqlTest {
    private static final String REQUIRED_URL = "jdbc:mysql://127.0.0.1:3306/easyshell_upgrade_proof"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DATABASE = "easyshell_upgrade_proof";
    private static final String USERNAME = "proof";
    private static final String GUARD = "easyshell_proof_guard";
    private static final String PROOF_LOCK = "easyshell_release112_migration_proof";
    private static final List<String> VERSIONS = List.of(
            "1.2.0", "1.2.1", "1.2.2", "1.2.10", "1.2.11", "1.2.12",
            "1.2.13", "1.2.14", "1.2.15", "1.2.17", "1.2.19", "1.2.20");
    private static final Set<String> FIXTURE_TABLES = Set.of(GUARD, "agent", "sys_user", "task", "audit_log");
    private static final Set<String> MIGRATION_TABLES = Set.of(
            "workflow", "workflow_version", "workflow_run", "workflow_node_run", "workflow_approval",
            "host_identity", "host_acl_state", "host_acl_grant", "workflow_dispatch_approval_binding",
            "task_target_snapshot", "task_target_snapshot_item", "workflow_run_provenance",
            "workflow_run_provenance_task", "ai_inspect_report_task_binding", "agent_enrollment_token",
            "workflow_run_v3_birth", "agent_bound_key", "agent_key_challenge", "user_refresh_generation",
            "workflow_recovery_run", "workflow_recovery_batch", "workflow_recovery_member");
    private static final Set<String> FINAL_TABLES = finalTables();
    private static final SyntheticSnapshot EXPECTED_LEGACY = new SyntheticSnapshot(
            List.of(new AgentRow("synthetic-a", "alpha", "192.0.2.10", 1),
                    new AgentRow("synthetic-b", "beta", "192.0.2.10", 0)),
            List.of(new UserRow(101L, "synthetic-viewer", "viewer", 1),
                    new UserRow(102L, "synthetic-operator", "operator", 1)),
            List.of(new TaskRow("synthetic-task", 101L, 2)),
            List.of(new AuditRow(1L, "SYNTHETIC_1_1_2")));

    @Test
    @EnabledIfEnvironmentVariable(named = "EASYSHELL_MYSQL_PROOF_URL", matches = "(?s).+")
    void synthetic112DatabaseMigratesOnGuardedMySql8WithoutChangingOldRows() throws Exception {
        ProofSettings settings = ProofSettings.fromEnvironment();
        GuardedProofDataSource dataSource = new GuardedProofDataSource(settings);

        // The first connection is checked for the guard-only schema before it is returned.
        // Keep it open for the cooperative advisory lock throughout the proof.
        try (Connection guardConnection = dataSource.getConnection()) {
            dataSource.beforeFixtureWrite(guardConnection, Set.of(GUARD));
            acquireProofLock(guardConnection);
            createSyntheticLegacyFixture(dataSource, guardConnection);
            SyntheticSnapshot before = readLegacySnapshot(guardConnection);
            assertThat(before).isEqualTo(EXPECTED_LEGACY);
            dataSource.beginMigrations(guardConnection, before);

            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .validateOnMigrate(true)
                    .cleanDisabled(true)
                    .load();
            assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);
            assertThat(flyway.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("1");
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(VERSIONS.size());

            try (Connection connection = dataSource.getConnection()) {
                dataSource.markComplete(connection);
            }
            assertFlywayState(flyway);
            assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);

            List<List<Object>> historyBeforeRepeat;
            List<IdentityRow> identitiesBeforeRepeat;
            List<AclStateRow> aclStateBeforeRepeat;
            Map<String, Long> countsBeforeRepeat;
            try (Connection connection = dataSource.getConnection()) {
                assertThat(readLegacySnapshot(connection)).isEqualTo(before);
                assertCompatibleCollations(connection);
                assertAclVersionColumn(connection);
                assertEnrollmentIndex(connection);
                assertEmptyIdentityAndAclSeed(connection);

                dataSource.beforeFinalWrite(connection);
                executeUpdate(connection, "INSERT INTO `host_identity` (`agent_id`) VALUES ('mysql-proof-default')", 1);
                dataSource.beforeFinalWrite(connection);
                executeUpdate(connection, "INSERT INTO `host_identity` (`agent_id`, `generation`, `active`) "
                        + "VALUES ('mysql-proof-explicit', 7, 1)", 1);
                identitiesBeforeRepeat = readIdentityRows(connection);
                assertThat(identitiesBeforeRepeat).containsExactly(
                        new IdentityRow("mysql-proof-default", 0L, 0, 0L),
                        new IdentityRow("mysql-proof-explicit", 7L, 1, 0L));
                aclStateBeforeRepeat = readAclState(connection);
                assertThat(aclStateBeforeRepeat).containsExactly(new AclStateRow(1, 0));
                historyBeforeRepeat = readHistory(connection);
                countsBeforeRepeat = readTableCounts(connection);
                for (String table : MIGRATION_TABLES) {
                    if (!table.equals("host_identity") && !table.equals("host_acl_state")) {
                        assertThat(countsBeforeRepeat.get(table)).as("initial rows in %s", table).isZero();
                    }
                }
                assertThat(readLegacySnapshot(connection)).isEqualTo(before);
            }

            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertFlywayState(flyway);
            try (Connection connection = dataSource.getConnection()) {
                assertThat(readLegacySnapshot(connection)).isEqualTo(before);
                assertThat(readIdentityRows(connection)).isEqualTo(identitiesBeforeRepeat);
                assertThat(readAclState(connection)).isEqualTo(aclStateBeforeRepeat);
                assertThat(readHistory(connection)).isEqualTo(historyBeforeRepeat);
                assertThat(readTableCounts(connection)).isEqualTo(countsBeforeRepeat);
                assertAclVersionColumn(connection);
                assertEnrollmentIndex(connection);
            }
            assertRecoveryRepeatableRead(dataSource);
            assertResumeNativeQuery(dataSource);
            try (Connection connection = dataSource.getConnection()) {
                assertThat(readLegacySnapshot(connection)).isEqualTo(before);
                assertThat(readHistory(connection)).isEqualTo(historyBeforeRepeat);
            }
        }
    }

    /** A read view predating the claim must not turn an active batch into an empty terminal batch. */
    private static void assertRecoveryRepeatableRead(GuardedProofDataSource source) throws SQLException {
        WorkflowRecoveryClaimRepository repository = new WorkflowRecoveryClaimRepository(true);
        String runId = "rr-proof-" + UUID.randomUUID();
        Member member = new Member("rr-node-" + UUID.randomUUID(), "rr-node", 1);
        try (Connection connectionA = source.getConnection(); Connection connectionB = source.getConnection()) {
            source.beforeFinalWrite(connectionA);
            source.beforeFinalWrite(connectionB);
            connectionB.setAutoCommit(false);
            try {
                repository.provision(connectionB, runId);
                connectionB.commit();

                connectionA.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connectionA.setAutoCommit(false);
                assertThat(connectionA.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                try (PreparedStatement statement = connectionA.prepareStatement(
                        "SELECT claim_state FROM workflow_recovery_member WHERE run_id = ?")) {
                    statement.setString(1, runId);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertThat(rows.next()).as("ordinary SELECT establishes the old read view").isFalse();
                    }
                }

                Claim claim = repository.claim(connectionB, runId, "mysql-rr-proof", Duration.ofHours(1),
                        List.of(member)).orElseThrow();
                connectionB.commit();
                // Prove that A still sees the old snapshot before invoking the locking repository read.
                try (PreparedStatement statement = connectionA.prepareStatement(
                        "SELECT claim_state FROM workflow_recovery_member WHERE run_id = ?")) {
                    statement.setString(1, runId);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertThat(rows.next()).as("claim is invisible to A's old read view").isFalse();
                    }
                }
                assertThat(repository.finishBatch(connectionA, claim))
                        .as("locking member read must observe CLAIMED despite the old read view").isFalse();
                connectionA.commit();

                Claim stale = new Claim(runId, claim.round(), claim.owner(), UUID.randomUUID().toString());
                assertThat(repository.enter(connectionA, stale, member)).isFalse();
                assertThat(repository.cancelUnentered(connectionA, stale, List.of(member))).isZero();
                connectionA.commit();

                assertThat(repository.enter(connectionB, claim, member)).isTrue();
                connectionB.commit();
                assertThat(repository.complete(connectionB, claim, member, State.SUCCEEDED)).isTrue();
                connectionB.commit();
                assertThat(repository.finishBatch(connectionB, claim)).isTrue();
                connectionB.commit();

                assertThat(repository.enter(connectionA, stale, member)).isFalse();
                assertThat(repository.complete(connectionA, stale, member, State.FAILED)).isFalse();
                assertThat(repository.cancelUnentered(connectionA, stale, List.of(member))).isZero();
                assertThat(repository.finishBatch(connectionA, stale)).isFalse();
                // Even the original token is fenced once its batch has closed.
                assertThat(repository.enter(connectionA, claim, member)).isFalse();
                assertThat(repository.finishBatch(connectionA, claim)).isFalse();
                connectionA.commit();
                try (PreparedStatement statement = connectionB.prepareStatement(
                        "SELECT claim_state FROM workflow_recovery_member WHERE run_id = ?")) {
                    statement.setString(1, runId);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("SUCCEEDED");
                        assertThat(rows.next()).isFalse();
                    }
                }
                connectionB.commit();
            } finally {
                if (!connectionA.getAutoCommit()) connectionA.rollback();
                if (!connectionB.getAutoCommit()) connectionB.rollback();
            }
        }
    }

    /** Execute the repository annotation itself so MySQL 1093 cannot hide behind an equivalent test SQL. */
    private static void assertResumeNativeQuery(GuardedProofDataSource source) throws Exception {
        Query query = WorkflowNodeRunRepository.class
                .getMethod("resumeWaitingAttempt", String.class, String.class, Long.class).getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).isNotBlank();
        String runId = "resume-proof-" + UUID.randomUUID();
        String blockedId = "blocked-" + UUID.randomUUID();
        String higherId = "higher-" + UUID.randomUUID();
        String clearId = "clear-" + UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            source.beforeFinalWrite(connection);
            // Suppress close: all named-parameter statements use this same guarded connection.
            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            assertThat(jdbc.update("INSERT INTO workflow_run "
                            + "(id, workflow_id, version_id, version_no, trigger_type, status, variables_json, "
                            + "variables_truncated, cancel_requested) "
                            + "VALUES (:runId, 1, 1, 1, 'manual', 'waiting', '{}', 0, 0)",
                    Map.of("runId", runId))).isEqualTo(1);
            long blockedApproval = insertResumeApproval(connection, runId, "blocked");
            long clearApproval = insertResumeApproval(connection, runId, "clear");
            insertResumeNode(jdbc, runId, blockedId, "blocked", 1, "waiting", blockedApproval);
            // Terminal, rather than waiting: only the higher-attempt branch can block the old attempt.
            insertResumeNode(jdbc, runId, higherId, "blocked", 2, "succeeded", blockedApproval);
            insertResumeNode(jdbc, runId, clearId, "clear", 1, "waiting", clearApproval);

            assertThat(jdbc.update(query.value(), Map.of("id", blockedId, "runId", runId,
                    "approvalId", blockedApproval))).as("higher attempt blocks the exact old attempt").isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM workflow_node_run WHERE id = :id",
                    Map.of("id", blockedId), String.class)).isEqualTo("waiting");
            assertThat(jdbc.update(query.value(), Map.of("id", clearId, "runId", runId,
                    "approvalId", clearApproval))).as("approved attempt without conflict resumes on MySQL").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM workflow_node_run WHERE id = :id",
                    Map.of("id", clearId), String.class)).isEqualTo("running");
            assertThat(jdbc.update(query.value(), Map.of("id", clearId, "runId", runId,
                    "approvalId", clearApproval))).as("already running attempt cannot resume again").isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM workflow_node_run WHERE id = :id",
                    Map.of("id", higherId), String.class)).isEqualTo("succeeded");
        }
    }

    private static long insertResumeApproval(Connection connection, String runId, String nodeKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO workflow_approval (run_id, node_key, node_type, title, status, approver_role, version) "
                        + "VALUES (?, ?, 'approval', 'Synthetic MySQL resume proof', 'approved', 'ADMIN', 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, runId);
            statement.setString(2, nodeKey);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                long id = keys.getLong(1);
                assertThat(id).isPositive();
                assertThat(keys.next()).isFalse();
                return id;
            }
        }
    }

    private static void insertResumeNode(NamedParameterJdbcTemplate jdbc, String runId, String id,
                                         String nodeKey, int attempt, String status, long approvalId) {
        assertThat(jdbc.update("INSERT INTO workflow_node_run "
                        + "(id, run_id, node_key, node_type, attempt, status, approval_id, cancel_delivered) "
                        + "VALUES (:id, :runId, :nodeKey, 'approval', :attempt, :status, :approvalId, 0)",
                Map.of("id", id, "runId", runId, "nodeKey", nodeKey, "attempt", attempt,
                        "status", status, "approvalId", approvalId))).isEqualTo(1);
    }

    private static void assertFlywayState(Flyway flyway) {
        assertThatCode(flyway::validate).doesNotThrowAnyException();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        List<MigrationInfo> applied = Arrays.asList(flyway.info().applied());
        List<MigrationInfo> baseline = applied.stream()
                .filter(info -> info.getType() == CoreMigrationType.BASELINE).toList();
        assertThat(baseline).extracting(info -> info.getVersion().getVersion()).containsExactly("1");
        assertThat(baseline.get(0).getState()).isEqualTo(MigrationState.BASELINE);
        List<MigrationInfo> sql = applied.stream().filter(info -> info.getType() == CoreMigrationType.SQL).toList();
        assertThat(sql).extracting(info -> info.getVersion().getVersion()).containsExactlyElementsOf(VERSIONS);
        assertThat(sql).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
    }

    private static void createSyntheticLegacyFixture(GuardedProofDataSource source, Connection connection)
            throws SQLException {
        Set<String> expected = new LinkedHashSet<>(Set.of(GUARD));
        checkedFixtureStatement(source, connection, expected,
                "CREATE TABLE `agent` (`id` VARCHAR(64) NOT NULL PRIMARY KEY, `hostname` VARCHAR(128) NOT NULL, "
                        + "`ip` VARCHAR(64) NOT NULL, `status` INT NOT NULL) ENGINE=InnoDB");
        expected.add("agent");
        checkedFixtureStatement(source, connection, expected,
                "CREATE TABLE `sys_user` (`id` BIGINT NOT NULL PRIMARY KEY, `username` VARCHAR(64) NOT NULL, "
                        + "`role` VARCHAR(32) NOT NULL, `status` INT NOT NULL) ENGINE=InnoDB");
        expected.add("sys_user");
        checkedFixtureStatement(source, connection, expected,
                "CREATE TABLE `task` (`id` VARCHAR(64) NOT NULL PRIMARY KEY, `created_by` BIGINT NOT NULL, "
                        + "`total_count` INT NOT NULL) ENGINE=InnoDB");
        expected.add("task");
        checkedFixtureStatement(source, connection, expected,
                "CREATE TABLE `audit_log` (`id` BIGINT NOT NULL PRIMARY KEY, `action` VARCHAR(64) NOT NULL) "
                        + "ENGINE=InnoDB");
        expected.add("audit_log");
        checkedFixtureStatement(source, connection, expected,
                "INSERT INTO `agent` (`id`, `hostname`, `ip`, `status`) VALUES "
                        + "('synthetic-a', 'alpha', '192.0.2.10', 1), "
                        + "('synthetic-b', 'beta', '192.0.2.10', 0)");
        checkedFixtureStatement(source, connection, expected,
                "INSERT INTO `sys_user` (`id`, `username`, `role`, `status`) VALUES "
                        + "(101, 'synthetic-viewer', 'viewer', 1), (102, 'synthetic-operator', 'operator', 1)");
        checkedFixtureStatement(source, connection, expected,
                "INSERT INTO `task` (`id`, `created_by`, `total_count`) VALUES ('synthetic-task', 101, 2)");
        checkedFixtureStatement(source, connection, expected,
                "INSERT INTO `audit_log` (`id`, `action`) VALUES (1, 'SYNTHETIC_1_1_2')");
    }

    private static void checkedFixtureStatement(GuardedProofDataSource source, Connection connection,
                                                Set<String> expectedTables, String sql) throws SQLException {
        source.beforeFixtureWrite(connection, expectedTables);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void acquireProofLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, PROOF_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && result.getInt(1) == 1 && !result.wasNull() && !result.next(),
                        "Could not acquire the dedicated proof lock");
            }
        }
    }

    private static void executeUpdate(Connection connection, String sql, int expected) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate(sql)).isEqualTo(expected);
        }
    }

    private static void assertAclVersionColumn(Connection connection) throws SQLException {
        String sql = "SELECT DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'host_identity' AND COLUMN_NAME = 'acl_version'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualToIgnoringCase("bigint");
            assertThat(result.getString(2)).isEqualTo("NO");
            assertThat(result.getString(3)).isEqualTo("0");
            assertThat(result.next()).isFalse();
        }
    }

    private static void assertCompatibleCollations(Connection connection) throws SQLException {
        String sql = "SELECT TABLE_NAME, COLUMN_NAME, COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND ((TABLE_NAME='agent' AND COLUMN_NAME='id') "
                + "OR (TABLE_NAME='host_identity' AND COLUMN_NAME='agent_id') "
                + "OR (TABLE_NAME='host_acl_grant' AND COLUMN_NAME='agent_id') "
                + "OR (TABLE_NAME='task' AND COLUMN_NAME='id') "
                + "OR (TABLE_NAME='task_target_snapshot_item' AND COLUMN_NAME='task_id'))";
        Map<String, String> collations = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) collations.put(result.getString(1) + "." + result.getString(2), result.getString(3));
        }
        assertThat(collations).hasSize(5);
        assertThat(collations.values()).doesNotContainNull().containsOnly(collations.get("agent.id"));
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT a.id FROM agent a JOIN host_identity i ON a.id = i.agent_id").close();
            statement.executeQuery("SELECT t.id FROM task t JOIN task_target_snapshot_item s ON t.id = s.task_id").close();
        }
    }

    private static void assertEnrollmentIndex(Connection connection) throws SQLException {
        String sql = "SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'agent_enrollment_token' AND INDEX_NAME = 'idx_agent_enrollment_active' "
                + "ORDER BY SEQ_IN_INDEX";
        List<IndexRow> indexes = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                indexes.add(new IndexRow(result.getString(1), result.getInt(2), result.getInt(3), result.getString(4)));
            }
        }
        assertThat(indexes).containsExactly(
                new IndexRow("idx_agent_enrollment_active", 1, 1, "agent_id"),
                new IndexRow("idx_agent_enrollment_active", 1, 2, "consumed_at"));
    }

    private static void assertEmptyIdentityAndAclSeed(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `host_identity`")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isZero();
            assertThat(result.next()).isFalse();
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT `id`, `completed` FROM `host_acl_state`")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
            assertThat(result.getInt(2)).isZero();
            assertThat(result.next()).isFalse();
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `host_acl_grant`")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isZero();
            assertThat(result.next()).isFalse();
        }
    }

    private static List<IdentityRow> readIdentityRows(Connection connection) throws SQLException {
        List<IdentityRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT `agent_id`, `generation`, `active`, `acl_version` "
                     + "FROM `host_identity` ORDER BY `agent_id`")) {
            while (result.next()) {
                rows.add(new IdentityRow(result.getString(1), result.getLong(2), result.getInt(3), result.getLong(4)));
            }
        }
        return rows;
    }

    private static List<AclStateRow> readAclState(Connection connection) throws SQLException {
        List<AclStateRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT `id`, `completed` FROM `host_acl_state` ORDER BY `id`")) {
            while (result.next()) rows.add(new AclStateRow(result.getInt(1), result.getInt(2)));
        }
        return rows;
    }

    private static Map<String, Long> readTableCounts(Connection connection) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new TreeSet<>(FINAL_TABLES)) {
            require(table.matches("[a-z0-9_]+"), "Unexpected proof table identifier");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                require(result.next(), "Could not count a proof table");
                counts.put(table, result.getLong(1));
                require(!result.next(), "Unexpected proof table count result");
            }
        }
        return counts;
    }

    private static List<List<Object>> readHistory(Connection connection) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM `flyway_schema_history` ORDER BY `installed_rank`")) {
            ResultSetMetaData meta = result.getMetaData();
            while (result.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) row.add(result.getObject(i));
                rows.add(row);
            }
        }
        return rows;
    }

    private static SyntheticSnapshot readLegacySnapshot(Connection connection) throws SQLException {
        List<AgentRow> agents = new ArrayList<>();
        List<UserRow> users = new ArrayList<>();
        List<TaskRow> tasks = new ArrayList<>();
        List<AuditRow> audits = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT `id`, `hostname`, `ip`, `status` FROM `agent` ORDER BY `id`")) {
            while (result.next()) agents.add(new AgentRow(result.getString(1), result.getString(2),
                    result.getString(3), result.getInt(4)));
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT `id`, `username`, `role`, `status` FROM `sys_user` ORDER BY `id`")) {
            while (result.next()) users.add(new UserRow(result.getLong(1), result.getString(2),
                    result.getString(3), result.getInt(4)));
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT `id`, `created_by`, `total_count` FROM `task` ORDER BY `id`")) {
            while (result.next()) tasks.add(new TaskRow(result.getString(1), result.getLong(2), result.getInt(3)));
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT `id`, `action` FROM `audit_log` ORDER BY `id`")) {
            while (result.next()) audits.add(new AuditRow(result.getLong(1), result.getString(2)));
        }
        return new SyntheticSnapshot(agents, users, tasks, audits);
    }

    private static Set<String> finalTables() {
        Set<String> names = new HashSet<>(FIXTURE_TABLES);
        names.addAll(MIGRATION_TABLES);
        names.add("flyway_schema_history");
        return Set.copyOf(names);
    }

    private static void require(boolean condition, String message) throws SQLException {
        if (!condition) throw new SQLException(message);
    }

    /** Immutable credentials without a generated toString that could expose the password. */
    private static final class ProofSettings {
        private final String url;
        private final String password;
        private final String nonce;

        private ProofSettings(String url, String password, String nonce) {
            this.url = url;
            this.password = password;
            this.nonce = nonce;
        }

        private static ProofSettings fromEnvironment() throws SQLException {
            ProofSettings settings = new ProofSettings(System.getenv("EASYSHELL_MYSQL_PROOF_URL"),
                    System.getenv("EASYSHELL_MYSQL_PROOF_PASSWORD"), System.getenv("EASYSHELL_MYSQL_PROOF_NONCE"));
            settings.requireCredentials();
            return settings;
        }

        private void requireCredentials() throws SQLException {
            require(REQUIRED_URL.equals(url), "Proof URL must exactly match the dedicated loopback URL");
            require("proof".equals(USERNAME), "Proof username must be proof");
            require(password != null, "EASYSHELL_MYSQL_PROOF_PASSWORD must be set");
            require(nonce != null && nonce.matches("[0-9a-fA-F]{64}"),
                    "EASYSHELL_MYSQL_PROOF_NONCE must contain exactly 64 hexadecimal characters");
        }

        private void beforeDriverManager() throws SQLException {
            requireCredentials();
            requireLoopbackOnly();
            require(DriverManager.getLogWriter() == null, "Disable JDBC DriverManager logging for this proof");
        }
    }

    /**
     * Every Flyway connection repeats the pre-connect checks and the read-only guard checks.
     * Once seeding starts, only the known synthetic and migration tables are accepted.
     */
    private static final class GuardedProofDataSource extends AbstractDataSource {
        private final ProofSettings settings;
        private Phase phase = Phase.GUARD_ONLY;
        private String serverUuid;
        private SyntheticSnapshot legacy;

        private GuardedProofDataSource(ProofSettings settings) { this.settings = settings; }

        @Override
        public synchronized Connection getConnection() throws SQLException {
            settings.beforeDriverManager();
            Connection connection;
            try {
                connection = DriverManager.getConnection(settings.url, USERNAME, settings.password);
            } catch (SQLException | RuntimeException failure) {
                // Do not expose driver connection details or credentials in test output.
                throw new SQLException("Could not open the isolated MySQL proof connection");
            }
            try {
                Target target = inspectReadOnly(connection);
                if (phase == Phase.GUARD_ONLY) {
                    require(target.tables().equals(Set.of(GUARD)), "Proof database is not guard-only");
                } else if (phase == Phase.MIGRATING) {
                    require(target.tables().containsAll(FIXTURE_TABLES) && FINAL_TABLES.containsAll(target.tables()),
                            "Unexpected tables during the MySQL proof migration");
                    require(readLegacySnapshot(connection).equals(legacy), "Synthetic legacy rows changed");
                } else {
                    require(target.tables().equals(FINAL_TABLES), "Unexpected final MySQL proof tables");
                    require(readLegacySnapshot(connection).equals(legacy), "Synthetic legacy rows changed");
                }
                if (serverUuid == null) serverUuid = target.serverUuid();
                else require(serverUuid.equals(target.serverUuid()), "MySQL server identity changed");
                return connection;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.close(); } catch (SQLException ignored) { }
                throw failure;
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            require(USERNAME.equals(username) && settings.password.equals(password), "Invalid proof credentials");
            return getConnection();
        }

        private synchronized void beforeFixtureWrite(Connection connection, Set<String> expected) throws SQLException {
            require(phase == Phase.GUARD_ONLY, "Fixture creation is no longer permitted");
            requireLoopbackOnly();
            Target target = inspectReadOnly(connection);
            require(serverUuid != null && serverUuid.equals(target.serverUuid()), "MySQL server identity changed");
            require(target.tables().equals(expected), "Unexpected tables before fixture creation");
            require(connection.getAutoCommit(), "The fixture connection must use autocommit");
        }

        private synchronized void beginMigrations(Connection connection, SyntheticSnapshot snapshot) throws SQLException {
            beforeFixtureWrite(connection, FIXTURE_TABLES);
            require(EXPECTED_LEGACY.equals(snapshot) && snapshot.equals(readLegacySnapshot(connection)),
                    "Synthetic legacy fixture does not match");
            legacy = snapshot;
            phase = Phase.MIGRATING;
        }

        private synchronized void markComplete(Connection connection) throws SQLException {
            require(phase == Phase.MIGRATING, "Unexpected proof migration phase");
            requireLoopbackOnly();
            Target target = inspectReadOnly(connection);
            require(serverUuid.equals(target.serverUuid()), "MySQL server identity changed");
            require(target.tables().equals(FINAL_TABLES), "Unexpected final MySQL proof tables");
            require(readLegacySnapshot(connection).equals(legacy), "Synthetic legacy rows changed");
            phase = Phase.COMPLETE;
        }

        private synchronized void beforeFinalWrite(Connection connection) throws SQLException {
            require(phase == Phase.COMPLETE, "Proof migration has not completed");
            requireLoopbackOnly();
            Target target = inspectReadOnly(connection);
            require(serverUuid.equals(target.serverUuid()), "MySQL server identity changed");
            require(target.tables().equals(FINAL_TABLES), "Unexpected final MySQL proof tables");
            require(readLegacySnapshot(connection).equals(legacy), "Synthetic legacy rows changed");
            require(connection.getAutoCommit(), "The identity probe connection must use autocommit");
        }

        private Target inspectReadOnly(Connection connection) throws SQLException {
            DatabaseMetaData metadata = connection.getMetaData();
            require("MySQL".equals(metadata.getDatabaseProductName())
                            && metadata.getDatabaseMajorVersion() == 8 && metadata.getDatabaseMinorVersion() == 0,
                    "The proof server must be MySQL 8.0");
            String currentUser;
            String uuid;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT DATABASE(), VERSION(), USER(), CURRENT_USER(), @@server_uuid")) {
                require(result.next(), "MySQL proof identity query returned no row");
                String database = result.getString(1);
                String version = result.getString(2);
                String loginUser = result.getString(3);
                currentUser = result.getString(4);
                uuid = result.getString(5);
                require(DATABASE.equals(database), "The selected database is not the proof database");
                require(version != null && version.matches("8\\.0\\.[0-9]+.*"), "The server is not MySQL 8.0");
                require(USERNAME.equals(accountName(loginUser)) && USERNAME.equals(accountName(currentUser)),
                        "The authenticated MySQL account must be proof");
                require(uuid != null && !uuid.isBlank(), "MySQL server UUID is unavailable");
                require(!result.next(), "MySQL proof identity query returned multiple rows");
            }
            requireDirectSelectPrivilege(connection, currentUser);
            Set<String> tables = readSchemaTables(connection);
            require(tables.contains(GUARD), "The proof guard table is missing");
            verifyGuardNonce(connection);
            return new Target(uuid, tables);
        }

        private void verifyGuardNonce(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT `nonce` FROM `easyshell_proof_guard` LIMIT 2")) {
                require(result.next(), "The proof guard must contain exactly one row");
                String actual = result.getString(1);
                require(actual != null && settings.nonce.equals(actual), "The proof guard nonce does not match");
                require(!result.next(), "The proof guard must contain exactly one row");
            }
        }
    }

    private static String accountName(String account) {
        if (account == null) return null;
        int separator = account.lastIndexOf('@');
        return separator <= 0 ? null : account.substring(0, separator);
    }

    private static void requireDirectSelectPrivilege(Connection connection, String currentUser) throws SQLException {
        int separator = currentUser.lastIndexOf('@');
        require(separator > 0 && separator < currentUser.length() - 1, "Cannot identify the proof account host");
        String host = currentUser.substring(separator + 1).replace("'", "''");
        String grantee = "'proof'@'" + host + "'";
        boolean allowed = false;
        // MySQL may expose an exact database grant with LIKE metacharacters escaped,
        // e.g. easyshell\_upgrade\_proof; do not accept wildcard grants by pattern matching.
        String escapedDatabase = DATABASE.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
        String schemaSql = "SELECT GRANTEE, TABLE_SCHEMA FROM information_schema.SCHEMA_PRIVILEGES "
                + "WHERE PRIVILEGE_TYPE = 'SELECT'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(schemaSql)) {
            while (result.next()) {
                String schema = result.getString(2);
                if (grantee.equals(result.getString(1))
                        && (DATABASE.equals(schema) || escapedDatabase.equals(schema))) allowed = true;
            }
        }
        String globalSql = "SELECT GRANTEE FROM information_schema.USER_PRIVILEGES WHERE PRIVILEGE_TYPE = 'SELECT'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(globalSql)) {
            while (result.next()) if (grantee.equals(result.getString(1))) allowed = true;
        }
        // This avoids treating privilege-filtered metadata as proof that other tables do not exist.
        require(allowed, "The proof account needs direct schema-level or global SELECT for metadata visibility");
    }

    private static Set<String> readSchemaTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        String sql = "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                require("BASE TABLE".equals(result.getString(2)), "Views or non-table schema objects are not allowed");
                require(tables.add(result.getString(1)), "Duplicate MySQL proof table metadata");
            }
        }
        return tables;
    }

    private static void requireLoopbackOnly() throws SQLException {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            require(interfaces != null, "Cannot enumerate network interfaces");
            List<NetworkInterface> pending = new ArrayList<>(Collections.list(interfaces));
            Set<String> visited = new HashSet<>();
            boolean sawLoopback = false;
            boolean sawIpv4Loopback = false;
            for (int i = 0; i < pending.size(); i++) {
                NetworkInterface network = pending.get(i);
                if (!visited.add(network.getName())) continue;
                require(network.isLoopback(), "Only loopback network interfaces are allowed");
                sawLoopback = true;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    require(address.isLoopbackAddress(), "Only loopback interface addresses are allowed");
                    if ("127.0.0.1".equals(address.getHostAddress())) sawIpv4Loopback = true;
                }
                pending.addAll(Collections.list(network.getSubInterfaces()));
            }
            require(sawLoopback && sawIpv4Loopback, "IPv4 loopback is not available in this namespace");
            Set<String> kernelInterfaces = new HashSet<>();
            for (String line : Files.readAllLines(Path.of("/proc/self/net/dev"))) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).trim();
                require(!name.isEmpty() && kernelInterfaces.add(name), "Cannot verify kernel network interfaces");
            }
            require(kernelInterfaces.equals(visited), "JVM and kernel network interface lists differ");
        } catch (IOException | SecurityException failure) {
            throw new SQLException("Cannot verify the network namespace interfaces");
        }
    }

    private enum Phase { GUARD_ONLY, MIGRATING, COMPLETE }
    private record Target(String serverUuid, Set<String> tables) { }
    private record AgentRow(String id, String hostname, String ip, int status) { }
    private record UserRow(long id, String username, String role, int status) { }
    private record TaskRow(String id, long createdBy, int totalCount) { }
    private record AuditRow(long id, String action) { }
    private record SyntheticSnapshot(List<AgentRow> agents, List<UserRow> users,
                                     List<TaskRow> tasks, List<AuditRow> audits) { }
    private record IdentityRow(String agentId, long generation, int active, long aclVersion) { }
    private record AclStateRow(int id, int completed) { }
    private record IndexRow(String name, int nonUnique, int position, String column) { }
}
