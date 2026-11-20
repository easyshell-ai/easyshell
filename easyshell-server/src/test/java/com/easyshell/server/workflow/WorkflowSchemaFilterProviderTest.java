package com.easyshell.server.workflow;

import com.easyshell.server.config.WorkflowSchemaFilterProvider;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowSchemaFilterProviderTest {
    private Table table(String name) {
        Table table = new Table();
        table.setName(name);
        return table;
    }
    @Test
    void flywayTablesAreExcludedFromHibernateDdl() {
        WorkflowSchemaFilterProvider provider = new WorkflowSchemaFilterProvider();
        for (SchemaFilter filter : new SchemaFilter[]{provider.getCreateFilter(), provider.getDropFilter(),
                provider.getMigrateFilter()}) {
            for (String name : new String[]{"workflow", "WORKFLOW_DISPATCH_APPROVAL_BINDING",
                    "host_identity", "HOST_ACL_STATE", "host_acl_grant", "agent_enrollment_token", "task_target_snapshot",
                    "agent_bound_key", "AGENT_KEY_CHALLENGE",
                    "task_target_snapshot_item", "TASK_TARGET_SNAPSHOT_FUTURE"}) {
                assertThat(filter.includeTable(table(name))).as(name).isFalse();
            }
            assertThat(filter.includeTable(table("task"))).isTrue();
            assertThat(filter.includeTable(table("host_credential"))).isTrue();
        }
    }

    @Test
    void validateIncludesWorkflowButNotJdbcOrLegacyTables() {
        SchemaFilter filter = new WorkflowSchemaFilterProvider().getValidateFilter();
        assertThat(filter.includeTable(table("workflow_dispatch_approval_binding"))).isTrue();
        for (String name : new String[]{"host_identity", "host_acl_state", "host_acl_grant",
                "task_target_snapshot", "task_target_snapshot_item", "task"}) {
            assertThat(filter.includeTable(table(name))).as(name).isFalse();
        }
    }
}
