package com.easyshell.server.workflow;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
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
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Flyway migrations against isolated, uniquely named in-memory H2 databases.
 * The old tables below are a small simulated 1.1.2 fixture, not an actual old database.
 * These tests do not start Spring or exercise the separate Java ACL backfill.
 */
class Release112MigrationH2Test {
    private static final List<String> VERSIONS = List.of(
            "1.2.0", "1.2.1", "1.2.2", "1.2.10", "1.2.11", "1.2.12",
            "1.2.13", "1.2.14", "1.2.15", "1.2.17", "1.2.19", "1.2.20");

    private static final Map<String, TableSpec> TABLES = Map.ofEntries(
            Map.entry("workflow", table(
                    "id name description category status visibility source created_by current_version_id "
                            + "run_count success_count last_run_at created_at updated_at", "id")),
            Map.entry("workflow_version", table(
                    "id workflow_id version base_version definition_json definition_sha256 changelog is_valid "
                            + "estimated_risk published_at published_by created_at updated_at", "id")),
            Map.entry("workflow_run", table(
                    "id workflow_id version_id version_no trigger_type status input_json output_json error_message "
                            + "variables_json variables_truncated current_nodes cancel_requested owner_instance "
                            + "heartbeat_at idempotency_key risk_snapshot_json started_by started_at finished_at "
                            + "duration_ms created_at updated_at", "id")),
            Map.entry("workflow_node_run", table(
                    "id run_id node_key node_type attempt status input_json output_json output_sha256 preview_sha256 "
                            + "raw_params artifact_ids error_message warning_message agent_versions cancel_delivered "
                            + "task_id approval_id started_at finished_at duration_ms created_at updated_at", "id")),
            Map.entry("workflow_approval", table(
                    "id run_id node_key node_type title detail_json risk_level risk_matches status approver_role "
                            + "decided_by decided_at decision_comment expires_at version created_at updated_at", "id")),
            Map.entry("host_identity", table("agent_id generation active acl_version", "agent_id")),
            Map.entry("host_acl_state", table("id completed", "id")),
            Map.entry("host_acl_grant", table("agent_id generation subject_type subject_id permission",
                    "agent_id generation subject_type subject_id permission")),
            Map.entry("workflow_dispatch_approval_binding", table(
                    "approval_id dispatch_node_run_id dispatch_attempt dispatch_user_id dispatch_script_hmac "
                            + "dispatch_intent_hmac dispatch_targets dispatch_timeout_sec dispatch_consumed_at "
                            + "dispatch_task_id", "approval_id")),
            Map.entry("task_target_snapshot", table("task_id target_count completed", "task_id")),
            Map.entry("task_target_snapshot_item", table("task_id agent_id generation", "task_id agent_id")),
            Map.entry("workflow_run_provenance", table(
                    "run_id version_id definition_sha256 source_kind task_count completed", "run_id")),
            Map.entry("workflow_run_provenance_task", table("run_id task_id", "run_id task_id")),
            Map.entry("ai_inspect_report_task_binding", table("report_id task_id", "report_id")),
            Map.entry("agent_enrollment_token", table(
                    "token_hash agent_id generation initiated_by expires_at consumed_at", "token_hash")),
            Map.entry("workflow_run_v3_birth", table("run_id version_id definition_sha256 semantics", "run_id")),
            Map.entry("agent_bound_key", table("agent_id generation public_key", "agent_id generation")),
            Map.entry("agent_key_challenge", table(
                    "challenge_hash agent_id generation expires_at consumed_at", "challenge_hash")),
            Map.entry("user_refresh_generation", table("user_id generation", "user_id")),
            Map.entry("workflow_recovery_run", table("run_id last_round active_round", "run_id")),
            Map.entry("workflow_recovery_batch", table("run_id claim_round owner_id fencing_token expires_at created_at closed_at", "run_id claim_round")),
            Map.entry("workflow_recovery_member", table("run_id claim_round node_run_id node_key attempt claim_state updated_at", "run_id claim_round node_run_id attempt")));

    private static final List<String> LEGACY_TABLES = List.of("agent", "sys_user", "task", "audit_log");

