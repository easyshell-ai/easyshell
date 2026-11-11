package com.easyshell.server.workflow;

import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.model.entity.*;
import com.easyshell.server.repository.*;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.*;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.node.*;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real engine, strict LLM executor and H2; ChatModel and WAIT side effects are deterministic stubs.
 * No provider, host ACL, script executor, agent or external command is exercised. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:structured_branch;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL", "spring.jpa.show-sql=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(45)
class WorkflowStructuredBranchH2Test {
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowVersionRepository versions;
    @Autowired WorkflowRunRepository runs;
    @Autowired WorkflowNodeRunRepository nodeRuns;
    @Autowired WorkflowApprovalRepository approvals;
    @Autowired JobRepository jobs;
    @Autowired PlatformTransactionManager tx;

    final ObjectMapper mapper = new ObjectMapper();
    final List<String> effects = new CopyOnWriteArrayList<>();
    ExecutorService enginePool, aiPool;
    ChatModel model;
    WorkflowEngine engine;
    DagValidator validator;

    @BeforeEach void setup() {
        var evaluator = new ExpressionEvaluator(mapper);
        validator = new DagValidator(evaluator);
        var properties = new WorkflowProperties();
        properties.getEngine().setMaxConcurrentNodes(3);
        properties.getEngine().setDefaultNodeTimeoutSec(10);
        enginePool = Executors.newFixedThreadPool(6);
        aiPool = Executors.newFixedThreadPool(2);
        var factory = mock(ChatModelFactory.class);
        model = mock(ChatModel.class);
        when(factory.getChatModel(nullable(String.class), nullable(String.class), nullable(Double.class))).thenReturn(model);
        NodeExecutor probe = new NodeExecutor() {
            public NodeType supports() { return NodeType.WAIT; }
            public NodeOutcome execute(NodeContext ctx) {
                effects.add(ctx.node().getKey());
                return NodeOutcome.SUCCEEDED;
            }
        };
        var registry = new NodeExecutorRegistry(List.of(new StartNodeExecutor(),
                new LlmNodeExecutor(factory, new SensitiveDataFilter(), mock(AiQuotaService.class), evaluator, aiPool, properties, mapper),
                new ConditionNodeExecutor(evaluator), probe, new EndNodeExecutor(evaluator)));
        engine = new WorkflowEngine(runs, nodeRuns, approvals, versions, workflows, jobs, registry, validator,
                evaluator, new AttemptAllocator(nodeRuns, runs, tx), mock(AuditLogService.class), new SensitiveDataFilter(),
                Optional.empty(), enginePool, mock(TaskScheduler.class), properties, mapper, tx);
    }

    @AfterEach void cleanup() {
        enginePool.shutdownNow();
        aiPool.shutdownNow();
    }

