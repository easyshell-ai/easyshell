package com.easyshell.server.workflow.service;

import com.easyshell.server.model.entity.*;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.workflow.gate.DispatchIntentHmac;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowDispatchApprovalAccessServiceTest {
    private final WorkflowProperties properties = new WorkflowProperties();
    private final WorkflowRunProvenanceService provenance = mock(WorkflowRunProvenanceService.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    private final WorkflowNodeRunRepository nodes = mock(WorkflowNodeRunRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HostAclService acl = mock(HostAclService.class);
    private final EntityManager em = mock(EntityManager.class);
    private final DispatchIntentHmac hmac = new DispatchIntentHmac(Base64.getEncoder().encodeToString(new byte[32]));
    private final WorkflowApproval approval = new WorkflowApproval();
    private final WorkflowRun run = new WorkflowRun();
    private final WorkflowNodeRun node = new WorkflowNodeRun();
    private final WorkflowDispatchApprovalBinding binding = new WorkflowDispatchApprovalBinding();
    private WorkflowDispatchApprovalAccessService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // Use an explicit test key without reading or changing the process environment.
        service = new WorkflowDispatchApprovalAccessService(properties, provenance, runs, versions, nodes,
                jdbc, acl, new ObjectMapper(), em) {
            @Override
            boolean validSignature(String runId, WorkflowDispatchApprovalBinding b, List<DispatchIntentHmac.Target> targets) {
                return DispatchIntentHmac.matches(b.getDispatchIntentHmac(), hmac.intentHmac(runId,
                        b.getDispatchNodeRunId(), b.getDispatchAttempt(), b.getDispatchUserId(),
                        b.getDispatchScriptHmac(), targets, b.getDispatchTimeoutSec()));
            }

            @Override
            boolean matchesDetailSignature(String signature, String payloadJson, String runId,
                    WorkflowDispatchApprovalBinding b, List<DispatchIntentHmac.Target> targets) {
                return DispatchIntentHmac.matches(signature, hmac.intentHmac(runId, b.getDispatchNodeRunId(),
                        b.getDispatchAttempt(), b.getDispatchUserId(), hmac.scriptHmac(payloadJson), targets,
                        b.getDispatchTimeoutSec()));
            }
        };
        properties.getApproval().setDispatchEnabled(true);
        approval.setId(7L);
        approval.setRunId("run");
        approval.setNodeKey("dispatch");
        approval.setNodeType("script-exec");
        approval.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        approval.setDetailJson(signedDetail(detailPayload()));
        run.setId("run");
        run.setVersionId(2L);
        run.setStartedBy(9L);
        run.setStatus("waiting");
        run.setInputJson("{}");
        node.setId("node");
        node.setRunId("run");
        node.setNodeKey("dispatch");
        node.setNodeType("script-exec");
        node.setStatus("waiting");
        node.setApprovalId(7L);
        binding.setApprovalId(7L);
        binding.setDispatchNodeRunId("node");
        binding.setDispatchAttempt(1);
        binding.setDispatchUserId(9L);
        binding.setDispatchTimeoutSec(60);
        binding.setDispatchScriptHmac(hmac.scriptHmac("test fixture"));
        binding.setDispatchTargets("[{\"agentId\":\"host\",\"generation\":3}]");
        binding.setDispatchIntentHmac(hmac.intentHmac("run", "node", 1, 9L, binding.getDispatchScriptHmac(),
                List.of(new DispatchIntentHmac.Target("host", 3)), 60));
        WorkflowVersion version = new WorkflowVersion();
        version.setDefinitionJson("{\"nodes\":[{\"key\":\"dispatch\",\"type\":\"script-exec\"}]}");
        when(em.find(WorkflowApproval.class, 7L)).thenReturn(approval);
        when(em.find(WorkflowDispatchApprovalBinding.class, 7L)).thenReturn(binding);
        when(provenance.verifiedIdentity("run")).thenReturn(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY);
        when(runs.findById("run")).thenReturn(Optional.of(run));
        when(versions.findById(2L)).thenReturn(Optional.of(version));
        when(nodes.findByRunId("run")).thenReturn(List.of(node));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run"))).thenReturn(List.of(true));
        when(jdbc.queryForObject(contains("dispatch_node_run_id"), eq(Long.class), eq("node"), eq(1))).thenReturn(1L);
        when(jdbc.queryForObject(contains("workflow_node_run WHERE approval_id"), eq(Long.class), eq(7L))).thenReturn(1L);
        when(jdbc.queryForObject(contains("SELECT task_count"), eq(Long.class), eq("run"))).thenReturn(0L);
        when(jdbc.queryForObject(contains("COUNT(*) FROM workflow_run_provenance_task"), eq(Long.class), eq("run"))).thenReturn(0L);
        when(acl.allowedForGeneration(4L, "host", 3L, HostAclService.Permission.VIEW)).thenReturn(true);
    }

    private LinkedHashMap<String, Object> detailPayload() {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("scriptPreview", "echo ***");
        var target = new LinkedHashMap<String, Object>();
        target.put("agentId", "host");
        target.put("generation", 3L);
        detail.put("targets", List.of(target));
        detail.put("timeoutSeconds", 60);
        detail.put("riskLevel", "HIGH");
        return detail;
    }

    private String signedDetail(LinkedHashMap<String, Object> detail) throws Exception {
        var mapper = new ObjectMapper();
        detail.put("detailHmac", hmac.intentHmac("run", "node", 1, 9L,
                hmac.scriptHmac(mapper.writeValueAsString(detail)),
                List.of(new DispatchIntentHmac.Target("host", 3)), 60));
        return mapper.writeValueAsString(detail);
    }

    @Test
    void missingMalformedAndChangedSnapshotFailsClosed() throws Exception {
        String valid = approval.getDetailJson();
        for (String detail : List.of("{}", "[]", "null", "{", valid + " {}",
                valid.replace("echo ***", "echo changed"), valid.replace("HIGH", "LOW"),
                valid.replace("\"timeoutSeconds\":60", "\"timeoutSeconds\":61"),
                valid.replace("\"generation\":3", "\"generation\":4"),
                valid.replace("\"scriptPreview\":", "\"scriptPreview\":\"duplicate\",\"scriptPreview\":"))) {
            approval.setDetailJson(detail);
            assertThat(service.canView(4L, approval)).as(detail).isFalse();
        }
        approval.setDetailJson(null);
        assertThat(service.canView(4L, approval)).isFalse();
        approval.setDetailJson(valid);
        assertThat(service.canView(4L, approval)).isTrue();
    }

    @Test
    void payloadOrderIsPreservedAndReorderingRequiresNewSignature() throws Exception {
        var mapper = new ObjectMapper();
        var original = mapper.readTree(approval.getDetailJson());
        var reordered = new LinkedHashMap<String, Object>();
        reordered.put("riskLevel", "HIGH");
        reordered.put("timeoutSeconds", 60);
        reordered.put("targets", detailPayload().get("targets"));
        reordered.put("scriptPreview", "echo ***");
        reordered.put("detailHmac", original.path("detailHmac").textValue());
        approval.setDetailJson(mapper.writeValueAsString(reordered));
        assertThat(service.canView(4L, approval)).isFalse();
        reordered.remove("detailHmac");
        approval.setDetailJson(signedDetail(reordered));
        assertThat(service.canView(4L, approval)).isTrue();
        // Formatting is normalized by parsing; object insertion order is retained.
        approval.setDetailJson(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                mapper.readTree(approval.getDetailJson())));
        assertThat(service.canView(4L, approval)).isTrue();
    }

    @Test
    void nestedTargetOrderIsAlsoPartOfSnapshotSignature() throws Exception {
        String original = approval.getDetailJson();
        approval.setDetailJson(original.replace("\"agentId\":\"host\",\"generation\":3",
                "\"generation\":3,\"agentId\":\"host\""));
        assertThat(service.canView(4L, approval)).isFalse();
        var payload = detailPayload();
        var reversedTarget = new LinkedHashMap<String, Object>();
        reversedTarget.put("generation", 3L);
        reversedTarget.put("agentId", "host");
        payload.put("targets", List.of(reversedTarget));
        approval.setDetailJson(signedDetail(payload));
        assertThat(service.canView(4L, approval)).isTrue();
    }

    @Test
    void snapshotSignedForAnotherRunOrBindingCannotBeReused() throws Exception {
        var mapper = new ObjectMapper();
        for (String[] identity : List.of(new String[]{"other_run", "node"}, new String[]{"run", "other_node"})) {
            var payload = detailPayload();
            payload.put("detailHmac", hmac.intentHmac(identity[0], identity[1], 1, 9L,
                    hmac.scriptHmac(mapper.writeValueAsString(payload)),
                    List.of(new DispatchIntentHmac.Target("host", 3)), 60));
            approval.setDetailJson(mapper.writeValueAsString(payload));
            assertThat(service.canView(4L, approval)).isFalse();
        }
        approval.setDetailJson(signedDetail(detailPayload()));
        assertThat(service.canView(4L, approval)).isTrue();
    }

    @Test
    void evenSignedSnapshotMustMatchBindingTargetsTimeoutAndAllowedRisk() throws Exception {
        for (String field : List.of("targets", "timeoutSeconds", "riskLevel")) {
            var payload = detailPayload();
            switch (field) {
                case "targets" -> payload.put(field, List.of(new DispatchIntentHmac.Target("other_host", 3)));
                case "timeoutSeconds" -> payload.put(field, 61);
                case "riskLevel" -> payload.put(field, "LOW");
                default -> throw new AssertionError(field);
            }
            approval.setDetailJson(signedDetail(payload));
            assertThat(service.canView(4L, approval)).isFalse();
        }
        var medium = detailPayload();
        medium.put("riskLevel", "MEDIUM");
        approval.setDetailJson(signedDetail(medium));
        assertThat(service.canView(4L, approval)).isTrue();
    }

    @Test
    void firstHostApprovalAllowsContentWithoutGrantingRunView() {
        assertThat(service.canView(4L, approval)).isTrue();
        verify(provenance, never()).canView(anyLong(), anyString());
    }

    @Test
    void existingTaskRequiresIndependentRunView() {
        when(jdbc.queryForObject(contains("SELECT task_count"), eq(Long.class), eq("run"))).thenReturn(1L);
        when(jdbc.queryForObject(contains("COUNT(*) FROM workflow_run_provenance_task"), eq(Long.class), eq("run"))).thenReturn(1L);
        assertThat(service.canView(4L, approval)).isFalse();
        when(provenance.canView(4L, "run")).thenReturn(true);
        assertThat(service.canView(4L, approval)).isTrue();
    }

    @Test
    void disabledUnknownIdentityAndNonemptyInputFailClosed() {
        properties.getApproval().setDispatchEnabled(false);
        assertThat(service.canView(4L, approval)).isFalse();
        properties.getApproval().setDispatchEnabled(true);
        when(provenance.verifiedIdentity("run")).thenReturn(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN);
        assertThat(service.canView(4L, approval)).isFalse();
        when(provenance.verifiedIdentity("run")).thenReturn(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY);
        run.setInputJson("{\"source\":\"unproven\"}");
        assertThat(service.canView(4L, approval)).isFalse();
    }

    @Test
    void generationChangesAndTamperedSignedFieldsFailClosed() {
        binding.setDispatchTargets("[{\"agentId\":\"host\",\"generation\":4}]");
        assertThat(service.canView(4L, approval)).isFalse();
        binding.setDispatchTargets("[{\"agentId\":\"host\",\"generation\":3}]");
        binding.setDispatchTimeoutSec(61);
        assertThat(service.canView(4L, approval)).isFalse();
        binding.setDispatchTimeoutSec(60);
        when(acl.allowedForGeneration(4L, "host", 3L, HostAclService.Permission.VIEW)).thenReturn(false);
        assertThat(service.canView(4L, approval)).isFalse();
    }

    @Test
    void malformedDuplicateAndIncompleteTargetsFailClosed() {
        for (String json : List.of("[]", "[{\"agentId\":\"host\"}]",
                "[{\"agentId\":\"host\",\"generation\":0}]",
                "[{\"agentId\":\"host\",\"generation\":3,\"extra\":1}]",
                "[{\"agentId\":\"host\",\"generation\":3.0}]",
                "[{\"agentId\":\"host\",\"generation\":3},{\"agentId\":\"host\",\"generation\":3}]",
                "[{\"agentId\":\"host\",\"generation\":3,\"generation\":3}]",
                "[{\"agentId\":\"host\",\"generation\":3}] {}")) {
            binding.setDispatchTargets(json);
            assertThat(service.canView(4L, approval)).isFalse();
        }
    }

    @Test
    void expiredConsumedOrSupersededApprovalFailsClosed() {
        approval.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThat(service.canView(4L, approval)).isFalse();
        approval.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        binding.setDispatchConsumedAt(LocalDateTime.now());
        assertThat(service.canView(4L, approval)).isFalse();
        binding.setDispatchConsumedAt(null);
        WorkflowNodeRun newer = new WorkflowNodeRun();
        newer.setId("newer");
        newer.setRunId("run");
        newer.setNodeKey("dispatch");
        newer.setAttempt(2);
        when(nodes.findByRunId("run")).thenReturn(List.of(node, newer));
        assertThat(service.canView(4L, approval)).isFalse();
    }
}
