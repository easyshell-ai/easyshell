package com.easyshell.server.workflow.service;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.vo.WorkflowNodeRunVO;
import com.easyshell.server.model.vo.WorkflowRunVO;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("WorkflowApiPresenter — run/nodeRun 输出与错误信息出站脱敏")
class WorkflowApiPresenterTest {

    private final WorkflowApiPresenter presenter = new WorkflowApiPresenter(
            new ObjectMapper(), new SensitiveDataFilter(),
            mock(WorkflowRepository.class), mock(WorkflowRunRepository.class));

    @Test
    @DisplayName("End 输出里复制自 run.input 的秘密：API output 返回 ***")
    void endOutputSecretIsMasked() {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_mask");
        run.setStatus("succeeded");
        run.setOutputJson("{\"summary\":\"token=supersecret-value\",\"ok\":true}");
        run.setErrorMessage("upstream password=hunter2 failed");

        WorkflowRunVO vo = presenter.run(run);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) vo.getOutput();
        assertThat(output.get("summary")).isEqualTo("token=***");
        assertThat(vo.getErrorMessage()).isEqualTo("upstream password=*** failed");
        assertThat(vo.getErrorMessage()).doesNotContain("hunter2");

        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId("wnr_mask");
        row.setRunId("wfr_mask");
        row.setNodeKey("finish");
        row.setNodeType("end");
        row.setAttempt(1);
        row.setStatus("succeeded");
        row.setOutputJson("{\"text\":\"Bearer abcdefghijklmnop\"}");
        row.setErrorMessage("api_key=sk-abcdefghijklmnopqrstuvwxyz012345");

        WorkflowNodeRunVO nodeVo = presenter.nodeRun(row);
        @SuppressWarnings("unchecked")
        Map<String, Object> nodeOutput = (Map<String, Object>) nodeVo.getOutput();
        assertThat(nodeOutput.get("text")).isEqualTo("Bearer ***");
        assertThat(nodeVo.getErrorMessage()).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz012345");
    }
}
