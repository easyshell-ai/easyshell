package com.easyshell.server.workflow;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.AttemptAllocator;
import com.easyshell.server.workflow.engine.DagScheduler;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeExecutorRegistry;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.engine.WorkflowEvent;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.node.ConditionNodeExecutor;
import com.easyshell.server.workflow.node.StartNodeExecutor;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;

/**
 * Branch safety at the real scheduler/gate and committed H2 node-run boundary.
 * WAIT is a side-effect probe, never a real wait or command. The classifier stub
 * injects malformed successful outputs to exercise the engine's defensive gate;
 * the real classifier normally rejects these before producing an output.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_branch_safety;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=warn"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(45)
@DisplayName("工作流分支安全回归（真实引擎 + H2，无真实命令）")
class WorkflowBranchSafetyRegressionTest {

    @Autowired
    private WorkflowRepository workflowRepository;
    @Autowired
    private WorkflowVersionRepository versionRepository;
    @Autowired
    private WorkflowRunRepository runRepository;
    @Autowired
    private WorkflowNodeRunRepository nodeRunRepository;
    @Autowired
    private WorkflowApprovalRepository approvalRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<String> sideEffects = new CopyOnWriteArrayList<>();
    private final List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger classifierCalls = new AtomicInteger();

    private ObjectMapper objectMapper;
    private DagValidator dagValidator;
    private ExecutorService enginePool;
    private WorkflowEngine engine;
    private volatile String failingProbe;
    private volatile Map<String, Object> classifierOutput;

    private static class StubExecutor implements NodeExecutor {
        private final NodeType type;
        private final Function<NodeContext, NodeOutcome> behavior;

        private StubExecutor(NodeType type, Function<NodeContext, NodeOutcome> behavior) {
            this.type = type;
            this.behavior = behavior;
        }

        @Override
        public NodeType supports() {
            return type;
        }

        @Override
        public NodeOutcome execute(NodeContext context) {
            return behavior.apply(context);
        }
    }

    @BeforeEach
    void setUp() {
        sideEffects.clear();
        events.clear();
        classifierCalls.set(0);
        failingProbe = null;
        classifierOutput = null;

        objectMapper = new ObjectMapper();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(objectMapper);
        dagValidator = new DagValidator(evaluator);
        WorkflowProperties properties = new WorkflowProperties();
        properties.getEngine().setMaxConcurrentNodes(3);
        properties.getEngine().setDefaultNodeTimeoutSec(10);
        enginePool = Executors.newFixedThreadPool(8);

        NodeExecutor probe = new StubExecutor(NodeType.WAIT, ctx -> {
            String key = ctx.node().getKey();
            sideEffects.add(key);
            if (key.equals(failingProbe)) {
                throw new NodeFailedException("SIMULATED_FAILURE", "probe failed");
            }
            return NodeOutcome.SUCCEEDED;
        });
        NodeExecutor classifier = new StubExecutor(NodeType.CLASSIFIER, ctx -> {
            classifierCalls.incrementAndGet();
            Map<String, Object> output = classifierOutput;
            if (output != null) {
                ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(), output);
            }
            return NodeOutcome.SUCCEEDED;
        });
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(), new ConditionNodeExecutor(evaluator), probe, classifier));
        engine = new WorkflowEngine(runRepository, nodeRunRepository, approvalRepository,
                versionRepository, workflowRepository, jobRepository, registry, dagValidator,
                evaluator, new AttemptAllocator(nodeRunRepository, runRepository, transactionManager),
                mock(AuditLogService.class), new SensitiveDataFilter(), Optional.of(events::add),
                enginePool, mock(TaskScheduler.class), properties, objectMapper, transactionManager);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        enginePool.shutdownNow();
        enginePool.awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Both arms have three probes before joining; true and false must be symmetric. */
    @ParameterizedTest(name = "condition={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("未选分支多跳持续 skipped；选中分支与 join 各执行一次")
    void skippedBranchStaysSkippedThroughMultipleHopsAndSelectedBranchReachesJoin(boolean choice)
            throws Exception {
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "variables":{"choice":{"type":"boolean","required":true}},
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"gate","type":"condition","config":{"expression":"run.input.choice"}},
                   {"key":"true_1","type":"wait","config":{"durationSec":1}},
                   {"key":"true_2","type":"wait","config":{"durationSec":1}},
                   {"key":"true_3","type":"wait","config":{"durationSec":1}},
                   {"key":"false_1","type":"wait","config":{"durationSec":1}},
                   {"key":"false_2","type":"wait","config":{"durationSec":1}},
                   {"key":"false_3","type":"wait","config":{"durationSec":1}},
                   {"key":"join","type":"wait","config":{"durationSec":1}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"gate"},
                   {"id":"e2","from":"gate","to":"true_1","sourceHandle":"true"},
                   {"id":"e3","from":"true_1","to":"true_2"},
                   {"id":"e4","from":"true_2","to":"true_3"},
                   {"id":"e5","from":"true_3","to":"join"},
                   {"id":"e6","from":"gate","to":"false_1","sourceHandle":"false"},
                   {"id":"e7","from":"false_1","to":"false_2"},
                   {"id":"e8","from":"false_2","to":"false_3"},
                   {"id":"e9","from":"false_3","to":"join"}]}
                """;
        Result result = drive(definition, Map.of("choice", choice));
        String selected = choice ? "true" : "false";
        String rejected = choice ? "false" : "true";

        assertSoftly(soft -> {
            soft.assertThat(result.run().getStatus()).as("run").isEqualTo("succeeded");
            soft.assertThat(result.status("begin")).as("start").isEqualTo("succeeded");
            soft.assertThat(result.status("gate")).as("condition").isEqualTo("succeeded");
            soft.assertThat(result.node("gate").getOutputJson()).as("condition output")
                    .contains("\"result\":" + choice);
            for (int hop = 1; hop <= 3; hop++) {
                soft.assertThat(result.status(selected + "_" + hop))
                        .as("selected hop %s", hop).isEqualTo("succeeded");
                soft.assertThat(result.status(rejected + "_" + hop))
                        .as("unselected hop %s", hop).isEqualTo("skipped");
            }
            soft.assertThat(result.status("join")).as("join").isEqualTo("succeeded");
            soft.assertThat(sideEffects).as("only selected probes and join may execute")
                    .containsExactlyInAnyOrder(selected + "_1", selected + "_2", selected + "_3", "join");
        });
    }

    @Test
    @DisplayName("condition 引用缺失时，IF 和 ELSE 都不能触发副作用")
    void unevaluableConditionMustNotSelectElse() throws Exception {
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "variables":{"threshold":{"type":"number","required":false}},
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"gate","type":"condition","config":{"expression":"run.input.threshold > 0"}},
                   {"key":"if_action","type":"wait","config":{"durationSec":1}},
                   {"key":"else_action","type":"wait","config":{"durationSec":1}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"gate"},
                   {"id":"e2","from":"gate","to":"if_action","sourceHandle":"true"},
                   {"id":"e3","from":"gate","to":"else_action","sourceHandle":"false"}]}
                """;
        // threshold is declared optional, but omitted: the expression is syntactically valid
        // and reaches the real executor, where resolving the input must fail.
        Result result = drive(definition, Map.of());

        assertSoftly(soft -> {
            soft.assertThat(result.status("begin")).isEqualTo("succeeded");
            soft.assertThat(result.run().getStatus()).as("evaluation error fails the run").isEqualTo("failed");
            soft.assertThat(result.status("gate")).as("evaluation error fails the condition")
                    .isEqualTo("failed");
            soft.assertThat(events).as("condition was actually attempted")
                    .anySatisfy(event -> {
                        assertThat(event.type()).isEqualTo("NODE_STARTED");
                        assertThat(event.nodeKey()).isEqualTo("gate");
                    });
            soft.assertThat(result.status("if_action")).as("IF").isIn("skipped", "cancelled");
            soft.assertThat(result.status("else_action")).as("ELSE").isIn("skipped", "cancelled");
            soft.assertThat(sideEffects).as("neither handle is a valid result of a failed evaluation")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("partial 允许失败上游后继续，但不能复活 condition 未选分支")
    void partialFailurePolicyDoesNotReviveUnselectedBranch() throws Exception {
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"partial",
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"failing","type":"wait","config":{"durationSec":1}},
                   {"key":"gate","type":"condition","config":{"expression":"true"}},
                   {"key":"selected","type":"wait","config":{"durationSec":1}},
                   {"key":"no_1","type":"wait","config":{"durationSec":1}},
                   {"key":"no_2","type":"wait","config":{"durationSec":1}},
                   {"key":"no_3","type":"wait","config":{"durationSec":1}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"failing"},
                   {"id":"e2","from":"failing","to":"gate"},
                   {"id":"e3","from":"gate","to":"selected","sourceHandle":"true"},
                   {"id":"e4","from":"gate","to":"no_1","sourceHandle":"false"},
                   {"id":"e5","from":"no_1","to":"no_2"},
                   {"id":"e6","from":"no_2","to":"no_3"}]}
                """;
        failingProbe = "failing";
        Result result = drive(definition, Map.of());

        assertSoftly(soft -> {
            soft.assertThat(result.run().getStatus()).as("run retains upstream failure").isEqualTo("failed");
            soft.assertThat(result.status("failing")).as("injected failure").isEqualTo("failed");
            soft.assertThat(result.status("gate")).as("partial actually continues past the failure")
                    .isEqualTo("succeeded");
            soft.assertThat(result.node("gate").getOutputJson()).contains("\"result\":true");
            soft.assertThat(result.status("selected")).as("selected branch").isEqualTo("succeeded");
            for (int hop = 1; hop <= 3; hop++) {
                soft.assertThat(result.status("no_" + hop)).as("unselected hop %s", hop)
                        .isEqualTo("skipped");
            }
            soft.assertThat(sideEffects).as("partial must not execute the unselected path")
                    .containsExactlyInAnyOrder("failing", "selected");
        });
    }

    static Stream<Arguments> invalidClassifierOutputs() {
        return Stream.of(
                Arguments.of("whole output absent", null),
                Arguments.of("class missing", Map.of("reason", "no class")),
                Arguments.of("class null", Collections.singletonMap("class", null)),
                Arguments.of("unknown class", Map.of("class", "storage")),
                Arguments.of("non-string class", Map.of("class", 42)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidClassifierOutputs")
    @DisplayName("classifier 输出缺失或不合法时所有出口 fail-closed，不能走 default")
    void invalidClassifierOutputMustNotReachDefault(String scenario, Map<String, Object> output)
            throws Exception {
        classifierOutput = output;
        Result result = drive(classifierDefinition(), Map.of());

        assertSoftly(soft -> {
            soft.assertThat(classifierCalls.get()).as("injected classifier was called: %s", scenario)
                    .isEqualTo(1);
            // A future engine may reject the invalid output immediately, or let the gate reject it.
            soft.assertThat(result.status("classify")).isIn("succeeded", "failed");
            if ("succeeded".equals(result.status("classify"))) {
                if (output == null) {
                    soft.assertThat(result.node("classify").getOutputJson()).isNull();
                } else {
                    try {
                        soft.assertThat(result.node("classify").getOutputJson())
                                .as("injected output persisted").isEqualTo(objectMapper.writeValueAsString(output));
                    } catch (Exception e) {
                        throw new AssertionError("cannot serialize test output", e);
                    }
                }
            }
            soft.assertThat(result.status("disk")).as("disk branch").isIn("skipped", "cancelled");
            soft.assertThat(result.status("network")).as("network branch").isIn("skipped", "cancelled");
            soft.assertThat(result.status("fallback")).as("default branch").isIn("skipped", "cancelled");
            soft.assertThat(sideEffects).as("invalid class must not trigger any branch").isEmpty();
        });
    }

    @Test
    @DisplayName("classifier 合法类别仍能精确命中，不因 fail-closed 全部阻断")
    void validClassifierOutputStillSelectsItsBranch() throws Exception {
        classifierOutput = Map.of("class", "disk");
        Result result = drive(classifierDefinition(), Map.of());

        assertSoftly(soft -> {
            soft.assertThat(classifierCalls.get()).isEqualTo(1);
            soft.assertThat(result.run().getStatus()).isEqualTo("succeeded");
            soft.assertThat(result.status("classify")).isEqualTo("succeeded");
            soft.assertThat(result.node("classify").getOutputJson()).contains("\"class\":\"disk\"");
            soft.assertThat(result.status("disk")).isEqualTo("succeeded");
            soft.assertThat(result.status("network")).isEqualTo("skipped");
            soft.assertThat(result.status("fallback")).isEqualTo("skipped");
            soft.assertThat(sideEffects).containsExactly("disk");
        });
    }

    private String classifierDefinition() {
        return """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"classify","type":"classifier","config":{"input":"test input",
                     "classes":[{"name":"disk"},{"name":"network"}]}},
                   {"key":"disk","type":"wait","config":{"durationSec":1}},
                   {"key":"network","type":"wait","config":{"durationSec":1}},
                   {"key":"fallback","type":"wait","config":{"durationSec":1}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"classify"},
                   {"id":"e2","from":"classify","to":"disk","sourceHandle":"disk"},
                   {"id":"e3","from":"classify","to":"network","sourceHandle":"network"},
                   {"id":"e4","from":"classify","to":"fallback","sourceHandle":"default"}]}
                """;
    }

    /** Save a validated definition and a running row, then synchronously drive real committed attempts. */
    private Result drive(String definitionJson, Map<String, Object> input) throws Exception {
        WorkflowDefinition definition = objectMapper.readValue(definitionJson, WorkflowDefinition.class);
        assertThat(dagValidator.validate(definition))
                .filteredOn(issue -> issue.severity() == ValidationIssue.Severity.ERROR)
                .as("test definition must pass publish-time graph validation").isEmpty();

        Workflow workflow = new Workflow();
        workflow.setName("branch-safety-regression");
        workflow.setCreatedBy(42L);
        workflow = workflowRepository.saveAndFlush(workflow);

        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowId(workflow.getId());
        version.setVersion(1);
        version.setDefinitionJson(definitionJson);
        version.setDefinitionSha256("a".repeat(64));
        version.setIsValid(true);
        version.setEstimatedRisk("LOW");
        version = versionRepository.saveAndFlush(version);

        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_branch_" + UUID.randomUUID().toString().replace("-", ""));
        run.setWorkflowId(workflow.getId());
        run.setVersionId(version.getId());
        run.setVersionNo(1);
        run.setTriggerType("manual");
        run.setStatus("running");
        run.setStartedBy(42L);
        run.setStartedAt(LocalDateTime.now());
        run.setInputJson(objectMapper.writeValueAsString(input));
        runRepository.saveAndFlush(run);

        engine.drive(run);
        WorkflowRun persisted = runRepository.findById(run.getId()).orElseThrow();
        assertThat(persisted.getStatus()).as("drive must finish: %s", run.getId())
                .isIn("succeeded", "failed", "cancelled", "timeout");
        return new Result(persisted, DagScheduler.latestByKey(nodeRunRepository.findByRunId(run.getId())));
    }

    private record Result(WorkflowRun run, Map<String, WorkflowNodeRun> nodes) {
        WorkflowNodeRun node(String key) {
            WorkflowNodeRun row = nodes.get(key);
            assertThat(row).as("persisted node %s", key).isNotNull();
            return row;
        }

        String status(String key) {
            WorkflowNodeRun row = nodes.get(key);
            return row == null ? null : row.getStatus();
        }
    }
}