    // Handwritten copy of the development-era 1.2.1, before acl_version existed.
    private static final String OLD_IDENTITY_SQL = """
            -- Independent of Hibernate-managed agent: also valid on an empty database.
            -- Rows are permanent tombstones; never delete or reset a generation.
            CREATE TABLE host_identity (
                agent_id VARCHAR(64) NOT NULL,
                generation BIGINT NOT NULL DEFAULT 0,
                active TINYINT NOT NULL DEFAULT 0,
                PRIMARY KEY (agent_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    // Handwritten simulations of the development-era enrollment table and later additions.
    private static final String OLD_ENROLLMENT_SQL = """
            -- Standalone enrollment ledger; existing agent and identity data are unchanged.
            CREATE TABLE agent_enrollment_token (
                token_hash CHAR(64) NOT NULL,
                agent_id VARCHAR(64) NOT NULL,
                generation BIGINT NOT NULL,
                initiated_by BIGINT NOT NULL,
                expires_at TIMESTAMP(6) NOT NULL,
                consumed_at TIMESTAMP(6) NULL,
                PRIMARY KEY (token_hash)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String OLD_ENROLLMENT_INDEX_SQL = """
            -- Simulated development migration: index added after enrollment table creation.
            CREATE INDEX idx_agent_enrollment_active ON agent_enrollment_token (agent_id, consumed_at);
            """;

    private static final String OLD_ACL_VERSION_SQL = """
            -- Simulated development migration: version added after identity table creation.
            ALTER TABLE host_identity ADD COLUMN acl_version BIGINT NOT NULL DEFAULT 0;
            """;

    @Test
    void emptyDatabaseMigratesWithoutBaselineAndRepeatsWithoutChanges() throws Exception {
        String url = isolatedUrl();
        // Keep the in-memory database alive while Flyway opens its own connections.
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Flyway flyway = currentFlyway(url);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(VERSIONS.size());
            assertAppliedVersions(flyway, false);
            assertFinalSchema(connection);
            assertDefaultAclVersionAndSeed(connection);
            assertRepeatMigrationIsUnchanged(flyway, connection);
        }
    }

    @Test
    void simulatedNonempty112DatabaseBaselinesAndPreservesLegacyRows() throws Exception {
        String url = isolatedUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            createSimulatedLegacyFixture(connection);
            Map<String, List<List<Object>>> before = snapshotTables(connection, LEGACY_TABLES);

            Flyway flyway = currentFlyway(url);
            // No baselineVersion override: application.yml also leaves it unset.
            assertThat(flyway.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("1");
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(VERSIONS.size());
            assertAppliedVersions(flyway, true);
            assertFinalSchema(connection);
            assertDefaultAclVersionAndSeed(connection);
            assertThat(snapshotTables(connection, LEGACY_TABLES)).isEqualTo(before);

            assertRepeatMigrationIsUnchanged(flyway, connection);
            assertThat(snapshotTables(connection, LEGACY_TABLES)).isEqualTo(before);
        }
    }

    @Test
    void appliedDevelopmentIdentityMigrationFailsChecksumValidationWithoutRepair(@TempDir Path oldScripts)
            throws Exception {
        Files.writeString(oldScripts.resolve("V1_2_1__host_identity.sql"), OLD_IDENTITY_SQL);
        String url = isolatedUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Flyway oldFlyway = Flyway.configure()
                    .dataSource(url, "sa", "")
                    .locations("filesystem:" + oldScripts.toAbsolutePath())
                    .validateOnMigrate(true)
                    .cleanDisabled(true)
                    .load();
            assertThat(oldFlyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThatCode(oldFlyway::validate).doesNotThrowAnyException();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO host_identity (agent_id, generation, active) "
                        + "VALUES ('legacy-host', 7, 1)");
            }
            List<List<Object>> identityBefore = rows(connection, "host_identity");
            List<List<Object>> historyBefore = rows(connection, "flyway_schema_history");
            assertThat(identityBefore).hasSize(1);
            // Flyway may also persist an internal TABLE row in schema history.
            assertThat(oldFlyway.info().applied())
                    .extracting(info -> info.getVersion().getVersion()).containsExactly("1.2.1");
            assertThat(historyBefore).isNotEmpty();

