package com.easyshell.server.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NonHostV3DefinitionPolicyTest {
    private final NonHostV3DefinitionPolicy policy = new NonHostV3DefinitionPolicy(new ObjectMapper());

    private String graph(String node, String edgeCondition) {
        return "{\"nodes\":[{\"key\":\"gate\",\"type\":\"approval\",\"outputVar\":\"alias\",\"config\":{\"title\":\"approve\"}},"
                + node + (edgeCondition == null ? "" : ",{\"key\":\"middle\",\"type\":\"wait\",\"config\":{\"durationSec\":1}}")
                + "],\"edges\":[" + (edgeCondition == null ? "" : "{\"from\":\"gate\",\"to\":\"middle\"},")
                + "{\"from\":\"" + (edgeCondition == null ? "gate" : "middle") + "\",\"to\":\"next\""
                + (edgeCondition == null ? "" : ",\"condition\":\"" + edgeCondition + "\"") + "}]}";
    }

    private String end(String value) {
        return "{\"key\":\"next\",\"type\":\"end\",\"config\":{\"outputs\":[{\"name\":\"x\",\"value\":\"" + value + "\"}]}}";
    }

    @Test void acceptsOnlyAuditedApprovalFields() {
        assertThat(policy.allows(graph(end("${nodes.gate.output.decision}"), null))).isTrue();
        assertThat(policy.allows(graph(end("${nodes.gate.output.decidedBy}"), null))).isTrue();
        assertThat(policy.allows(graph(end("ok"), "nodes.gate.output.decision == 'approved'"))).isTrue();
    }

    @Test void permitsDirectRunInputsButNotMisleadingNestedPaths() {
        assertThat(policy.allows(graph(end("${run.input.message}"), null))).isTrue();
        assertThat(policy.allows(graph(end("ok"), "run.input.enabled == true"))).isTrue();
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"llm\",\"config\":{\"userPrompt\":\"run.input.message\"}}", null))).isTrue();
        assertThat(policy.allows(graph(end("${run.input.message.child}"), null))).isFalse();
        assertThat(policy.allows(graph(end("${run.input}"), null))).isFalse();
    }

    @Test void rejectsCommentAliasesBracketsAndWholeObjects() {
        for (String path : new String[]{"nodes.gate.output.comment", "nodes.gate.output.comment.x",
                "alias.comment", "alias", "nodes", "nodes.gate", "nodes.gate.output",
                "nodes['gate'].output.comment", "nodes.gate.output['comment']", "nodes.gate.output.other"}) {
            assertThat(policy.allows(graph(end("${" + path + "}"), null))).as(path).isFalse();
        }
        assertThat(policy.allows(graph(end("ok"), "alias.comment == 'x'"))).isFalse();
        assertThat(policy.allows(graph(end("ok"), "nodes['gate'].output.comment == 'x'"))).isFalse();
    }

    @Test void coversOtherEvaluationEntrypointsAndAmbiguousTemplates() {
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"condition\",\"config\":{\"expression\":\"nodes.gate.output.comment == 'x'\"}}", null))).isFalse();
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"llm\",\"config\":{\"userPrompt\":\"nodes.gate.output.comment\"}}", null))).isFalse();
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"transform\",\"config\":{\"mappings\":[{\"name\":\"x\",\"value\":\"${alias.comment}\"}]}}", null))).isFalse();
        assertThat(policy.allows(graph(end("${nodes.gate.output.decision"), null))).isFalse();
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"approval\",\"config\":{\"title\":\"{{x}}\",\"parameters\":{\"x\":\"${alias.comment}\"}}}", null))).isFalse();
        // A parameter can itself resolve to text containing a second-stage reference.
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"approval\",\"config\":{\"title\":\"{{x}}\",\"parameters\":{\"x\":\"${run.input.message}\"}}}", null))).isFalse();
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"approval\",\"config\":{\"title\":\"fixed\",\"parameters\":{\"x\":\"literal\"}}}", null))).isFalse();
        assertThat(policy.allows(graph(end("${run.input.message} ${nodes.gate.output.comment}"), null))).isFalse();
        assertThat(policy.allows(graph(end("ok"), "run.input.enabled == true || nodes.gate.output.comment == 'x'"))).isFalse();
    }

    @Test void llmSystemPromptIsLiteralNotAnEvaluatorEntrypoint() {
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"llm\",\"config\":{\"userPrompt\":\"hello\",\"systemPrompt\":\"${nodes.gate.output.comment}\"}}", null))).isTrue();
    }

    @Test void rejectsUnknownConfigurationInvalidGraphsAndDisconnectedUnsafeNodes() {
        assertThat(policy.allows("{\"nodes\":[{\"key\":\"x\",\"type\":\"wait\",\"config\":{\"durationSec\":1}}]}" )).isFalse();
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"wait\",\"config\":{\"durationSec\":0}}", null))).isFalse();
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"end\",\"config\":{\"unknown\":1}}", null))).isFalse();
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"llm\",\"config\":{\"userPrompt\":\"hello\",\"futureTemplate\":\"${nodes.gate.output.comment}\"}}", null))).isFalse();
        assertThat(policy.allows(graph("{\"key\":\"next\",\"type\":\"end\",\"config\":{\"outputs\":[{\"name\":\"x\",\"value\":\"ok\",\"future\":\"bad\"}]}}", null))).isFalse();
        assertThat(policy.allows("{\"nodes\":[{\"key\":\"gate\",\"type\":\"approval\"},{\"key\":\"bad\",\"type\":\"script-exec\"}],\"edges\":[]}")).isFalse();
        assertThat(policy.allows(graph(end("ok"), null) + " {}" )).isFalse();
        assertThat(policy.allows("{\"nodes\":[],\"nodes\":[]}")).isFalse();
        assertThat(policy.allows(null)).isFalse();
    }
}
