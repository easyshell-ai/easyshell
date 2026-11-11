package com.easyshell.server.workflow.service;

import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.*;
import com.easyshell.server.service.HostAclService;
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

class WorkflowRunProvenanceServiceTest {
    private final WorkflowRunProvenanceRepository provenance = mock(WorkflowRunProvenanceRepository.class);
    private final WorkflowRunV3BirthRepository births = mock(WorkflowRunV3BirthRepository.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    private final TaskTargetSnapshotRepository snapshots = mock(TaskTargetSnapshotRepository.class);
    private final HostAclService acl = mock(HostAclService.class);
    private final WorkflowRunProvenanceService service = new WorkflowRunProvenanceService(
            provenance, runs, versions, snapshots, acl, new ObjectMapper());
    private WorkflowRun run;
    private WorkflowVersion version;

    @BeforeEach
    void setup() throws Exception {
        ReflectionTestUtils.setField(service, "births", births);
        run = new WorkflowRun(); run.setId("r"); run.setVersionId(1L); run.setWorkflowId(2L);
        run.setVersionNo(1); run.setInputJson("{}");
        version = new WorkflowVersion(); version.setId(1L); version.setWorkflowId(2L); version.setVersion(1);
        version.setIsValid(true); definition("{\"nodes\":[{\"type\":\"wait\"}]}");
        when(runs.findById("r")).thenReturn(Optional.of(run));
        when(versions.findById(1L)).thenReturn(Optional.of(version));
    }

    private void definition(String json) throws Exception {
        version.setDefinitionJson(json);
        version.setDefinitionSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8))));
    }

    private void record(String kind, List<String> tasks) {
        when(provenance.findComplete("r")).thenReturn(Optional.of(
                new WorkflowRunProvenanceRepository.Provenance(1, version.getDefinitionSha256(), kind, tasks)));
    }

    @Test
    void unknownAndHostWithoutTasksAreDenied() {
        assertFalse(service.canView(1L, "r"));
        record("HOST", List.of());
        assertFalse(service.canView(1L, "r"));
    }

    @Test
    void verifiedLegacyRequiresCompleteBoundEvidenceAndNoBirthMarker() {
        record("HOST", List.of());
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY, service.verifiedIdentity("r"));
        when(births.exists("r")).thenReturn(true);
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r"));
        when(births.exists("r")).thenReturn(false);
        version.setDefinitionSha256("b".repeat(64));
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r"));
    }

    @Test
    void executionDefinitionMustMatchTheFrozenVersionWithoutGrantingView() throws Exception {
        record("HOST", List.of());
        ObjectMapper mapper = new ObjectMapper();
        var definition = mapper.readValue(version.getDefinitionJson(),
                com.easyshell.server.workflow.model.WorkflowDefinition.class);
        assertTrue(service.matchesExecutionDefinition("r", definition));
        assertFalse(service.canView(1L, "r"));
        definition.getNodes().get(0).setType("script-exec");
        assertFalse(service.matchesExecutionDefinition("r", definition));
    }

    @Test
    void contradictoryBirthMarkerDeniesOtherwiseViewablePureRecord() {
        record("PURE", List.of());
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY, service.verifiedIdentity("r"));
        assertTrue(service.canView(1L, "r"));

        when(births.exists("r")).thenReturn(true);
        assertAll(
                () -> assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r")),
                () -> assertFalse(service.canView(1L, "r")));
    }

    @Test
    void contradictoryBirthMarkerDeniesOtherwiseViewableHostRecord() {
        record("HOST", List.of("t"));
        when(snapshots.findComplete("t")).thenReturn(Optional.of(new TaskTargetSnapshot("t", List.of(
                new TaskTargetSnapshot.Target("a", 1)))));
        when(acl.allowedForGeneration(1L, "a", 1, HostAclService.Permission.VIEW)).thenReturn(true);
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY, service.verifiedIdentity("r"));
        assertTrue(service.canView(1L, "r"));

        when(births.exists("r")).thenReturn(true);
        assertAll(
                () -> assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r")),
                () -> assertFalse(service.canView(1L, "r")));
    }

    @Test
    void unavailableBirthEvidenceDeniesOtherwiseViewableLegacyRecord() {
        record("PURE", List.of());
        assertTrue(service.canView(1L, "r"));
        when(births.exists("r")).thenThrow(new IllegalStateException("birth evidence unavailable"));
        assertAll(
                () -> assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r")),
                () -> assertFalse(service.canView(1L, "r")));
    }

    @Test
    void unknownAndDisabledKindsNeverEstablishIdentityOrVisibility() {
        for (String kind : List.of("NON_HOST_V2", "NON_HOST_V3", "NON_HOST_V4", "UNKNOWN", "")) {
            record(kind, List.of());
            assertAll(kind,
                    () -> assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r")),
                    () -> assertFalse(service.canView(1L, "r")));
        }
    }

    @Test
    void mismatchedRunIdentityDeniesOtherwiseValidPureRecord() {
        record("PURE", List.of());
        run.setId("another-run");
        assertAll(
                () -> assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r")),
                () -> assertFalse(service.canView(1L, "r")));
    }

    @Test
    void pureRequiresImmutableDefinitionAndNoInputOrSources() throws Exception {
        record("PURE", List.of());
        assertTrue(service.canView(1L, "r"));
        run.setInputJson("{\"x\":1}");
        assertFalse(service.canView(1L, "r"));
        run.setInputJson("{}");
        definition("{\"nodes\":[{\"type\":\"script-exec\"}]}");
        record("PURE", List.of());
        assertFalse(service.canView(1L, "r"));
        version.setDefinitionSha256("a".repeat(64));
        assertFalse(service.canView(1L, "r"));
    }

    @Test
    void everySnapshotGenerationRequiresViewAndErrorsDeny() {
        record("HOST", List.of("t"));
        assertFalse(service.canView(1L, "r"));
        when(snapshots.findComplete("t")).thenReturn(Optional.of(new TaskTargetSnapshot("t", List.of(
                new TaskTargetSnapshot.Target("a", 1), new TaskTargetSnapshot.Target("b", 2)))));
        when(acl.allowedForGeneration(1L, "a", 1, HostAclService.Permission.VIEW)).thenReturn(true);
        assertFalse(service.canView(1L, "r"));
        when(acl.allowedForGeneration(1L, "b", 2, HostAclService.Permission.VIEW)).thenReturn(true);
        assertTrue(service.canView(1L, "r"));
        when(acl.allowedForGeneration(1L, "b", 2, HostAclService.Permission.VIEW)).thenThrow(new IllegalStateException());
        assertFalse(service.canView(1L, "r"));
    }

    private static final String AI_REVIEW = """
            {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort",
             "variables":{"observation":{"type":"string","required":true}},
             "nodes":[
               {"key":"start","type":"start","config":{}},
               {"key":"review","type":"llm","onFailure":"abort","retryMax":0,"config":{"userPrompt":"Review this manually supplied observation:\\n${run.input.observation}","outputMode":"json_schema_v1","jsonSchema":"{\\"type\\":\\"object\\",\\"properties\\":{\\"needs_attention\\":{\\"type\\":\\"boolean\\"},\\"summary\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"needs_attention\\",\\"summary\\"],\\"additionalProperties\\":false}","temperature":0,"maxInputChars":8000}},
               {"key":"attention","type":"condition","config":{"expression":"nodes.review.output.json.needs_attention == true"}},
               {"key":"no_attention","type":"transform","config":{"mappings":[{"name":"note","value":"Human review is still required."}]}},
               {"key":"end","type":"end","config":{"outputs":[{"name":"summary","value":"${nodes.review.output.json.summary}"}]}}],
             "edges":[{"id":"start->review","from":"start","to":"review"},
               {"id":"review->attention","from":"review","to":"attention"},
               {"id":"attention:true->end","from":"attention","to":"end","sourceHandle":"true"},
               {"id":"attention:false->no_attention","from":"attention","to":"no_attention","sourceHandle":"false"},
               {"id":"no_attention->end","from":"no_attention","to":"end"}]}
            """;

    @Test
    void contradictoryBirthMarkerDeniesOtherwiseViewableV1Record() throws Exception {
        definition(AI_REVIEW);
        run.setTriggerType("manual");
        run.setStartedBy(1L);
        run.setInputJson("{\"observation\":\"manual data\"}");
        record("NON_HOST_V1", List.of());
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY, service.verifiedIdentity("r"));
        assertTrue(service.canView(1L, "r"));

        when(births.exists("r")).thenReturn(true);
        assertAll(
                () -> assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r")),
                () -> assertFalse(service.canView(1L, "r")));
    }

    @Test
    void manualAiTemplateAcceptsUserInputWithVersionedProof() throws Exception {
        definition(AI_REVIEW);
        run.setStartedBy(1L);
        run.setInputJson("{\"observation\":\"manually entered text\"}");
        service.initialize(run, version);
        verify(provenance).create("r", 1L, version.getDefinitionSha256(), "NON_HOST_V1");
        record("NON_HOST_V1", List.of());
        assertTrue(service.canView(1L, "r"));
        record("PURE", List.of());
        assertFalse(service.canView(1L, "r"));
        record("NON_HOST_V1", List.of("unexpected"));
        assertFalse(service.canView(1L, "r"));
    }

    @Test
    void proofRejectsOtherTriggersUntrustedGraphsAndInvalidReferences() throws Exception {
        run.setStartedBy(1L);
        run.setInputJson("{\"observation\":\"text\"}");
        definition(AI_REVIEW);
        run.setTriggerType("scheduled");
        service.initialize(run, version);
        verify(provenance).create("r", 1L, version.getDefinitionSha256(), "HOST");
        record("NON_HOST_V1", List.of());
        assertFalse(service.canView(1L, "r"));
        run.setTriggerType("manual");
        run.setStartedBy(null);
        assertFalse(service.canView(1L, "r"));
        run.setStartedBy(1L);
        definition(AI_REVIEW.replace("\"type\":\"transform\"", "\"type\":\"script-exec\""));
        record("NON_HOST_V1", List.of());
        assertFalse(service.canView(1L, "r"));
        definition(AI_REVIEW.replace("\"type\":\"transform\"", "\"type\":\"future-node\""));
        record("NON_HOST_V1", List.of());
        assertFalse(service.canView(1L, "r"));
        definition(AI_REVIEW.replace("${run.input.observation}", "${nodes.no_attention.output.note}"));
        record("NON_HOST_V1", List.of());
        assertFalse(service.canView(1L, "r"));
        definition(AI_REVIEW.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unknownSource\":{}"));
        record("NON_HOST_V1", List.of());
        assertFalse(service.canView(1L, "r"));
    }

    @Test
    void approvalRemainsUnprovenEvenForExistingV2Records() throws Exception {
        run.setTriggerType("manual");
        run.setStartedBy(1L);
        run.setInputJson("{\"observation\":\"manual data\"}");
        String approval = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort",
                 "variables":{"observation":{"type":"string","required":true}},
                 "nodes":[{"key":"a","type":"wait","config":{"durationSec":1}},
                   {"key":"b","type":"approval","config":{"title":"Review","approverRole":"ADMIN","timeoutSec":120}},
                   {"key":"c","type":"wait","config":{"durationSec":1}}],
                 "edges":[{"id":"e1","from":"a","to":"b"},{"id":"e2","from":"b","to":"c"}]}
                """;
        definition(approval);
        service.initialize(run, version);
        verify(provenance).create("r", 1L, version.getDefinitionSha256(), "HOST");
        verify(births, never()).create(anyString(), anyLong(), anyString(), anyString());
        when(births.find("r")).thenReturn(Optional.of(new WorkflowRunV3BirthRepository.Birth(
                "r", 1, version.getDefinitionSha256(), "NON_HOST_V3")));
        record("NON_HOST_V3", List.of());
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r"));
        when(births.find("r")).thenReturn(Optional.empty());
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r"));
        when(births.find("r")).thenReturn(Optional.of(new WorkflowRunV3BirthRepository.Birth(
                "r", 1, "b".repeat(64), "NON_HOST_V3")));
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r"));
        when(births.find("r")).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(WorkflowRunProvenanceService.VerifiedIdentity.UNKNOWN, service.verifiedIdentity("r"));
        record("NON_HOST_V2", List.of());
        assertFalse(service.canView(1L, "r"));
        // Even a repository returning a V3 record must not grant access until the
        // execution and persisted-state boundaries are integrated together.
        record("NON_HOST_V3", List.of());
        assertFalse(service.canView(1L, "r"));
        record("NON_HOST_V1", List.of());
        assertFalse(service.canView(1L, "r"));
        record("NON_HOST_V2", List.of("unexpected"));
        assertFalse(service.canView(1L, "r"));
        definition(approval.replace("\"type\":\"wait\"", "\"type\":\"script-exec\""));
        record("NON_HOST_V2", List.of());
        assertFalse(service.canView(1L, "r"));
        definition(approval.replace("\"type\":\"wait\"", "\"type\":\"ai-analyze\""));
        record("NON_HOST_V2", List.of());
        assertFalse(service.canView(1L, "r"));
        definition(approval);
        record("NON_HOST_V2", List.of());
        run.setTriggerType("scheduled");
        assertFalse(service.canView(1L, "r"));
        run.setTriggerType("manual");
        run.setStartedBy(null);
        assertFalse(service.canView(1L, "r"));
    }
}
