package com.easyshell.server.config;

import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowDispatchApprovalBinding;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工作流 schema 一致性校验（"双主管"对冲的第二半，设计 §5.4 / 复核 C-16）。
 *
 * <p>背景：存量表由 Hibernate ddl-auto 管理，workflow* 表由 Flyway 纳管
 * （{@link WorkflowSchemaFilterProvider} 保证两个主管的管辖范围不相交）。Flyway 只保证
 * "脚本被完整执行过"，不保证"脚本与 JPA 实体还对得上"——实体加一个字段而忘了改迁移脚本，
 * 生产上要到第一次 SQL 报 Unknown column 才暴露。</p>
 *
 * <p>因此启动时（ApplicationRunner，上下文刷新后、对外服务前）把工作流实体与真实 DDL
 * 逐字段比对：列名集合、可空性、VARCHAR 长度、主键、唯一键、索引。任何不一致立即抛异常，
 * 让进程 fail-fast 起不来，而不是带着隐性漂移运行。</p>
 *
 * <p>范围说明（刻意为之）：只校验 Flyway 纳管的工作流 JPA 表；存量表的 schema 一致性继续由
 * ddl-auto 自己保证。SQL 类型名不做逐字比对——MySQL 报 MEDIUMTEXT、H2(MODE=MySQL) 对同一列
 * 报 CHARACTER VARYING，逐字比会产生方言误报；类型级校验留给 M2 的全局 Hibernate validate。</p>
 */
@Slf4j
@Component
public class WorkflowSchemaConsistencyChecker implements ApplicationRunner {

    /** Flyway 纳管的工作流 JPA 实体（顺序即校验顺序） */
    private static final List<Class<?>> WORKFLOW_ENTITIES = List.of(
            Workflow.class, WorkflowVersion.class, WorkflowRun.class,
            WorkflowNodeRun.class, WorkflowApproval.class, WorkflowDispatchApprovalBinding.class);

    private final DataSource dataSource;

    /** 测试可通过开关跳过（例如只跑仓储逻辑、不关心 schema 的切片） */
    private final boolean enabled;