    private String definition(String policy) {
        return """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"%s",
                 "nodes":[
                   {"key":"start","type":"start","config":{}},
                   {"key":"review","type":"llm","onFailure":"abort","retryMax":0,"config":{
                     "userPrompt":"Review observation","outputMode":"json_schema_v1",
                     "jsonSchema":"{\\"type\\":\\"object\\",\\"properties\\":{\\"needs_attention\\":{\\"type\\":\\"boolean\\"},\\"summary\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"needs_attention\\",\\"summary\\"],\\"additionalProperties\\":false}"}},
                   {"key":"attention","type":"condition","config":{"expression":"nodes.review.output.json.needs_attention == true"}},
                   {"key":"yes1","type":"wait","config":{"durationSec":1}},
                   {"key":"yes2","type":"wait","config":{"durationSec":1}},
                   {"key":"no1","type":"wait","config":{"durationSec":1}},
                   {"key":"no2","type":"wait","config":{"durationSec":1}},
                   {"key":"join","type":"wait","config":{"durationSec":1}},
                   {"key":"end","type":"end","config":{"outputs":[
                     {"name":"needs_attention","value":"${nodes.review.output.json.needs_attention}"},
                     {"name":"summary","value":"${nodes.review.output.json.summary}"}]}}],
                 "edges":[
                   {"id":"a","from":"start","to":"review"},
                   {"id":"b","from":"review","to":"attention","onUpstreamFailure":"partial"},
                   {"id":"c","from":"attention","to":"yes1","sourceHandle":"true"},
                   {"id":"d","from":"yes1","to":"yes2"},
                   {"id":"e","from":"yes2","to":"join"},
                   {"id":"f","from":"attention","to":"no1","sourceHandle":"false"},
                   {"id":"g","from":"no1","to":"no2"},
                   {"id":"h","from":"no2","to":"join"},
                   {"id":"i","from":"join","to":"end"}]}
                """.formatted(policy);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void structuredOutputRoutesOnlySelectedMultiHopAndProducesFinalOutput(boolean choice) throws Exception {
        reply("{\"needs_attention\":" + choice + ",\"summary\":\"advisory\"}");
        Result result = drive(definition("skip"));
        assertThat(result.run.getStatus()).isEqualTo("succeeded");
        assertThat(result.status("review")).isEqualTo("succeeded");
        assertThat(result.node("attention").getOutputJson()).contains("\"result\":" + choice);
        String selected = choice ? "yes" : "no", rejected = choice ? "no" : "yes";
        for (int i = 1; i <= 2; i++) {
            assertThat(result.status(selected + i)).isEqualTo("succeeded");
            assertThat(result.status(rejected + i)).isEqualTo("skipped");
        }
        assertThat(result.status("join")).isEqualTo("succeeded");
        assertThat(result.status("end")).isEqualTo("succeeded");
        assertThat(effects).containsExactly(selected + "1", selected + "2", "join");
        assertThat(mapper.readTree(result.run.getOutputJson()).get("needs_attention").booleanValue()).isEqualTo(choice);
        assertThat(mapper.readTree(result.run.getOutputJson()).get("summary").textValue()).isEqualTo("advisory");
        verify(model, times(1)).call(any(Prompt.class));
    }

    static Stream<Arguments> invalidResponses() {
        return Stream.of(Arguments.of("invalid JSON", "not json"),
                Arguments.of("missing", "{\"needs_attention\":true}"),
                Arguments.of("wrong type", "{\"needs_attention\":\"true\",\"summary\":\"x\"}"),
                Arguments.of("extra", "{\"needs_attention\":true,\"summary\":\"x\",\"extra\":1}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidResponses")
    void invalidStrictOutputCannotCauseEffectsEvenWithPartial(String scenario, String text) throws Exception {
        reply(text);
        Result result = drive(definition("partial"));
        assertThat(result.run.getStatus()).isEqualTo("failed");
        assertThat(result.status("review")).isEqualTo("failed");
        assertThat(result.node("review").getOutputJson()).isNull();
        assertThat(effects).as(scenario).isEmpty();
        assertThat(result.status("end")).isIn("skipped", "cancelled");
        verify(model, times(1)).call(any(Prompt.class));
    }

    private void reply(String text) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private Result drive(String json) throws Exception {
        var def = mapper.readValue(json, WorkflowDefinition.class);
        assertThat(validator.validate(def)).filteredOn(i -> i.severity() == ValidationIssue.Severity.ERROR).isEmpty();
        var workflow = new Workflow();
        workflow.setName("structured-branch-test"); workflow.setCreatedBy(42L);
        workflow = workflows.saveAndFlush(workflow);
        var version = new WorkflowVersion();
        version.setWorkflowId(workflow.getId()); version.setVersion(1); version.setDefinitionJson(json);
        version.setDefinitionSha256("a".repeat(64)); version.setIsValid(true); version.setEstimatedRisk("LOW");
        version = versions.saveAndFlush(version);
        var run = new WorkflowRun();
        run.setId("wfr_struct_" + UUID.randomUUID().toString().replace("-", ""));
        run.setWorkflowId(workflow.getId()); run.setVersionId(version.getId()); run.setVersionNo(1);
        run.setTriggerType("manual"); run.setStatus("running"); run.setStartedBy(42L);
        run.setStartedAt(LocalDateTime.now()); run.setInputJson("{}"); runs.saveAndFlush(run);
        engine.drive(run);
        return new Result(runs.findById(run.getId()).orElseThrow(), DagScheduler.latestByKey(nodeRuns.findByRunId(run.getId())));
    }

    record Result(WorkflowRun run, Map<String, WorkflowNodeRun> nodes) {
        WorkflowNodeRun node(String key) { return Objects.requireNonNull(nodes.get(key), key); }
        String status(String key) { return node(key).getStatus(); }
    }

    @Test void frontendTemplateKeyContractSentinel() throws Exception {
        // Source sentinel only, not an execution or complete equivalence test of the TS builder.
        Path source = Path.of("../easyshell-web/src/pages/workflow/editor/templates/aiReview.ts");
        String ts = Files.readString(source);
        assertThat(ts).contains("outputMode: 'json_schema_v1'", "needs_attention: { type: 'boolean' }",
                "summary: { type: 'string' }", "required: ['needs_attention', 'summary']",
                "additionalProperties: false", "nodes.review.output.json.needs_attention == true",
                "sourceHandle: 'true'", "sourceHandle: 'false'",
                "${nodes.review.output.json.needs_attention}", "${nodes.review.output.json.summary}");
    }
}
