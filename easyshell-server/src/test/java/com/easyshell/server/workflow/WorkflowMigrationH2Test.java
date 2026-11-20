package com.easyshell.server.workflow;

import com.easyshell.server.config.WorkflowSchemaConsistencyChecker;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Flyway 迁移在 H2(MODE=MySQL) 上的落地验证（设计 §5.4 / 复核 C-16 的 CI 门禁）：
 * <p>① 六张工作流表被真实迁移脚本建出，字段/主键/唯一键/索引齐全；
 * ② flyway_schema_history 里每个 version 都能在 db/migration 找到脚本（F3-③）；
 * ③ 迁移脚本零 ALTER / 零 DROP、绝不碰存量 22 张表（MF-6 / MF-7）；
 * ④ Flyway 自身 validate 通过（validate-on-migrate 的启动即 fail-fast 语义）；
 * ⑤ 启动一致性校验器（实体 ↔ DDL 逐字段）在迁移后的库上报告零违规。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=warn"})
@DisplayName("Flyway 迁移 — H2(MODE=MySQL) 上建出六张工作流表")
class WorkflowMigrationH2Test {

    /** Flyway 纳管的工作流 JPA 表 */
    private static final Set<String> WORKFLOW_TABLES = Set.of(
            "workflow", "workflow_version", "workflow_run", "workflow_node_run", "workflow_approval",
            "workflow_dispatch_approval_binding");

    /** 存量 22 张表：迁移脚本一个都不许碰（零 ALTER 旧表原则） */
    private static final Set<String> LEGACY_TABLES = Set.of(
            "agent", "agent_definitions", "agent_tag", "ai_chat_message", "ai_chat_session",
            "ai_inspect_report", "ai_iteration_message", "ai_scheduled_task", "ai_session_summary",
            "ai_sop_template", "audit_log", "cluster", "cluster_agent", "host_credential",
            "host_software_inventory", "job", "metric_snapshot", "script", "system_config",
            "sys_user", "tag", "task");

    /** 唯一键：表 -> 键名 -> 列集合（设计 §5.2） */
    private static final Map<String, Map<String, List<String>>> EXPECTED_UNIQUE_KEY_COLUMNS = Map.of(
            "workflow_version", Map.of("uk_wv_workflow_version", List.of("WORKFLOW_ID", "VERSION")),
            "workflow_run", Map.of("uk_wfr_idem", List.of("STARTED_BY", "IDEMPOTENCY_KEY")),
            "workflow_node_run", Map.of("uk_node_run", List.of("RUN_ID", "NODE_KEY", "ATTEMPT")));