            Flyway current = currentFlyway(url);
            var validation = current.validateWithResult();
            assertThat(validation.validationSuccessful).isFalse();
            assertThat(validation.invalidMigrations).anySatisfy(invalid -> {
                assertThat(invalid.version).isEqualTo("1.2.1");
                assertThat(invalid.errorDetails.errorCode).isEqualTo(CoreErrorCode.CHECKSUM_MISMATCH);
            });
            assertThatThrownBy(current::validate).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("1.2.1").hasMessageContaining("checksum");
            assertThat(rows(connection, "host_identity")).isEqualTo(identityBefore);
            assertThat(rows(connection, "flyway_schema_history")).isEqualTo(historyBefore);

            assertThatThrownBy(current::migrate).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("1.2.1").hasMessageContaining("checksum");
            assertThat(rows(connection, "host_identity")).isEqualTo(identityBefore);
            assertThat(rows(connection, "flyway_schema_history")).isEqualTo(historyBefore);
            assertThat(columnNames(connection, "host_identity"))
                    .containsExactly("agent_id", "generation", "active");
        }
    }

    /** Only models this handwritten development state; it says nothing about any deployed database. */
    @Test
    void simulatedAppliedDevelopmentMigrationsRejectCurrentChainWithoutChangingData(@TempDir Path oldScripts)
            throws Exception {
        Files.writeString(oldScripts.resolve("V1_2_1__host_identity.sql"), OLD_IDENTITY_SQL);
        // Copy the actual classpath bytes so the ACL state/grant DDL and seed are unchanged.
        try (InputStream input = Release112MigrationH2Test.class
                .getResourceAsStream("/db/migration/V1_2_2__host_acl.sql")) {
            assertThat(input).as("current 1.2.2 ACL migration resource").isNotNull();
            Files.copy(input, oldScripts.resolve("V1_2_2__host_acl.sql"));
        }
        Files.writeString(oldScripts.resolve("V1_2_14__agent_enrollment_token.sql"), OLD_ENROLLMENT_SQL);
        Files.writeString(oldScripts.resolve("V1_2_16__agent_enrollment_lookup_index.sql"), OLD_ENROLLMENT_INDEX_SQL);
        Files.writeString(oldScripts.resolve("V1_2_18__host_acl_version.sql"), OLD_ACL_VERSION_SQL);

        String url = isolatedUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Flyway oldFlyway = Flyway.configure()
                    .dataSource(url, "sa", "")
                    .locations("filesystem:" + oldScripts.toAbsolutePath())
                    .validateOnMigrate(true)
                    .cleanDisabled(true)
                    .load();
            assertThat(oldFlyway.migrate().migrationsExecuted).isEqualTo(5);
            assertThatCode(oldFlyway::validate).doesNotThrowAnyException();
            List<MigrationInfo> oldApplied = Arrays.stream(oldFlyway.info().applied())
                    .filter(info -> info.getType() == CoreMigrationType.SQL).toList();
            assertThat(oldApplied).extracting(info -> info.getVersion().getVersion())
                    .containsExactly("1.2.1", "1.2.2", "1.2.14", "1.2.16", "1.2.18");
            assertThat(oldApplied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));

            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("UPDATE host_acl_state SET completed = 1 WHERE id = 1"))
                        .isEqualTo(1);
                assertThat(statement.executeUpdate("INSERT INTO host_identity "
                        + "(agent_id, generation, active, acl_version) VALUES "
                        + "('legacy-live', 5, 1, 17), ('legacy-retired', 8, 0, 23)"))
                        .isEqualTo(2);
                assertThat(statement.executeUpdate("INSERT INTO host_acl_grant "
                        + "(agent_id, generation, subject_type, subject_id, permission) VALUES "
                        + "('legacy-live', 5, 'USER', '42', 'EXECUTE'), "
                        + "('legacy-retired', 7, 'USER', '42', 'VIEW')"))
                        .isEqualTo(2);
            }
            try (PreparedStatement token = connection.prepareStatement("INSERT INTO agent_enrollment_token "
                    + "(token_hash, agent_id, generation, initiated_by, expires_at, consumed_at) "
                    + "VALUES (?, 'legacy-live', 5, 42, TIMESTAMP '2031-01-01 00:00:00', NULL)")) {
                token.setString(1, "a".repeat(64));
                assertThat(token.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement tombstone = connection.prepareStatement("SELECT generation, active, acl_version "
                    + "FROM host_identity WHERE agent_id = ?")) {
                tombstone.setString(1, "legacy-retired");
                try (ResultSet result = tombstone.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(8L);
                    assertThat(result.getInt(2)).isZero();
                    assertThat(result.getLong(3)).isEqualTo(23L);
                    assertThat(result.next()).isFalse();
                }
            }

            List<String> tablesBefore = businessTableNames(connection);
            assertThat(tablesBefore).containsExactlyInAnyOrder(
                    "host_identity", "host_acl_state", "host_acl_grant", "agent_enrollment_token");
            Map<String, List<List<Object>>> dataBefore = snapshotTables(connection, tablesBefore);
            assertThat(dataBefore.get("host_identity")).hasSize(2);
            assertThat(dataBefore.get("host_acl_state")).hasSize(1);
            assertThat(dataBefore.get("host_acl_grant")).hasSize(2);
            assertThat(dataBefore.get("agent_enrollment_token")).hasSize(1);
            List<List<Object>> historyBefore = rows(connection, "flyway_schema_history");
            assertThat(historyBefore).isNotEmpty();

            Flyway current = currentFlyway(url);
            List<MigrationInfo> currentInfo = Arrays.asList(current.info().all());
            List<String> resolvedVersions = currentInfo.stream()
                    .filter(info -> info.getVersion() != null)
                    .map(info -> info.getVersion().getVersion()).toList();
            // 1.2.19 makes the absent 1.2.16 and 1.2.18 missing, not future, migrations.
            assertThat(resolvedVersions).contains("1.2.19");
            assertMissingMigration(currentInfo, "1.2.16");
            assertMissingMigration(currentInfo, "1.2.18");
            ValidateResult validation = current.validateWithResult();
            assertThat(validation.validationSuccessful).isFalse();
            assertMigrationError(validation, "1.2.1", CoreErrorCode.CHECKSUM_MISMATCH);
            assertMigrationError(validation, "1.2.14", CoreErrorCode.CHECKSUM_MISMATCH);
            assertMigrationError(validation, "1.2.16", CoreErrorCode.APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED);
            assertMigrationError(validation, "1.2.18", CoreErrorCode.APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED);
            assertThat(validation.invalidMigrations).noneMatch(invalid -> "1.2.2".equals(invalid.version));
            assertDevelopmentStateUnchanged(connection, tablesBefore, dataBefore, historyBefore);

            assertThatThrownBy(current::validate).isInstanceOf(FlywayValidateException.class)
                    .hasMessageContaining("1.2.1").hasMessageContaining("1.2.14")
                    .hasMessageContaining("1.2.16").hasMessageContaining("1.2.18");
            assertDevelopmentStateUnchanged(connection, tablesBefore, dataBefore, historyBefore);
            assertThatThrownBy(current::migrate).isInstanceOf(FlywayValidateException.class)
                    .hasMessageContaining("1.2.1").hasMessageContaining("1.2.14")
                    .hasMessageContaining("1.2.16").hasMessageContaining("1.2.18");
            assertDevelopmentStateUnchanged(connection, tablesBefore, dataBefore, historyBefore);
        }
    }

    private static void assertMigrationError(ValidateResult result, String version, CoreErrorCode expected) {
        List<ValidateOutput> errors = result.invalidMigrations.stream()
                .filter(invalid -> version.equals(invalid.version)).toList();
        assertThat(errors).as("validation errors for %s", version).hasSize(1);
        assertThat(errors.get(0).errorDetails).isNotNull();
        assertThat(errors.get(0).errorDetails.errorCode).as("error code for %s", version).isEqualTo(expected);
    }

    private static void assertMissingMigration(List<MigrationInfo> info, String version) {
        List<MigrationInfo> matches = info.stream()
                .filter(item -> item.getVersion() != null && version.equals(item.getVersion().getVersion())).toList();
        assertThat(matches).as("migration info for %s", version).hasSize(1);
        assertThat(matches.get(0).getState()).as("state for %s", version).isEqualTo(MigrationState.MISSING_SUCCESS);
    }

    private static void assertDevelopmentStateUnchanged(Connection connection, List<String> tablesBefore,
                                                         Map<String, List<List<Object>>> dataBefore,
                                                         List<List<Object>> historyBefore) throws SQLException {
        assertThat(businessTableNames(connection)).containsExactlyElementsOf(tablesBefore);
        assertThat(snapshotTables(connection, tablesBefore)).isEqualTo(dataBefore);
        assertThat(rows(connection, "flyway_schema_history")).isEqualTo(historyBefore);
    }

    private static String isolatedUrl() {
        return "jdbc:h2:mem:release112_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
    }

    private static Flyway currentFlyway(String url) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();
    }

    private static void assertAppliedVersions(Flyway flyway, boolean hasBaseline) {
        assertThatCode(flyway::validate).doesNotThrowAnyException();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();

        List<MigrationInfo> applied = Arrays.asList(flyway.info().applied());
        List<MigrationInfo> sql = applied.stream().filter(info -> info.getType() == CoreMigrationType.SQL).toList();
        assertThat(sql).extracting(info -> info.getVersion().getVersion()).containsExactlyElementsOf(VERSIONS);
        assertThat(sql).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));

        List<MigrationInfo> baselines = applied.stream()
                .filter(info -> info.getType() == CoreMigrationType.BASELINE).toList();
        if (hasBaseline) {
            assertThat(baselines).extracting(info -> info.getVersion().getVersion()).containsExactly("1");
            assertThat(baselines.get(0).getState()).isEqualTo(MigrationState.BASELINE);
        } else {
            assertThat(baselines).isEmpty();
        }
    }

    private static void assertFinalSchema(Connection connection) throws SQLException {
        for (Map.Entry<String, TableSpec> entry : TABLES.entrySet()) {
            assertThat(columnNames(connection, entry.getKey())).as("columns of %s", entry.getKey())
                    .containsExactlyElementsOf(entry.getValue().columns());
            assertThat(primaryKey(connection, entry.getKey())).as("primary key of %s", entry.getKey())
                    .containsExactlyElementsOf(entry.getValue().primaryKey());
        }

        assertForeignKey(connection, "task_target_snapshot_item", "task_id", "task_target_snapshot", "task_id");
        assertForeignKey(connection, "workflow_run_provenance_task", "run_id", "workflow_run_provenance", "run_id");
        assertForeignKey(connection, "ai_inspect_report_task_binding", "task_id", "task_target_snapshot", "task_id");
        assertUniqueIndex(connection, "workflow_version", List.of("workflow_id", "version"));
        assertUniqueIndex(connection, "workflow_run", List.of("started_by", "idempotency_key"));
        assertUniqueIndex(connection, "workflow_node_run", List.of("run_id", "node_key", "attempt"));
        assertUniqueIndex(connection, "workflow_run_provenance_task", List.of("task_id"));
        assertUniqueIndex(connection, "ai_inspect_report_task_binding", List.of("task_id"));
        assertEnrollmentIndex(connection);
    }

    private static void assertDefaultAclVersionAndSeed(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String tableName = actualTableName(connection, "host_identity");
        int matches = 0;
        try (ResultSet columns = meta.getColumns(connection.getCatalog(), connection.getSchema(), tableName, null)) {
            while (columns.next()) {
                if (tableName.equals(columns.getString("TABLE_NAME"))
                        && "acl_version".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    matches++;
                    assertThat(columns.getInt("DATA_TYPE")).isEqualTo(Types.BIGINT);
                    assertThat(columns.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNoNulls);
                    assertThat(columns.getString("COLUMN_DEF")).isNotBlank();
                }
            }
        }
        assertThat(matches).isEqualTo(1);

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO host_identity (agent_id, generation, active) VALUES (?, ?, ?)")) {
            insert.setString(1, "default-version-probe");
            insert.setLong(2, 7L);
            insert.setInt(3, 1);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT generation, active, acl_version FROM host_identity WHERE agent_id = ?")) {
            select.setString(1, "default-version-probe");
            try (ResultSet result = select.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(7L);
                assertThat(result.getInt(2)).isEqualTo(1);
                assertThat(result.getLong(3)).isZero();
                assertThat(result.wasNull()).isFalse();
                assertThat(result.next()).isFalse();
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO host_identity (agent_id) VALUES (?)")) {
            insert.setString(1, "default-identity-probe");
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT generation, active, acl_version FROM host_identity WHERE agent_id = ?")) {
            select.setString(1, "default-identity-probe");
            try (ResultSet result = select.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
                assertThat(result.wasNull()).isFalse();
                assertThat(result.getInt(2)).isZero();
                assertThat(result.wasNull()).isFalse();
                assertThat(result.getLong(3)).isZero();
                assertThat(result.wasNull()).isFalse();
                assertThat(result.next()).isFalse();
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet seed = statement.executeQuery("SELECT id, completed FROM host_acl_state")) {
            assertThat(seed.next()).isTrue();
            assertThat(seed.getInt(1)).isEqualTo(1);
            assertThat(seed.getInt(2)).isZero();
            assertThat(seed.next()).isFalse();
        }
    }

    private static void assertRepeatMigrationIsUnchanged(Flyway flyway, Connection connection) throws SQLException {
        List<List<Object>> history = rows(connection, "flyway_schema_history");
        List<List<Object>> identity = rows(connection, "host_identity");
        List<List<Object>> aclState = rows(connection, "host_acl_state");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThatCode(flyway::validate).doesNotThrowAnyException();
        assertThat(rows(connection, "flyway_schema_history")).isEqualTo(history);
        assertThat(rows(connection, "host_identity")).isEqualTo(identity);
        assertThat(rows(connection, "host_acl_state")).isEqualTo(aclState);
    }

    private static void createSimulatedLegacyFixture(Connection connection) throws SQLException {
        // Deliberately minimal representative tables, not a reconstruction of the complete 1.1.2 schema.
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent (id VARCHAR(64) PRIMARY KEY, hostname VARCHAR(128))");
            statement.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, role VARCHAR(32), status INT)");
            statement.execute("CREATE TABLE task (id VARCHAR(64) PRIMARY KEY, created_by BIGINT, total_count INT)");
            statement.execute("CREATE TABLE audit_log (id BIGINT PRIMARY KEY, action VARCHAR(64))");
            statement.executeUpdate("INSERT INTO agent VALUES ('old-agent-a', 'alpha'), ('old-agent-b', 'beta')");
            statement.executeUpdate("INSERT INTO sys_user VALUES (1, 'admin', 1), (2, 'viewer', 1)");
            statement.executeUpdate("INSERT INTO task VALUES ('old-task', 1, 2)");
            statement.executeUpdate("INSERT INTO audit_log VALUES (1, 'OLD_EVENT')");
        }
    }

    private static List<String> columnNames(Connection connection, String name) throws SQLException {
        String actual = actualTableName(connection, name);
        Map<Integer, String> columns = new TreeMap<>();
        try (ResultSet result = connection.getMetaData()
                .getColumns(connection.getCatalog(), connection.getSchema(), actual, null)) {
            while (result.next()) {
                if (actual.equals(result.getString("TABLE_NAME"))) {
                    columns.put(result.getInt("ORDINAL_POSITION"), normalize(result.getString("COLUMN_NAME")));
                }
            }
        }
        return List.copyOf(columns.values());
    }

    private static List<String> primaryKey(Connection connection, String name) throws SQLException {
        Map<Short, String> columns = new TreeMap<>();
        try (ResultSet result = connection.getMetaData()
                .getPrimaryKeys(connection.getCatalog(), connection.getSchema(), actualTableName(connection, name))) {
            while (result.next()) {
                columns.put(result.getShort("KEY_SEQ"), normalize(result.getString("COLUMN_NAME")));
            }
        }
        return List.copyOf(columns.values());
    }

    private static void assertEnrollmentIndex(Connection connection) throws SQLException {
        Map<Short, String> columns = new TreeMap<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(),
                connection.getSchema(), actualTableName(connection, "agent_enrollment_token"), false, false)) {
            while (indexes.next()) {
                if ("idx_agent_enrollment_active".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    assertThat(indexes.getBoolean("NON_UNIQUE")).isTrue();
                    columns.put(indexes.getShort("ORDINAL_POSITION"), normalize(indexes.getString("COLUMN_NAME")));
                }
            }
        }
        assertThat(columns.keySet()).containsExactly((short) 1, (short) 2);
        assertThat(columns.values()).containsExactly("agent_id", "consumed_at");
    }

    private static void assertUniqueIndex(Connection connection, String table, List<String> expected)
            throws SQLException {
        Map<String, Map<Short, String>> indexes = new LinkedHashMap<>();
        try (ResultSet result = connection.getMetaData().getIndexInfo(connection.getCatalog(),
                connection.getSchema(), actualTableName(connection, table), false, false)) {
            while (result.next()) {
                String index = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (index != null && column != null && !result.getBoolean("NON_UNIQUE")) {
                    indexes.computeIfAbsent(index, ignored -> new TreeMap<>())
                            .put(result.getShort("ORDINAL_POSITION"), normalize(column));
                }
            }
        }
        List<List<String>> uniqueColumns = indexes.values().stream()
                .map(columns -> List.copyOf(columns.values())).toList();
        assertThat(uniqueColumns).as("unique index on %s", table).contains(expected);
    }

    private static void assertForeignKey(Connection connection, String table, String column,
                                         String parent, String parentColumn) throws SQLException {
        List<List<String>> keys = new ArrayList<>();
        try (ResultSet result = connection.getMetaData().getImportedKeys(connection.getCatalog(),
                connection.getSchema(), actualTableName(connection, table))) {
            while (result.next()) {
                keys.add(List.of(normalize(result.getString("FKCOLUMN_NAME")),
                        normalize(result.getString("PKTABLE_NAME")), normalize(result.getString("PKCOLUMN_NAME"))));
            }
        }
        assertThat(keys).as("foreign key on %s", table).contains(List.of(column, parent, parentColumn));
    }

    private static Map<String, List<List<Object>>> snapshotTables(Connection connection, List<String> names)
            throws SQLException {
        Map<String, List<List<Object>>> snapshot = new LinkedHashMap<>();
        for (String name : names) snapshot.put(name, rows(connection, name));
        return snapshot;
    }

    private static List<String> businessTableNames(Connection connection) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (ResultSet tables = connection.getMetaData()
                .getTables(connection.getCatalog(), connection.getSchema(), null, null)) {
            while (tables.next()) {
                String type = tables.getString("TABLE_TYPE");
                String name = tables.getString("TABLE_NAME");
                if (("TABLE".equalsIgnoreCase(type) || "BASE TABLE".equalsIgnoreCase(type))
                        && !"flyway_schema_history".equalsIgnoreCase(name)) {
                    assertThat(names.add(normalize(name))).as("duplicate table name %s", name).isTrue();
                }
            }
        }
        return List.copyOf(names);
    }

    private static List<List<Object>> rows(Connection connection, String name) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString().trim();
        assertThat(quote).as("identifier quote").isNotEmpty();
        String actual = actualTableName(connection, name);
        String quoted = quote + actual.replace(quote, quote + quote) + quote;
        List<List<Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + quoted + " ORDER BY 1")) {
            ResultSetMetaData meta = result.getMetaData();
            while (result.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) row.add(result.getObject(i));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String actualTableName(Connection connection, String name) throws SQLException {
        List<String> matches = new ArrayList<>();
        try (ResultSet tables = connection.getMetaData()
                .getTables(connection.getCatalog(), connection.getSchema(), null, null)) {
            while (tables.next()) {
                String type = tables.getString("TABLE_TYPE");
                String actual = tables.getString("TABLE_NAME");
                if (("TABLE".equalsIgnoreCase(type) || "BASE TABLE".equalsIgnoreCase(type))
                        && name.equalsIgnoreCase(actual)) {
                    matches.add(actual);
                }
            }
        }
        assertThat(matches).as("table %s", name).hasSize(1);
        return matches.get(0);
    }

    private static String normalize(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    private static TableSpec table(String columns, String primaryKey) {
        return new TableSpec(List.of(columns.split("\\s+")), List.of(primaryKey.split("\\s+")));
    }

    private record TableSpec(List<String> columns, List<String> primaryKey) { }
}
