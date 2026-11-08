package com.easyshell.server.workflow;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ShellSafeRenderer;
import com.easyshell.server.workflow.expression.SubstitutionGuard;
import com.easyshell.server.workflow.expression.WorkflowValuePolicy;
import com.easyshell.server.workflow.model.RenderResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * 变量注入安全模型的 26 条实测用例（设计 §2.4.8，M1 验收必备，全部真实运行）。
 *
 * <p>驱动真实 {@link CommandRiskEngine}（基线 HEAD，已含 1.1.3 的 find 写操作补丁），
 * 按 §2.4.3 的严格顺序跑完整管线：静态检查 → 求值 → L1 → L2 → L3 → L4。
 * "实测"列与设计文档逐条对齐——这不是纸面推导，是跑出来的。</p>
 *
 * <p>前 6 条对应评审 B3 的六类 payload 双向验证；#25/#26 是 v3 新增的边界；
 * #16 给出 1.1.3 补丁后的 MEDIUM 判定。</p>
 */
@DisplayName("工作流注入防护 — 26 条实测用例（§2.4.8）")
class WorkflowInjectionTest {

    private CommandRiskEngine riskEngine;
    private ExpressionEvaluator evaluator;

    /** 管线执行结果：rendered + L4 等级，或拒绝码 */
    private record Outcome(String rendered, RiskLevel risk, String rejectCode) {

        static Outcome passed(String rendered, RiskLevel risk) {
            return new Outcome(rendered, risk, null);
        }

        static Outcome rejected(String code) {
            return new Outcome(null, null, code);
        }

        boolean rejected() {
            return rejectCode != null;
        }
    }

    @BeforeEach
    void setUp() {
        AgenticConfigService config = Mockito.mock(AgenticConfigService.class);
        lenient().when(config.getStringSet(anyString(), eq(Set.of()))).thenReturn(Set.of());
        lenient().when(config.getStringList(anyString(), eq(List.of()))).thenReturn(List.of());
        riskEngine = new CommandRiskEngine(config);
        evaluator = new ExpressionEvaluator(new ObjectMapper());
    }

    /**
     * 跑 §2.4.3 的六步管线（与 ScriptExecNodeExecutor 同一顺序）。
     *
     * @param rawParams 声明了 raw:true 的参数名
     */
    private Outcome pipeline(String template, Map<String, String> parameters, Set<String> rawParams) {
        // ① 静态检查：槽位格式 / 槽位 ⊆ parameters / raw 声明
        for (String loose : ShellSafeRenderer.looseSlots(template)) {
            boolean wellFormed = loose.matches("\\{\\{\\s*[A-Z][A-Z0-9_]*(:raw)?\\s*}}");
            if (!wellFormed) {
                return Outcome.rejected("PARAM_SLOT_MALFORMED(" + loose + ")");
            }
        }
        for (String slot : ShellSafeRenderer.slotNames(template)) {
            if (!parameters.containsKey(slot)) {
                return Outcome.rejected("PARAM_MISSING(" + slot + ")");
            }
        }
        for (String rawSlot : ShellSafeRenderer.rawSlotNames(template)) {
            if (!rawParams.contains(rawSlot)) {
                return Outcome.rejected("PARAM_RAW_UNDECLARED(" + rawSlot + ")");
            }
        }
        // ② 求值（字面量直传）
        Map<String, String> values = new LinkedHashMap<>(parameters);
        // ③ L1
        for (Map.Entry<String, String> entry : values.entrySet()) {
            boolean raw = rawParams.contains(entry.getKey());
            if (WorkflowValuePolicy.check(entry.getKey(), entry.getValue(), raw)
                    == WorkflowValuePolicy.ValueDisposition.REJECT) {
                return Outcome.rejected("PARAM_UNSAFE(" + entry.getKey() + ")");
            }
        }
        // ④ L2 渲染
        RenderResult rendered = ShellSafeRenderer.render(template, values, rawParams);
        // ⑤ L3
        String reject = SubstitutionGuard.rejectSubstitution(rendered.rendered(), rendered.valueRanges());
        if (reject != null) {
            return Outcome.rejected(reject);
        }
        // ⑥ L4
        return Outcome.passed(rendered.rendered(), riskEngine.assessScript(rendered.rendered()).getOverallRisk());
    }

    private Outcome pipeline(String template, String paramName, String value) {
        return pipeline(template, Map.of(paramName, value), Set.of());
    }

