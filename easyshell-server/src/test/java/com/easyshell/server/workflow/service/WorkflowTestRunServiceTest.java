package com.easyshell.server.workflow.service;

import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.model.vo.WorkflowTestRunVO;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("WorkflowTestRunService — 工作流 2.0 新节点试运行预览（AI 不调模型）")
class WorkflowTestRunServiceTest {

    private static final String DEFINITION = """
            {"schemaVersion":1,
             "variables":{"threshold":{"type":"number"},"secret":{"type":"string"}},
             "nodes":[
               {"key":"begin","type":"start","name":"begin","config":{}},
               {"key":"mapping","type":"transform","name":"mapping","config":{"mappings":[
                 {"name":"usage","value":"${run.input.threshold}"},
                 {"name":"secret","value":"${run.input.secret}"},
                 {"name":"up","value":"${nodes.begin.output.threshold}"}]}},
               {"key":"ai","type":"ai-analyze","name":"ai","config":{"instructions":"分析"}},
               {"key":"llm","type":"llm","name":"llm","config":{"userPrompt":"分析"}},
               {"key":"classify","type":"classifier","name":"classify","config":{
                 "input":"${run.input.secret}","classes":[{"name":"disk","description":"磁盘"}]}},
               {"key":"code","type":"code","name":"code","config":{
                 "language":"javascript","code":"function main(input){ return input; }","inputs":{},"timeoutSec":5}},
               {"key":"finish","type":"end","name":"finish","config":{"outputs":[
                 {"name":"usage","value":"${run.input.threshold}"},
                 {"name":"up","value":"${nodes.mapping.output.usage}"}]}}],
             "edges":[]}
            """;

    private WorkflowTestRunService service;
    private WorkflowRun run;
    private Workflow workflow;
    private WorkflowVersionRepository versionRepository;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
        versionRepository = mock(WorkflowVersionRepository.class);
        WorkflowVersion version = new WorkflowVersion();
        version.setIsValid(true);
        version.setDefinitionJson(DEFINITION);
        when(versionRepository.findByIdAndWorkflowId(1L, 1L)).thenReturn(Optional.of(version));

        service = new WorkflowTestRunService(runRepository, versionRepository,
                new ExpressionEvaluator(objectMapper), mock(CommandRiskEngine.class),
                new SensitiveDataFilter(), mock(AuditLogService.class), objectMapper,
                mock(ClusterService.class), mock(TagService.class));

        run = new WorkflowRun();
        run.setId("wfr_testrun");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setStatus("running");
        run.setStartedBy(42L);
        run.setInputJson("{\"threshold\":95,\"secret\":\"token=abc123\"}");

        workflow = new Workflow();
        workflow.setId(1L);
        workflow.setName("preview");
    }

    private WorkflowTestRunVO test(String nodeKey) {
        return service.testRun(run, workflow, nodeKey, 42L, "tester");
    }

    @Test
    void rejectsUnpublishedVersionEvenWhenItContainsAClaimedSnapshot() {
        WorkflowVersion draft = new WorkflowVersion();
        draft.setIsValid(false);
        draft.setDefinitionJson(DEFINITION);
        when(versionRepository.findByIdAndWorkflowId(1L, 1L)).thenReturn(Optional.of(draft));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> test("begin"))
                .isInstanceOf(com.easyshell.server.common.exception.BusinessException.class);
    }

    @Test
    @DisplayName("start：resolvedParameters 是 run.input.* 的预览")
    void startPreview() {
        WorkflowTestRunVO result = test("begin");
        assertThat(result.getStatus()).isEqualTo("ok");
        assertThat(result.getNodeType()).isEqualTo("start");
        assertThat(result.getResolvedParameters()).containsEntry("threshold", "95");
        assertThat(result.getResolvedParameters()).containsEntry("secret", "token=***");
    }

    @Test
    @DisplayName("transform / end：解析 run.input.*，缺上游输出保留原模板并记 warning")
    void transformAndEndPreview() {
        WorkflowTestRunVO transform = test("mapping");
        assertThat(transform.getStatus()).isEqualTo("ok");
        assertThat(transform.getResolvedParameters()).containsEntry("usage", "95");
        assertThat(transform.getResolvedParameters()).containsEntry("secret", "token=***");
        assertThat(transform.getWarnings()).anySatisfy(w -> assertThat(w).contains("试运行"));

        WorkflowTestRunVO end = test("finish");
        assertThat(end.getStatus()).isEqualTo("ok");
        assertThat(end.getResolvedParameters()).containsEntry("usage", "95");
        assertThat(end.getWarnings()).anySatisfy(w -> assertThat(w).contains("试运行"));
    }

    @Test
    @DisplayName("ai-analyze：绝不调用模型，返回固定 warning")
    void aiPreviewDoesNotCallModel() {
        WorkflowTestRunVO result = test("ai");
        assertThat(result.getStatus()).isEqualTo("ok");
        assertThat(result.getNodeType()).isEqualTo("ai-analyze");
        assertThat(result.getWarnings())
                .containsExactly("AI 节点试运行不会调用模型，请在真实运行中验证");
    }

    @Test
    @DisplayName("llm / classifier：试运行不调用模型")
    void newAiAndCodePreviewsAreSideEffectFree() {
        for (String nodeKey : new String[]{"llm", "classify"}) {
            WorkflowTestRunVO result = test(nodeKey);
            assertThat(result.getStatus()).isEqualTo("ok");
            assertThat(result.getNodeType()).isEqualTo(
                    "classify".equals(nodeKey) ? "classifier" : nodeKey);
            assertThat(result.getWarnings()).containsExactly("试运行不会调用模型/执行代码");
        }
    }

    @Test
    void codePreviewExplicitlyReportsUnavailableAndNotExecuted() {
        WorkflowTestRunVO result = test("code");
        assertThat(result.getNodeType()).isEqualTo("code");
        assertThat(result.getStatus()).isEqualTo("unavailable");
        assertThat(result.getWarnings()).containsExactly(
                "该入口仅预演，不执行代码；请使用节点编辑器的隔离代码样例运行");
        assertThat(result.getRiskLevel()).isNull();
    }
}