    @Autowired
    public WorkflowSchemaConsistencyChecker(
            DataSource dataSource,
            @Value("${easyshell.workflow.schema-check.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.warn("工作流 schema 一致性校验已被关闭（easyshell.workflow.schema-check.enabled=false），"
                    + "实体与 Flyway DDL 的漂移将不会被发现");
            return;
        }
        List<String> violations = verify();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("工作流实体与 Flyway DDL 不一致，拒绝启动（fail-fast）：\n - "
                    + String.join("\n - ", violations));
        }
        log.info("工作流 schema 一致性校验通过：{} 张表与 JPA 实体逐字段对齐", WORKFLOW_ENTITIES.size());
    }

    /**
     * 执行校验并返回违规清单（空 = 一致）。
     * 独立于 Spring 生命周期，测试可直接调用。
     */
    public List<String> verify() {
        List<String> violations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            for (Class<?> entity : WORKFLOW_ENTITIES) {
                // 逐表查元数据：H2 的 getIndexInfo / getPrimaryKeys 对 "%" 表名模式返回空，
                // 必须传具体表名；MySQL 对两种写法都正常
                String table = tableName(entity);
                Map<String, ColumnMeta> columns = readColumns(meta, table);
                Map<String, IndexMeta> indexes = readIndexes(meta, table);
                List<String> primaryKey = readPrimaryKeys(meta, table);
                checkEntity(entity, columns, indexes, primaryKey, violations);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("工作流 schema 一致性校验失败：无法读取数据库元数据", e);
        }
        return violations;
    }

    private void checkEntity(Class<?> entity,
                             Map<String, ColumnMeta> actualTableColumns,
                             Map<String, IndexMeta> actualIndexes,
                             List<String> actualPkColumns,
                             List<String> violations) {
        String table = tableName(entity);
        if (actualTableColumns.isEmpty()) {
            violations.add(table + "：表不存在（Flyway 迁移未执行？）");
            return;
        }

        Map<String, ExpectedColumn> expected = expectedColumns(entity);
        for (Map.Entry<String, ExpectedColumn> entry : expected.entrySet()) {
            ColumnMeta actual = actualTableColumns.get(entry.getKey().toUpperCase(Locale.ROOT));
            if (actual == null) {
                violations.add(String.format("%s.%s：实体有、DDL 无（迁移脚本漏了该列？）",
                        table, entry.getKey()));
                continue;
            }
            if (entry.getValue().nullable != actual.nullable) {
                violations.add(String.format("%s.%s：可空性不一致（实体 %s / DDL %s）",
                        table, entry.getKey(),
                        entry.getValue().nullable ? "NULL" : "NOT NULL",
                        actual.nullable ? "NULL" : "NOT NULL"));
            }
            if (entry.getValue().length > 0 && actual.size > 0 && entry.getValue().length != actual.size) {
                violations.add(String.format("%s.%s：长度不一致（实体 %d / DDL %d）",
                        table, entry.getKey(), entry.getValue().length, actual.size));
            }
        }
        for (String actualName : actualTableColumns.keySet()) {
            if (!expected.containsKey(actualName.toLowerCase(Locale.ROOT))) {
                violations.add(String.format("%s.%s：DDL 有、实体无（迁移脚本多列或实体漏字段？）",
                        table, actualName));
            }
        }

        List<String> expectedPk = upperCaseColumns(
                primaryKeyColumns(entity).toArray(new String[0]));
        if (actualPkColumns.isEmpty()) {
            violations.add(table + "：DDL 中没有主键");
        } else if (!expectedPk.equals(actualPkColumns)) {
            violations.add(String.format("%s：主键列不一致（实体 %s / DDL %s）",
                    table, expectedPk, actualPkColumns));
        }

        Table tableAnnotation = entity.getAnnotation(Table.class);
        for (UniqueConstraint constraint : tableAnnotation.uniqueConstraints()) {
            List<String> columns = upperCaseColumns(constraint.columnNames());
            IndexMeta index = findIndexByColumns(actualIndexes, columns, true);
            if (index == null) {
                violations.add(String.format("%s：缺少唯一键 %s%s（attempt 抢占/幂等唯一性的兜底）",
                        table, constraint.name(), constraint.columnNames()));
            } else if (!index.unique) {
                violations.add(String.format("%s：%s 存在但不是唯一索引", table, constraint.name()));
            }
        }
        for (Index index : tableAnnotation.indexes()) {
            List<String> columns = upperCaseColumns(index.columnList().split(","));
            if (findIndexByColumns(actualIndexes, columns, false) == null) {
                violations.add(String.format("%s：缺少索引 %s(%s)",
                        table, index.name(), index.columnList()));
            }
        }
    }

    /**
     * 按列集合找索引（而非按名字）：H2 会把 UNIQUE KEY 的支撑索引改名
     * （uk_node_run -> UK_NODE_RUN_INDEX_E），MySQL 则保留原名；列集合才是
     * 跨方言稳定的语义。requiredUnique 为 true 时只接受唯一索引。
     */
    private static IndexMeta findIndexByColumns(Map<String, IndexMeta> indexes,
                                                List<String> expectedColumns,
                                                boolean requiredUnique) {
        for (IndexMeta index : indexes.values()) {
            if (requiredUnique && !index.unique) {
                continue;
            }
            if (upperCaseColumns(index.columns.toArray(new String[0])).equals(expectedColumns)) {
                return index;
            }
        }
        return null;
    }

    private static List<String> upperCaseColumns(String[] columns) {
        List<String> result = new ArrayList<>();
        for (String column : columns) {
            result.add(column.trim().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private static String tableName(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        if (table == null || table.name().isEmpty()) {
            throw new IllegalStateException("工作流实体缺少 @Table 声明：" + entity.getName());
        }
        return table.name();
    }

    private static List<String> primaryKeyColumns(Class<?> entity) {
        List<String> columns = new ArrayList<>();
        for (Field field : collectFields(entity)) {
            if (field.getAnnotation(Id.class) != null) {
                columns.add(columnName(field));
            }
        }
        return columns;
    }

    private static Map<String, ExpectedColumn> expectedColumns(Class<?> entity) {
        Map<String, ExpectedColumn> columns = new LinkedHashMap<>();
        for (Field field : collectFields(entity)) {
            if (Modifier.isStatic(field.getModifiers()) || field.getAnnotation(Transient.class) != null) {
                continue;
            }
            Id id = field.getAnnotation(Id.class);
            Column column = field.getAnnotation(Column.class);
            if (id == null && column == null) {
                continue;
            }
            String name = columnName(field);
            boolean nullable = id == null && (column == null || column.nullable());
            boolean lob = field.getAnnotation(Lob.class) != null;
            // JPA 的 @Column.length 默认 255，只对 String 列有意义；非 String 列（时间/数值/布尔）不比长度
            int length = field.getType() == String.class && !lob
                    ? (column != null ? column.length() : 255)
                    : 0;
            columns.put(name, new ExpectedColumn(name, nullable, length));
        }
        return columns;
    }

    private static String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isEmpty()) {
            return column.name();
        }
        return camelToUnderscores(field.getName());
    }

    /** 与 Spring Boot 默认物理命名策略（CamelCaseToUnderscoresNamingStrategy）同口径 */
    private static String camelToUnderscores(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static List<Field> collectFields(Class<?> entity) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = entity;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Map<String, ColumnMeta> readColumns(DatabaseMetaData meta, String table) throws SQLException {
        Map<String, ColumnMeta> result = new LinkedHashMap<>();
        for (String pattern : tableNamePatterns(table)) {
            try (ResultSet rs = meta.getColumns(null, null, pattern, null)) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT);
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    int size = rs.getInt("COLUMN_SIZE");
                    result.put(column, new ColumnMeta(column, nullable, size));
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return result;
    }

    private static Map<String, IndexMeta> readIndexes(DatabaseMetaData meta, String table) throws SQLException {
        Map<String, IndexMeta> result = new LinkedHashMap<>();
        // unique 传 true/false 各拉一次再合并：个别驱动在 false 时只返回非唯一索引
        for (boolean unique : new boolean[]{true, false}) {
            for (String pattern : tableNamePatterns(table)) {
                boolean found = false;
                try (ResultSet rs = meta.getIndexInfo(null, null, pattern, unique, false)) {
                    while (rs.next()) {
                        String indexName = rs.getString("INDEX_NAME");
                        if (indexName == null) {
                            continue;
                        }
                        boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                        short ordinal = rs.getShort("ORDINAL_POSITION");
                        String column = rs.getString("COLUMN_NAME");
                        IndexMeta index = result.computeIfAbsent(
                                indexName.toUpperCase(Locale.ROOT), key -> new IndexMeta());
                        index.unique = !nonUnique;
                        while (index.columns.size() < ordinal) {
                            index.columns.add(null);
                        }
                        index.columns.set(ordinal - 1, column);
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

    private static List<String> readPrimaryKeys(DatabaseMetaData meta, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (String pattern : tableNamePatterns(table)) {
            try (ResultSet rs = meta.getPrimaryKeys(null, null, pattern)) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT);
                    short seq = rs.getShort("KEY_SEQ");
                    while (columns.size() < seq) {
                        columns.add(null);
                    }
                    columns.set(seq - 1, column);
                }
            }
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        return columns;
    }

    /**
     * 表名模式按两种大小写各试一次：H2 把未加引号的标识符存成大写且元数据匹配大小写敏感，
     * MySQL 的 information_schema LIKE 匹配大小写不敏感——两种写法各库都能命中。
     */
    private static List<String> tableNamePatterns(String table) {
        return List.of(table, table.toUpperCase(Locale.ROOT));
    }

    private record ExpectedColumn(String name, boolean nullable, int length) {
    }

    private static final class ColumnMeta {
        private final String name;
        private final boolean nullable;
        private final int size;

        private ColumnMeta(String name, boolean nullable, int size) {
            this.name = name;
            this.nullable = nullable;
            this.size = size;
        }
    }

    private static final class IndexMeta {
        private boolean unique;
        /** 按 ORDINAL_POSITION 排列的列名（null 占位表示驱动没给全） */
        private final List<String> columns = new ArrayList<>();
    }
}
