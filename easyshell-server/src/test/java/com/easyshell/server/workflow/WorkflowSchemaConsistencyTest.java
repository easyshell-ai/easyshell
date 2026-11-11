package com.easyshell.server.workflow;

import com.easyshell.server.config.WorkflowSchemaConsistencyChecker;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体 ↔ Flyway DDL 的逐字段一致性（设计 §5.4 / 复核 C-16 的 fail-fast 校验）。
 *
 * <p>正例：真实迁移后的 H2 库上，六个工作流实体与 DDL 零差异；上下文能正常起来、
 * 五个仓储可读可写（证明 Flyway 建出的表就是引擎要用的表）。</p>
 *
 * <p>负例（fail-fast 的证据）：迁移后人为制造四类漂移——DROP 列、DROP 唯一键、
 * 改可空性、整表缺失——校验器必须逐条报出，任何一类都不许静默通过。
 * 这正是"实体加了字段忘了改迁移脚本"这类事故要在启动时被拦住的原因。</p>
 *
 * <p>为什么不用 Hibernate 自带的 {@code ddl-auto=validate}：H2 驱动把 MEDIUMTEXT 报成
 * VARCHAR，而 MySQLDialect 对 @Lob String 期望 CLOB，属方言伪差异；本校验器比对
 * 列名/可空性/长度/主键/唯一键/索引，跨方言稳定且覆盖更全。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=warn"})