    /** 索引：表 -> 索引名 -> 列集合（设计 §5.2） */
    private static final Map<String, Map<String, List<String>>> EXPECTED_INDEX_COLUMNS = Map.of(
            "workflow", Map.of(
                    "idx_wf_category", List.of("CATEGORY"),
                    "idx_wf_status_visibility", List.of("STATUS", "VISIBILITY"),
                    "idx_wf_created_by", List.of("CREATED_BY")),
            "workflow_run", Map.of(
                    "idx_wfr_workflow", List.of("WORKFLOW_ID"),
                    "idx_wfr_status", List.of("STATUS"),
                    "idx_wfr_started_at", List.of("STARTED_AT"),
                    "idx_wfr_owner_heartbeat", List.of("OWNER_INSTANCE", "HEARTBEAT_AT")),
            "workflow_node_run", Map.of(
                    "idx_wnr_run", List.of("RUN_ID"),
                    "idx_wnr_status", List.of("STATUS"),
                    "idx_wnr_task", List.of("TASK_ID")),
            "workflow_approval", Map.of(
                    "idx_appr_run", List.of("RUN_ID"),
                    "idx_appr_status", List.of("STATUS"),
                    "idx_appr_expires", List.of("EXPIRES_AT")));

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("六张工作流表存在，字段逐列对齐（列名 + 可空性）")
    void migration_createsWorkflowTablesWithDesignedColumns() throws SQLException {
        Map<String, Map<String, Boolean>> actual = readColumns();
        assertThat(actual.keySet()).containsAll(WORKFLOW_TABLES);

        Map<String, Map<String, Boolean>> expected = new LinkedHashMap<>();
        expected.put("workflow", cols(
                entry("id", false), entry("name", false), entry("description", true),
                entry("category", true), entry("status", false), entry("visibility", false),
                entry("source", true), entry("created_by", false), entry("current_version_id", true),
                entry("run_count", false), entry("success_count", false), entry("last_run_at", true),
                entry("created_at", true), entry("updated_at", true)));
        expected.put("workflow_version", cols(
                entry("id", false), entry("workflow_id", false), entry("version", false),
                entry("base_version", false), entry("definition_json", false),
                entry("definition_sha256", false), entry("changelog", true), entry("is_valid", false),
                entry("estimated_risk", true), entry("published_at", true), entry("published_by", true),
                entry("created_at", true), entry("updated_at", true)));
        expected.put("workflow_run", cols(
                entry("id", false), entry("workflow_id", false), entry("version_id", false),
                entry("version_no", false), entry("trigger_type", false), entry("status", false),
                entry("input_json", true), entry("output_json", true), entry("error_message", true),
                entry("variables_json", true), entry("variables_truncated", false),
                entry("current_nodes", true), entry("cancel_requested", false),
                entry("owner_instance", true), entry("heartbeat_at", true),
                entry("idempotency_key", true), entry("risk_snapshot_json", true),
                entry("started_by", true), entry("started_at", true), entry("finished_at", true),
                entry("duration_ms", true), entry("created_at", true), entry("updated_at", true)));
        expected.put("workflow_node_run", cols(
                entry("id", false), entry("run_id", false), entry("node_key", false),
                entry("node_type", false), entry("attempt", false), entry("status", false),
                entry("input_json", true), entry("output_json", true), entry("output_sha256", true),
                entry("preview_sha256", true), entry("raw_params", true), entry("artifact_ids", true),
                entry("error_message", true), entry("warning_message", true),
                entry("agent_versions", true), entry("cancel_delivered", false),
                entry("task_id", true), entry("approval_id", true), entry("started_at", true),
                entry("finished_at", true), entry("duration_ms", true),
                entry("created_at", true), entry("updated_at", true)));
        expected.put("workflow_approval", cols(
                entry("id", false), entry("run_id", false), entry("node_key", false),
                entry("node_type", false), entry("title", false), entry("detail_json", true),
                entry("risk_level", true), entry("risk_matches", true), entry("status", false),
                entry("approver_role", false), entry("decided_by", true), entry("decided_at", true),
                entry("decision_comment", true), entry("expires_at", true), entry("version", false),
                entry("created_at", true), entry("updated_at", true)));
        expected.put("workflow_dispatch_approval_binding", cols(
                entry("approval_id", false), entry("dispatch_node_run_id", false),
                entry("dispatch_attempt", false), entry("dispatch_user_id", false),
                entry("dispatch_script_hmac", false), entry("dispatch_intent_hmac", false),
                entry("dispatch_targets", false), entry("dispatch_timeout_sec", false),
                entry("dispatch_consumed_at", true), entry("dispatch_task_id", true)));

        assertThat(expected.keySet()).containsExactlyInAnyOrderElementsOf(WORKFLOW_TABLES);
        for (Map.Entry<String, Map<String, Boolean>> table : expected.entrySet()) {
            assertThat(actual.get(table.getKey()))
                    .as("表 %s 的列集合与可空性", table.getKey())
                    .containsExactlyInAnyOrderEntriesOf(table.getValue());
        }
    }

