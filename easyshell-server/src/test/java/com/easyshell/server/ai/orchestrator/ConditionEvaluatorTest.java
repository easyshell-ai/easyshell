package com.easyshell.server.ai.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConditionEvaluator — DAG condition evaluation")
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    private ConditionEvaluator.StepState state(String status, String result, String outputVar) {
        return new ConditionEvaluator.StepState(status, result, outputVar);
    }

    @Test
    void nullExpression_returnsTrue() {
        assertThat(evaluator.evaluate(null, Map.of())).isTrue();
    }

    @Test
    void blankExpression_returnsTrue() {
        assertThat(evaluator.evaluate("   ", Map.of())).isTrue();
    }

    @Test
    void statusEquals_completed() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        stateMap.put(1, state("completed", "ok", null));

        assertThat(evaluator.evaluate("step[1].status == 'completed'", stateMap)).isTrue();
    }

    @Test
    void statusEquals_mismatch() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        stateMap.put(1, state("failed", null, null));

        assertThat(evaluator.evaluate("step[1].status == 'completed'", stateMap)).isFalse();
    }

    @Test
    void statusNotEquals() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        stateMap.put(2, state("completed", null, null));

        assertThat(evaluator.evaluate("step[2].status != 'failed'", stateMap)).isTrue();
    }

    @Test
    void outputVarContains() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        stateMap.put(0, state("completed", "nginx is running on port 80", "nginx is running on port 80"));

        assertThat(evaluator.evaluate("step[0].outputVar contains 'nginx'", stateMap)).isTrue();
    }

    @Test
    void outputVarContains_noMatch() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        stateMap.put(0, state("completed", "apache running", "apache running"));

        assertThat(evaluator.evaluate("step[0].outputVar contains 'nginx'", stateMap)).isFalse();
    }

    @Test
    void andCondition_bothTrue() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        stateMap.put(1, state("completed", "ok", null));
        stateMap.put(2, state("completed", "done", null));

        assertThat(evaluator.evaluate(
                "step[1].status == 'completed' && step[2].status == 'completed'", stateMap)).isTrue();
    }

    @Test
    void andCondition_oneFalse() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        stateMap.put(1, state("completed", null, null));
        stateMap.put(2, state("failed", null, null));

        assertThat(evaluator.evaluate(
                "step[1].status == 'completed' && step[2].status == 'completed'", stateMap)).isFalse();
    }

    @Test
    void missingStep_returnsFalse() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        // step[5] not in map
        assertThat(evaluator.evaluate("step[5].status == 'completed'", stateMap)).isFalse();
    }

    @Test
    void unparseableExpression_returnsTrue() {
        // fail-open: unparseable conditions don't block
        assertThat(evaluator.evaluate("some random text", Map.of())).isTrue();
    }

    @Test
    void resultField_equals() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        stateMap.put(3, state("completed", "SUCCESS", null));

        assertThat(evaluator.evaluate("step[3].result == 'SUCCESS'", stateMap)).isTrue();
    }

    @Test
    void nullFieldValue_treatedAsEmpty() {
        Map<Integer, ConditionEvaluator.StepState> stateMap = new HashMap<>();
        stateMap.put(0, state("completed", null, null));

        // null outputVar → ""  → does not contain 'data'
        assertThat(evaluator.evaluate("step[0].outputVar contains 'data'", stateMap)).isFalse();
        // null → "" == '' → true
        assertThat(evaluator.evaluate("step[0].outputVar == ''", stateMap)).isTrue();
    }
}