@DisplayName("工作流 schema 一致性 — 实体与 Flyway DDL 逐字段对齐（fail-fast）")
class WorkflowSchemaConsistencyTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository workflowVersionRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private WorkflowNodeRunRepository workflowNodeRunRepository;

    @Autowired
    private WorkflowApprovalRepository workflowApprovalRepository;

    @Test
    @DisplayName("校验器清单覆盖全部工作流 JPA 实体")
    void checkerEntityListMatchesWorkflowJpaEntities() throws Exception {
        var field = WorkflowSchemaConsistencyChecker.class.getDeclaredField("WORKFLOW_ENTITIES");
        field.setAccessible(true);
        Set<?> checked = Set.copyOf((List<?>) field.get(null));
        Set<Class<?>> mapped = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(entity -> entity.getJavaType())
                .filter(type -> type.isAnnotationPresent(Table.class)
                        && type.getAnnotation(Table.class).name().startsWith("workflow"))
                .collect(Collectors.toSet());
        assertThat(checked).isEqualTo(mapped);
        assertThat(mapped).hasSize(6);
    }

    @Test
    @DisplayName("正例：迁移后的库上六个实体与 DDL 零差异，五个仓储可读可写")
    void migratedSchema_matchesEntitiesAndRepositoriesWork() {
        assertThat(flyway.info().applied()).isNotEmpty();

        WorkflowSchemaConsistencyChecker checker =
                new WorkflowSchemaConsistencyChecker(dataSource, true);
        assertThat(checker.verify()).isEmpty();

        Workflow workflow = new Workflow();
        workflow.setName("consistency-gate");
        workflow.setCreatedBy(1L);
        Workflow saved = workflowRepository.saveAndFlush(workflow);
        assertThat(workflowRepository.findById(saved.getId())).isPresent();

        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowId(saved.getId());
        version.setVersion(1);
        version.setDefinitionJson("{\"nodes\":[]}");
        version.setDefinitionSha256("a".repeat(64));
        WorkflowVersion savedVersion = workflowVersionRepository.saveAndFlush(version);
        assertThat(workflowVersionRepository.findDefinition(savedVersion.getId()))
                .contains("{\"nodes\":[]}");
        assertThat(workflowVersionRepository.findMaxVersion(saved.getId())).isEqualTo(1);

        WorkflowRun run = new WorkflowRun();
        run.setId("wfr-consistency-1");
        run.setWorkflowId(saved.getId());
        run.setVersionId(savedVersion.getId());
        run.setVersionNo(1);
        run.setStartedBy(1L);
        WorkflowRun savedRun = workflowRunRepository.saveAndFlush(run);
        assertThat(workflowRunRepository.findById(savedRun.getId())).isPresent();

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr-consistency-1");
        nodeRun.setRunId(savedRun.getId());
        nodeRun.setNodeKey("step");
        nodeRun.setNodeType("script-exec");
        WorkflowNodeRun savedNodeRun = workflowNodeRunRepository.saveAndFlush(nodeRun);
        assertThat(workflowNodeRunRepository.findByRunId(savedRun.getId())).hasSize(1);

        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId(savedRun.getId());
        approval.setNodeKey("gate");
        approval.setNodeType("approval");
        approval.setTitle("consistency gate");
        approval.setExpiresAt(LocalDateTime.now().plusHours(1));
        WorkflowApproval savedApproval = workflowApprovalRepository.saveAndFlush(approval);
        assertThat(workflowApprovalRepository.findById(savedApproval.getId())).isPresent();
    }

    @Test
    @DisplayName("负例：迁移后 DROP 一列，校验器 fail-fast 报出“实体有、DDL 无”")
    void failsFastWhenColumnDropped() throws Exception {
        String url = freshMigratedSchema("drift-column");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("alter table workflow drop column category");
        }

        List<String> violations = verifyOn(url);
        assertThat(violations)
                .as("category 被 DROP 后必须报出“实体有、DDL 无”，且不得静默通过")
                .anyMatch(violation -> violation.contains("workflow.category")
                        && violation.contains("实体有、DDL 无"));
        // 除该列及其索引 idx_wf_category 外不得有其它误报
        assertThat(violations).allMatch(violation -> violation.contains("category"));
    }

    @Test
    @DisplayName("负例：唯一键被删，校验器 fail-fast 报出缺失（uk_node_run 是抢占竞态的兜底）")
    void failsFastWhenUniqueKeyMissing() throws Exception {
        String url = freshMigratedSchema("drift-uk");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("alter table workflow_node_run drop constraint uk_node_run");
        }

        assertThat(verifyOn(url))
                .anyMatch(violation -> violation.contains("uk_node_run") && violation.contains("唯一键"));
    }

    @Test
    @DisplayName("负例：可空性漂移，校验器 fail-fast 报出")
    void failsFastWhenNullabilityDrifts() throws Exception {
        String url = freshMigratedSchema("drift-null");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("alter table workflow alter column name set null");
        }

        assertThat(verifyOn(url))
                .anyMatch(violation -> violation.contains("workflow.name") && violation.contains("可空性"));
    }

    @Test
    @DisplayName("负例：整表缺失，校验器报“表不存在”而不是静默通过")
    void failsFastWhenTableMissing() throws Exception {
        String url = freshMigratedSchema("drift-table");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("drop table workflow_approval");
        }

        assertThat(verifyOn(url))
                .anyMatch(violation -> violation.contains("workflow_approval") && violation.contains("表不存在"));
    }

    @Test
    @DisplayName("绑定表字段漂移和主键漂移均被检测")
    void failsFastWhenBindingSchemaDrifts() throws Exception {
        String url = freshMigratedSchema("drift-binding");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("alter table workflow_dispatch_approval_binding drop column dispatch_task_id");
            statement.execute("alter table workflow_dispatch_approval_binding drop primary key");
        }
        assertThat(verifyOn(url))
                .anyMatch(v -> v.contains("workflow_dispatch_approval_binding.dispatch_task_id") && v.contains("实体有、DDL 无"))
                .anyMatch(v -> v.contains("workflow_dispatch_approval_binding") && v.contains("没有主键"));
    }

    @Test
    @DisplayName("绑定表缺失不能被静默忽略")
    void failsFastWhenBindingTableMissing() throws Exception {
        String url = freshMigratedSchema("drift-binding-table");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("drop table workflow_dispatch_approval_binding");
        }
        assertThat(verifyOn(url))
                .anyMatch(v -> v.contains("workflow_dispatch_approval_binding") && v.contains("表不存在"));
    }

    @Test
    @DisplayName("正例：另起一个干净库迁移后校验同样零违规（排除共享库的偶然性）")
    void cleanFreshSchema_passesCheck() throws Exception {
        String url = freshMigratedSchema("clean");
        assertThat(verifyOn(url)).isEmpty();
    }

    // ---------- 辅助 ----------

    /** 起一个独占的内存库并跑真实迁移脚本（与生产同一份 DDL） */
    private static String freshMigratedSchema(String dbName) {
        String url = "jdbc:h2:mem:wf-" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        return url;
    }

    private static List<String> verifyOn(String url) {
        WorkflowSchemaConsistencyChecker checker = new WorkflowSchemaConsistencyChecker(
                new SingleConnectionDataSource(url, "sa", "", true), true);
        return checker.verify();
    }
}