    @Test
    @DisplayName("每张工作流表主键列与实体契约一致")
    void migration_createsExpectedPrimaryKeys() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : WORKFLOW_TABLES) {
                List<String> columns = new ArrayList<>();
                try (ResultSet rs = connection.getMetaData().getPrimaryKeys(null, null, table.toUpperCase(Locale.ROOT))) {
                    while (rs.next()) {
                        int position = rs.getShort("KEY_SEQ");
                        while (columns.size() < position) columns.add(null);
                        columns.set(position - 1, rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
                assertThat(columns).as("表 %s 的主键", table)
                        .containsExactly(table.equals("workflow_dispatch_approval_binding") ? "approval_id" : "id");
            }
        }
    }

    @Test
    @DisplayName("唯一键与索引按设计 §5.2 建齐（uk_node_run / uk_wfr_idem / uk_wv_workflow_version + idx_*）")
    void migration_createsUniqueKeysAndIndexes() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            for (String table : WORKFLOW_TABLES) {
                Map<String, List<String>> indexes = readIndexColumns(meta, table);
                // 唯一键按列集合断言：H2 会把 UNIQUE KEY 的支撑索引改名（uk_node_run -> UK_NODE_RUN_INDEX_E），
                // MySQL 保留原名，列集合才是跨方言稳定的语义
                for (Map.Entry<String, List<String>> uniqueKey : EXPECTED_UNIQUE_KEY_COLUMNS
                        .getOrDefault(table, Map.of()).entrySet()) {
                    assertThat(indexes)
                            .as("表 %s 缺少唯一键 %s", table, uniqueKey.getKey())
                            .containsValue(uniqueKey.getValue());
                }
                for (Map.Entry<String, List<String>> index : EXPECTED_INDEX_COLUMNS
                        .getOrDefault(table, Map.of()).entrySet()) {
                    assertThat(indexes)
                            .as("表 %s 缺少索引 %s", table, index.getKey())
                            .containsValue(index.getValue());
                }
            }
        }
    }

    @Test
    @DisplayName("flyway_schema_history 每个 version 都能在 db/migration 找到脚本（F3-③）")
    void schemaHistory_versionsResolveToScriptFiles() throws Exception {
        List<String> appliedVersions = new ArrayList<>();
        for (MigrationInfo info : flyway.info().applied()) {
            appliedVersions.add(info.getVersion().getVersion());
        }
        assertThat(appliedVersions).contains("1.2.0");

        List<String> scriptNames = scriptFileNames();
        assertThat(scriptNames).anyMatch(name -> name.startsWith("V1_2_0__"));
        for (String version : appliedVersions) {
            assertThat(scriptNames)
                    .as("已 applied 的 version %s 必须有对应脚本文件（F3-③：永不删除已 applied 的脚本）", version)
                    .anyMatch(name -> name.startsWith("V" + version.replace('.', '_') + "__"));
        }
    }

    @Test
    @DisplayName("迁移脚本零 ALTER / 零 DROP，且不 CREATE 任何存量表（MF-6 / MF-7）")
    void migrationScripts_neverTouchLegacyTables() throws Exception {
        for (Resource script : migrationScripts()) {
            String sql = stripComments(readScript(script));
            assertThat(sql).as("%s 不得包含 ALTER", script.getFilename()).doesNotContainPattern("(?i)\\balter\\b");
            assertThat(sql).as("%s 不得包含 DROP", script.getFilename()).doesNotContainPattern("(?i)\\bdrop\\b");
            for (String legacy : LEGACY_TABLES) {
                assertThat(sql).as("%s 不得碰存量表 %s", script.getFilename(), legacy)
                        .doesNotContainPattern("(?i)\\b" + legacy + "\\b");
            }
        }
    }

    @Test
    @DisplayName("Flyway validate 通过（validate-on-migrate 的启动即 fail-fast 语义）")
    void flywayValidate_passes() {
        assertThatNoException().isThrownBy(() -> flyway.validate());
    }

    @Test
    @DisplayName("启动一致性校验器在迁移后的库上报告零违规（实体 ↔ DDL 逐字段对齐）")
    void schemaConsistencyChecker_reportsNoViolation() {
        WorkflowSchemaConsistencyChecker checker = new WorkflowSchemaConsistencyChecker(dataSource, true);
        assertThat(checker.verify()).isEmpty();
    }

    // ---------- 脚本解析辅助 ----------

    private static Resource[] migrationScripts() throws IOException {
        return resolver().getResources("classpath:db/migration/*.sql");
    }

    private static List<String> scriptFileNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (Resource script : migrationScripts()) {
            names.add(script.getFilename());
        }
        return names;
    }

    private static ResourcePatternResolver resolver() {
        return new PathMatchingResourcePatternResolver();
    }

    private static String readScript(Resource script) {
        try {
            return new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 去掉 -- 行注释后压成单行，便于正则比对 */
    private static String stripComments(String sql) {
        StringBuilder result = new StringBuilder();
        for (String line : sql.split("\n")) {
            int comment = line.indexOf("--");
            result.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return result.toString();
    }

    // ---------- JDBC 元数据辅助 ----------

    private Map<String, Map<String, Boolean>> readColumns() throws SQLException {
        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            for (String table : WORKFLOW_TABLES) {
                Map<String, Boolean> columns = new LinkedHashMap<>();
                // H2 的元数据匹配大小写敏感（未加引号的标识符存成大写），两种大小写都试一次
                for (String pattern : List.of(table, table.toUpperCase(Locale.ROOT))) {
                    try (ResultSet rs = meta.getColumns(null, null, pattern, null)) {
                        while (rs.next()) {
                            String column = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                            boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                            columns.put(column, nullable);
                        }
                    }
                    if (!columns.isEmpty()) {
                        break;
                    }
                }
                result.put(table, columns);
            }
        }
        return result;
    }

    /** 索引名（大写）-> 列集合（按 ORDINAL_POSITION 排序） */
    private static Map<String, List<String>> readIndexColumns(DatabaseMetaData meta, String table) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (boolean unique : new boolean[]{true, false}) {
            for (String pattern : List.of(table, table.toUpperCase(Locale.ROOT))) {
                boolean found = false;
                try (ResultSet rs = meta.getIndexInfo(null, null, pattern, unique, false)) {
                    while (rs.next()) {
                        String indexName = rs.getString("INDEX_NAME");
                        if (indexName == null) {
                            continue;
                        }
                        List<String> columns = result.computeIfAbsent(
                                indexName.toUpperCase(Locale.ROOT), key -> new ArrayList<>());
                        short ordinal = rs.getShort("ORDINAL_POSITION");
                        while (columns.size() < ordinal) {
                            columns.add(null);
                        }
                        columns.set(ordinal - 1, rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                        found = true;
                    }
                }
                if (found) {
                    break;
                }
            }
        }
        return result;
    }

    // ---------- 期望列构造 ----------

    private static Map<String, Boolean> cols(Map.Entry<String, Boolean>... entries) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map.Entry<String, Boolean> entry(String name, boolean nullable) {
        return Map.entry(name, nullable);
    }
}
