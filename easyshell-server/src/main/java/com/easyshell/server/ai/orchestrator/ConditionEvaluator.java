package com.easyshell.server.ai.orchestrator;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates condition expressions for DAG step gating.
 * Supports: step[N].status == 'value', step[N].outputVar contains 'text'
 */
@Slf4j
@Component
public class ConditionEvaluator {

    // Matches: step[2].status == 'completed'  or  step[1].outputVar contains 'nginx'
    private static final Pattern CONDITION_PATTERN =
            Pattern.compile("step\\[(\\d+)]\\.(\\w+)\\s*(==|!=|contains)\\s*'([^']*)'");

    @Data
    @AllArgsConstructor
    public static class StepState {
        private String status;
        private String result;
        private String outputVar;
    }

    public boolean evaluate(String expression, Map<Integer, StepState> stateMap) {
        if (expression == null || expression.isBlank()) {
            return true;
        }

        // Support AND-joined conditions: "expr1 && expr2"
        String[] parts = expression.split("\\s*&&\\s*");
        for (String part : parts) {
            if (!evaluateSingle(part.trim(), stateMap)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateSingle(String expr, Map<Integer, StepState> stateMap) {
        Matcher matcher = CONDITION_PATTERN.matcher(expr);
        if (!matcher.matches()) {
            log.warn("Unparseable condition expression: {}", expr);
            return true; // fail-open: unparseable conditions don't block
        }

        int stepIndex = Integer.parseInt(matcher.group(1));
        String field = matcher.group(2);
        String operator = matcher.group(3);
        String expected = matcher.group(4);

        StepState state = stateMap.get(stepIndex);
        if (state == null) {
            log.warn("No state for step[{}] in condition: {}", stepIndex, expr);
            return false;
        }

        String actual = resolveField(state, field);
        if (actual == null) {
            actual = "";
        }

        return switch (operator) {
            case "==" -> actual.equals(expected);
            case "!=" -> !actual.equals(expected);
            case "contains" -> actual.contains(expected);
            default -> {
                log.warn("Unknown operator '{}' in condition: {}", operator, expr);
                yield true;
            }
        };
    }

    private String resolveField(StepState state, String field) {
        return switch (field) {
            case "status" -> state.getStatus();
            case "result" -> state.getResult();
            case "outputVar" -> state.getOutputVar();
            default -> {
                log.warn("Unknown field '{}' in condition", field);
                yield null;
            }
        };
    }
}