    @Test
    @DisplayName("#1 $(reboot) 作为参数值被 L1 拒绝")
    void case01_commandSubstitutionPayload() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "$(reboot)");
        assertThat(outcome.rejected()).isTrue();
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_UNSAFE(MSG)");
    }

    @Test
    @DisplayName("#2 反引号 `id` 作为参数值被 L1 拒绝")
    void case02_backtickPayload() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "`id`");
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_UNSAFE(MSG)");
    }

    @Test
    @DisplayName("#3 ${IFS} 作为参数值被 L1 拒绝")
    void case03_ifsPayload() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "${IFS}");
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_UNSAFE(MSG)");
    }

    @Test
    @DisplayName("#4 引号逃逸 + 命令拼接 '; rm -rf / # 被 L1 拒绝")
    void case04_quoteEscapePayload() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "'; rm -rf / #");
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_UNSAFE(MSG)");
    }

    @Test
    @DisplayName("#5 含换行的参数值被 L1 拒绝")
    void case05_newlinePayload() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "a\nb");
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_UNSAFE(MSG)");
    }

    @Test
    @DisplayName("#6 引号内的 $(useradd hacker) 同样被 L1 拒绝")
    void case06_quotedPayload() {
        Outcome outcome = pipeline("echo \"usage on {{X}}%\"", "X", "$(useradd hacker)");
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_UNSAFE(X)");
    }

    @Test
    @DisplayName("#7 安全标点值 PASS 并单引号包裹，L4=LOW（C-1 判据）")
    void case07_safePunctuationValue() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "safe-value_1.2:3@4%5/6");
        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.rendered()).isEqualTo("echo 'safe-value_1.2:3@4%5/6'");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#8 含撇号的值渲染为 'it'\\''s fine'，L4=LOW（C-1 判据）")
    void case08_apostropheEscaping() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "it's fine");
        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.rendered()).isEqualTo("echo 'it'\\''s fine'");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#9 shell 结构字符组合 a;b|c&d>e<f 被 L1 拒绝")
    void case09_structuralChars() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "a;b|c&d>e<f");
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_UNSAFE(MSG)");
    }

    @Test
    @DisplayName("#10 值长度边界：257 字符拒绝，256 字符 PASS")
    void case10_lengthBoundary() {
        assertThat(pipeline("echo {{MSG}}", "MSG", "a".repeat(257)).rejectCode())
                .isEqualTo("PARAM_UNSAFE(MSG)");
        Outcome boundary = pipeline("echo {{MSG}}", "MSG", "a".repeat(256));
        assertThat(boundary.rejected()).isFalse();
        assertThat(boundary.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#11 CJK 值 PASS（白名单纳入 \\p{L}）")
    void case11_cjkValue() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "磁盘");
        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.rendered()).isEqualTo("echo '磁盘'");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#12 空串值在 L1 即被拒绝（不是 PARAM_MISSING）")
    void case12_emptyValue() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "");
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_UNSAFE(MSG)");
    }

    @Test
    @DisplayName("#13 {{MSG:raw}} 未声明 rawParams → PARAM_RAW_UNDECLARED（静态检查先于 L1）")
    void case13_rawUndeclared() {
        Outcome outcome = pipeline("echo {{MSG:raw}}", Map.of("MSG", "$(id)"), Set.of());
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_RAW_UNDECLARED(MSG)");
    }

    @Test
    @DisplayName("#14 声明 raw 后 $(id) 被 L3 命中 SUBSTITUTION_REJECTED")
    void case14_rawSubstitutionRejected() {
        Outcome outcome = pipeline("echo {{MSG:raw}}", Map.of("MSG", "$(id)"), Set.of("MSG"));
        assertThat(outcome.rejectCode()).isEqualTo("SUBSTITUTION_REJECTED");
    }

    @Test
    @DisplayName("#15 find 目录参数 PASS 且 L4=LOW")
    void case15_findDirectoryParam() {
        Outcome outcome = pipeline("find {{DIR}} -name '*.log'", "DIR", "/var/log");
        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.rendered()).isEqualTo("find '/var/log' -name '*.log'");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#16 find -delete 渲染后为 MEDIUM（1.1.3 补丁后，按 riskPolicy 转审批）")
    void case16_findDeleteIsMedium() {
        Outcome outcome = pipeline("find {{DIR}} -delete", "DIR", "/var/log");
        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.rendered()).isEqualTo("find '/var/log' -delete");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    @DisplayName("#17 无参数模板 df -h PASS，L4=LOW")
    void case17_noParamTemplate() {
        Outcome outcome = pipeline("df -h", Map.of(), Set.of());
        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.rendered()).isEqualTo("df -h");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#18 两个含撇号的参数渲染 'x'\\''y'-'z'，L4=LOW（C-1 判据）")
    void case18_twoApostropheParams() {
        Outcome outcome = pipeline("echo {{A}}-{{B}}",
                Map.of("A", "x'y", "B", "z"), Set.of());
        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.rendered()).isEqualTo("echo 'x'\\''y'-'z'");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#19 同一参数三次引用无二次转义")
    void case19_repeatedReference() {
        Outcome outcome = pipeline("echo {{A}}-{{A}}-{{A}}", "A", "x");
        assertThat(outcome.rendered()).isEqualTo("echo 'x'-'x'-'x'");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#20 尾部反斜杠值被 L1 拒绝（\\ 不在白名单）")
    void case20_trailingBackslash() {
        Outcome outcome = pipeline("echo {{MSG}}", "MSG", "abc\\");
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_UNSAFE(MSG)");
    }

    @Test
    @DisplayName("#21 模板自带 ${HOME} 不受影响，L3 区间只覆盖 'ok'（C-2 判据）")
    void case21_templateDollarBraceUntouched() {
        Outcome outcome = pipeline("echo ${HOME} {{MSG}}", "MSG", "ok");
        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.rendered()).isEqualTo("echo ${HOME} 'ok'");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.LOW);

        // 区间只覆盖替换值本身，不等于全串（N2 修正的回归锁）
        RenderResult rendered = ShellSafeRenderer.render("echo ${HOME} {{MSG}}",
                Map.of("MSG", "ok"), Set.of());
        assertThat(rendered.valueRanges()).hasSize(1);
        assertThat(rendered.rendered().substring(
                rendered.valueRanges().get(0).start(), rendered.valueRanges().get(0).end()))
                .isEqualTo("'ok'");
        assertThat(rendered.valueRanges().get(0).start()).isEqualTo(13);
        assertThat(rendered.valueRanges().get(0).end()).isEqualTo(17);
        // L3 放行：模板自带的 ${HOME} 不在任何值区间里
        assertThat(SubstitutionGuard.rejectSubstitution(rendered.rendered(), rendered.valueRanges()))
                .isNull();
    }

    @Test
    @DisplayName("#22 模板自带 $(hostname) 判 LOW 放行（C-5 判据，插值前评估无效是可接受边界）")
    void case22_templateCommandSubstitutionIsLow() {
        Outcome outcome = pipeline("echo $(hostname) {{MSG}}", "MSG", "ok");
        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.rendered()).isEqualTo("echo $(hostname) 'ok'");
        assertThat(outcome.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#23 raw 的正当用途：glob 模式 *.log")
    void case23_rawGlobPattern() {
        // 规格裁定（§2.4.2 L2 行 + §2.4.4 伪代码）：裸值渲染<b>必须</b>带 :raw 槽位后缀，
        // 且该参数已在 rawParams 声明——两处独立确认，少一处都不裸渲染。
        // 设计表格 #23 的模板漏了后缀，按规格实现后渲染为带引号的 '*.log'（仍 PASS / LOW）；
        // 带后缀的形态才是 glob 的正当用法，一并锁定。
        Outcome quoted = pipeline("find {{DIR}} -name {{PATTERN}}",
                Map.of("DIR", "/var/log", "PATTERN", "*.log"), Set.of("PATTERN"));
        assertThat(quoted.rejected()).isFalse();
        assertThat(quoted.rendered()).isEqualTo("find '/var/log' -name '*.log'");
        assertThat(quoted.risk()).isEqualTo(RiskLevel.LOW);

        Outcome raw = pipeline("find {{DIR}} -name {{PATTERN:raw}}",
                Map.of("DIR", "/var/log", "PATTERN", "*.log"), Set.of("PATTERN"));
        assertThat(raw.rejected()).isFalse();
        assertThat(raw.rendered()).isEqualTo("find '/var/log' -name *.log");
        assertThat(raw.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#24 raw 值 x; cat /etc/shadow 被 L3 拒绝（删掉 L3 引擎会判 LOW）")
    void case24_rawStructuralCharRejected() {
        Outcome outcome = pipeline("echo {{MSG:raw}}",
                Map.of("MSG", "x; cat /etc/shadow"), Set.of("MSG"));
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_RAW_UNSAFE");

        // 实证 L3 是唯一防线：同一渲染结果过风险引擎是 LOW
        RenderResult rendered = ShellSafeRenderer.render("echo {{MSG:raw}}",
                Map.of("MSG", "x; cat /etc/shadow"), Set.of("MSG"));
        assertThat(riskEngine.assessScript(rendered.rendered()).getOverallRisk())
                .isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("#25 小写槽位 {{msg}} 被 PARAM_SLOT_MALFORMED 拒绝（防宽松替换兜底）")
    void case25_lowercaseSlotMalformed() {
        Outcome outcome = pipeline("echo {{msg}}", "msg", "x");
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_SLOT_MALFORMED({{msg}})");
    }

    @Test
    @DisplayName("#26 raw 值以 - 开头被拒绝（防标志注入）")
    void case26_rawFlagInjectionRejected() {
        Outcome outcome = pipeline("echo {{MSG:raw}}",
                Map.of("MSG", "-type f -delete"), Set.of("MSG"));
        assertThat(outcome.rejectCode()).isEqualTo("PARAM_RAW_UNSAFE");
    }

    @Test
    @DisplayName("六类 payload 双向验证：全部拒绝，且渲染前不产生任何派发副作用")
    void sixPayloadClasses_allRejected() {
        List<Map<String, String>> payloads = List.of(
                Map.of("MSG", "$(reboot)"),
                Map.of("MSG", "`id`"),
                Map.of("MSG", "'; rm -rf / #"),
                Map.of("MSG", "a\nb"),
                Map.of("MSG", "${IFS}"),
                Map.of("X", "$(useradd hacker)"));
        for (Map<String, String> payload : payloads) {
            String template = payload.containsKey("X") ? "echo \"usage on {{X}}%\"" : "echo {{MSG}}";
            assertThat(pipeline(template, payload, Set.of()).rejected())
                    .as("payload %s 必须被拒绝", payload)
                    .isTrue();
        }
    }
}
