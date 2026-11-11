package com.easyshell.server.config;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;
import java.util.Locale;
import java.util.Set;

/**
 * "双主管"对冲（MF-6 / 复核官结论）：存量 22 张表仍由 Hibernate 的 ddl-auto 管理，
 * workflow* 与指定 JDBC 表只归 Flyway 纳管——两个 schema 主管的管辖范围必须不相交。
 *
 * <p>做法：create / drop / migrate 三个动作只放行存量表（Hibernate 侧），
 * 让 Hibernate 永远不建、不改、不删工作流表；validate 动作反过来只放行工作流表，
 * 使得一旦把 ddl-auto 切成 validate，Hibernate 校验的是 Flyway 纳管的工作流 JPA 表
 * （存量表留待 M2 全量 baseline 后再纳入全局 validate）。</p>
 *
 * <p>配置方式：{@code spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider}，
 * 由 application.yml 与 application-test.yml 同时声明——测试切片（@DataJpaTest）不扫描
 * @Configuration，只有 YAML 属性能保证 Flyway 与 Hibernate 在测试里同样不相交。</p>
 */
public class WorkflowSchemaFilterProvider implements SchemaFilterProvider {

    /** 工作流表及 JDBC 表均由 Flyway 管辖。 */
    private static final String WORKFLOW_TABLE_PREFIX = "workflow";
    private static final Set<String> FLYWAY_JDBC_TABLES = Set.of(
            "host_identity", "host_acl_state", "host_acl_grant", "agent_enrollment_token",
            "agent_bound_key", "agent_key_challenge", "user_refresh_generation");
    private static final String TASK_TARGET_SNAPSHOT_PREFIX = "task_target_snapshot";

    private static boolean isWorkflowTable(String name) {
        return name.toLowerCase(Locale.ROOT).startsWith(WORKFLOW_TABLE_PREFIX);
    }

    private static boolean isFlywayTable(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return isWorkflowTable(normalized) || FLYWAY_JDBC_TABLES.contains(normalized)
                || normalized.startsWith(TASK_TARGET_SNAPSHOT_PREFIX);
    }

    /** Hibernate 侧（存量表）：建、删、改都只归它 */
    private static final SchemaFilter LEGACY_ONLY = new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) {
            return true;
        }

        @Override
        public boolean includeTable(Table table) {
            return table.getName() != null && !isFlywayTable(table.getName());
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
            return true;
        }
    };

    /** Flyway 侧（工作流新表）：validate 只校验它 */
    private static final SchemaFilter WORKFLOW_ONLY = new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) {
            return true;
        }

        @Override
        public boolean includeTable(Table table) {
            return table.getName() != null && isWorkflowTable(table.getName());
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
            return true;
        }
    };

    @Override
    public SchemaFilter getCreateFilter() {
        return LEGACY_ONLY;
    }

    @Override
    public SchemaFilter getDropFilter() {
        return LEGACY_ONLY;
    }

    @Override
    public SchemaFilter getMigrateFilter() {
        return LEGACY_ONLY;
    }

    @Override
    public SchemaFilter getValidateFilter() {
        return WORKFLOW_ONLY;
    }

    @Override
    public SchemaFilter getTruncatorFilter() {
        return SchemaFilter.ALL;
    }
}
