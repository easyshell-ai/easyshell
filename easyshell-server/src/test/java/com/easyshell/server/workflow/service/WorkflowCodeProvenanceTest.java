package com.easyshell.server.workflow.service;

import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.WorkflowRunProvenanceRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowRunV3BirthRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.workflow.code.WorkflowCodeWorkerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowCodeProvenanceTest {
    private static final String CODE_GRAPH = """
            {"schemaVersion":1,"nodes":[
              {"key":"start","type":"start","config":{}},
              {"key":"code","type":"code","config":{"language":"javascript",
                "code":"function main(input) { return {value: 2}; }","inputs":{},"timeoutSec":5}},
              {"key":"end","type":"end","config":{"outputs":[]}}],
             "edges":[{"id":"e1","from":"start","to":"code"},
                      {"id":"e2","from":"code","to":"end"}]}
            """;

    private final WorkflowRunProvenanceRepository provenance = mock(WorkflowRunProvenanceRepository.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    private final WorkflowRunV3BirthRepository births = mock(WorkflowRunV3BirthRepository.class);
    private final TaskTargetSnapshotRepository snapshots = mock(TaskTargetSnapshotRepository.class);
    private final HostAclService acl = mock(HostAclService.class);
    private final WorkflowCodeWorkerClient worker = mock(WorkflowCodeWorkerClient.class);
    // Direct construction deliberately avoids the MANDATORY transaction interceptor.
    // Repository writes are mocked; this suite checks classification and view decisions only.
    private final WorkflowRunProvenanceService service = new WorkflowRunProvenanceService(
            provenance, runs, versions, snapshots, acl, new ObjectMapper());
    private WorkflowRun run;
    private WorkflowVersion version;

    @BeforeEach
    void setup() throws Exception {
        ReflectionTestUtils.setField(service, "births", births);
        ReflectionTestUtils.setField(service, "codeWorker", worker);
        when(worker.isAvailable()).thenReturn(true);
        run = new WorkflowRun();
        run.setId("code-run");
        run.setWorkflowId(2L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setTriggerType("manual");
        run.setStartedBy(7L);
        run.setInputJson("{\"observation\":\"manual data\"}");
        version = new WorkflowVersion();
        version.setId(1L);
        version.setWorkflowId(2L);
        version.setVersion(1);
        version.setIsValid(true);
        definition(CODE_GRAPH);
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        when(versions.findById(1L)).thenReturn(Optional.of(version));
    }

    @Test
    void newManualCodeRunGetsDedicatedKindAndCanBeViewed() {
        service.initialize(run, version);
        verify(provenance).create(run.getId(), 1L, version.getDefinitionSha256(), "NON_HOST_CODE");
        record("NON_HOST_CODE", List.of());
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY, service.verifiedIdentity(run.getId()));
        assertTrue(service.canView(7L, run.getId()));
        verifyNoInteractions(snapshots, acl);
        verify(births, never()).create(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void disabledAtBirthDoesNotCreateCodeKind() {
        when(worker.isAvailable()).thenReturn(false);
        service.initialize(run, version);
        verify(provenance).create(run.getId(), 1L, version.getDefinitionSha256(), "HOST");
    }

    @Test
    void workerUnavailableLaterDoesNotRevokeHistoricalVisibility() {
        record("NON_HOST_CODE", List.of());
        when(worker.isAvailable()).thenReturn(false);
        clearInvocations(worker);
        assertTrue(service.canView(7L, run.getId()));
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY, service.verifiedIdentity(run.getId()));
        verifyNoInteractions(worker);
    }

    @Test
    void oldHostAndUnknownRecordsAreNeverUpgradedByReading() {
        run.setInputJson("{}");
        record("HOST", List.of());
        assertFalse(service.canView(7L, run.getId()));
        record("UNKNOWN", List.of());
        assertDenied();
        when(provenance.findComplete(run.getId())).thenReturn(Optional.empty());
        assertDenied();
        verify(provenance, never()).create(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void codeKindRequiresNoAssociatedTasks() {
        record("NON_HOST_CODE", List.of("unexpected-task"));
        assertDenied();
        verifyNoInteractions(snapshots, acl);
    }

    @Test
    void entireGraphRejectsDisconnectedHostAndUnknownNodes() throws Exception {
        for (String type : List.of("script-exec", "ai-analyze", "approval", "notify", "future-node")) {
            definition(CODE_GRAPH.replace("\"nodes\":[", "\"nodes\":[{\"key\":\"hidden\",\"type\":\""
                    + type + "\",\"config\":{}},"));
            record("NON_HOST_CODE", List.of());
            assertDenied();
            service.initialize(run, version);
            verify(provenance).create(run.getId(), 1L, version.getDefinitionSha256(), "HOST");
        }
    }

    @Test
    void codeKindRequiresCodeAndValidDag() throws Exception {
        for (String graph : List.of(
                CODE_GRAPH.replace("\"type\":\"code\"", "\"type\":\"wait\""),
                CODE_GRAPH.replace("\"to\":\"end\"", "\"to\":\"missing\""),
                CODE_GRAPH.replace("\"inputs\":{}", "\"inputs\":{\"x\":\"${nodes.end.output}\"}"))) {
            definition(graph);
            record("NON_HOST_CODE", List.of());
            assertDenied();
        }
    }

    @Test
    void codeKindRequiresManualAuthenticatedTriggerAndObjectInput() {
        record("NON_HOST_CODE", List.of());
        run.setTriggerType("scheduled");
        assertDenied();
        run.setTriggerType("manual");
        run.setStartedBy(null);
        assertDenied();
        run.setStartedBy(0L);
        assertDenied();
        run.setStartedBy(7L);
        run.setInputJson("[]");
        assertDenied();
    }

    @Test
    void damagedImmutableVersionAndContradictoryBirthEvidenceDeny() {
        record("NON_HOST_CODE", List.of());
        when(births.exists(run.getId())).thenReturn(true);
        assertDenied();
        when(births.exists(run.getId())).thenReturn(false);
        version.setIsValid(false);
        assertDenied();
        assertThrows(IllegalArgumentException.class, () -> service.initialize(run, version));
        version.setIsValid(true);
        version.setDefinitionSha256("b".repeat(64));
        assertDenied();
        assertThrows(IllegalArgumentException.class, () -> service.initialize(run, version));
        verify(provenance, never()).create(anyString(), anyLong(), anyString(), anyString());
    }

    private void definition(String json) throws Exception {
        version.setDefinitionJson(json);
        version.setDefinitionSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8))));
    }

    private void record(String kind, List<String> taskIds) {
        when(provenance.findComplete(run.getId())).thenReturn(Optional.of(
                new WorkflowRunProvenanceRepository.Provenance(1L, version.getDefinitionSha256(), kind, taskIds)));
    }

    private void assertDenied() {
        assertFalse(service.canView(7L, run.getId()));
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity(run.getId()));
    }
}
