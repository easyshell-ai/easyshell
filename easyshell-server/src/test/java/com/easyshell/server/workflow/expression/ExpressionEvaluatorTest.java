package com.easyshell.server.workflow.expression;

import com.easyshell.server.workflow.expression.ExpressionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 表达式求值器单测（§2.5 五条硬约束 + fail-closed 语义）。
 *
 * <p>H1 import 禁用、H2 JsonPath 只 read()、H3/H5 长度/深度/禁 now()/random()、
 * H4 逃逸语料，全部在这里用测试锁定。</p>
 */
@DisplayName("表达式求值器 — 语法/作用域/类型/fail-closed")
class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator(new ObjectMapper());



    /** 内存变量解析器（测试专用） */
    private static VariableResolver resolver(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return path -> Optional.ofNullable(values.get(path));
    }

    @Nested
    @DisplayName("路径解析与四种命名空间")
    class Paths {

        @Test
        @DisplayName("run.input / nodes.X.output / 别名 / run 元信息")
        void resolvesAllNamespaces() {
            VariableResolver resolver = resolver(
                    "run.input.threshold", 90,
                    "run.workflowName", "磁盘巡检",
                    "run.id", "wfr_1",
                    "nodes.disk.output.maxPcent", 93.2,
                    "nodes.disk.status", "succeeded",
                    "nodes.disk.attempt", 1,
                    "disk.maxPcent", 93.2);

            assertThat(evaluator.evaluate("run.input.threshold > 90", resolver)).isFalse();
            assertThat(evaluator.evaluate("nodes.disk.output.maxPcent > run.input.threshold", resolver)).isTrue();
            assertThat(evaluator.evaluate("nodes.disk.status == 'succeeded'", resolver)).isTrue();
            assertThat(evaluator.evaluate("nodes.disk.attempt == 1", resolver)).isTrue();
            assertThat(evaluator.evaluate("disk.maxPcent > 90", resolver)).isTrue();
            assertThat(evaluator.evaluate("run.workflowName == '磁盘巡检'", resolver)).isTrue();
            assertThat(evaluator.evaluate("run.id == 'wfr_1'", resolver)).isTrue();
        }

        @Test
        @DisplayName("括号路径归一化：nodes['disk'].output.maxPcent")
        void normalizesBracketPaths() {
            VariableResolver resolver = resolver("nodes.disk.output.maxPcent", 93.2);
            assertThat(evaluator.evaluate("nodes['disk'].output.maxPcent > 90", resolver)).isTrue();
            assertThat(evaluator.evaluate("nodes[\"disk\"].output.maxPcent > 90", resolver)).isTrue();
        }

        @Test
        @DisplayName("引用不存在的变量 → REFERENCE_NOT_FOUND（fail-closed，不静默变空串）")
        void missingReferenceFailsClosed() {
            assertThatThrownBy(() -> evaluator.evaluate("nodes.missing.output.x > 1", resolver()))
                    .isInstanceOf(ExpressionException.class)
                    .satisfies(e -> assertThat(((ExpressionException) e).getCode())
                            .isEqualTo(ExpressionException.REFERENCE_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("运算符与显式转换")
    class Operators {

        @Test
        @DisplayName("六种比较 + contains + && / ||")
        void allOperators() {
            VariableResolver resolver = resolver("n", 5, "s", "hello world", "b", true, "l", List.of("a", "b"));
            assertThat(evaluator.evaluate("n == 5", resolver)).isTrue();
            assertThat(evaluator.evaluate("n != 5", resolver)).isFalse();
            assertThat(evaluator.evaluate("n > 4", resolver)).isTrue();
            assertThat(evaluator.evaluate("n < 4", resolver)).isFalse();
            assertThat(evaluator.evaluate("n >= 5", resolver)).isTrue();
            assertThat(evaluator.evaluate("n <= 4", resolver)).isFalse();
            assertThat(evaluator.evaluate("s contains 'world'", resolver)).isTrue();
            assertThat(evaluator.evaluate("s contains 'nope'", resolver)).isFalse();
            assertThat(evaluator.evaluate("l contains 'a'", resolver)).isTrue();
            assertThat(evaluator.evaluate("n > 1 && n < 10", resolver)).isTrue();
            assertThat(evaluator.evaluate("n > 10 || n < 10", resolver)).isTrue();
            assertThat(evaluator.evaluate("n > 10 || n > 20", resolver)).isFalse();
            assertThat(evaluator.evaluate("b", resolver)).isTrue();
            assertThat(evaluator.evaluate("b == true", resolver)).isTrue();
        }

        @Test
        @DisplayName("显式转换①：number 与可解析为 number 的 string 做数值比较")
        void numericStringCoercion() {
            VariableResolver resolver = resolver("pcent", "91.2", "threshold", 90);
            assertThat(evaluator.evaluate("pcent > threshold", resolver)).isTrue();
            assertThat(evaluator.evaluate("pcent == 91.2", resolver)).isTrue();
        }

        @Test
        @DisplayName("类型不匹配的比较抛 TYPE_MISMATCH（不静默按字符串比）")
        void typeMismatchThrows() {
            VariableResolver resolver = resolver("name", "prod", "threshold", 90);
            assertThatThrownBy(() -> evaluator.evaluate("name > threshold", resolver))
                    .isInstanceOf(ExpressionException.class)
                    .satisfies(e -> assertThat(((ExpressionException) e).getCode())
                            .isEqualTo(ExpressionException.TYPE_MISMATCH));
        }

        @Test
        @DisplayName("空条件 = 恒真（边的 condition 缺省）")
        void blankConditionIsTrue() {
            assertThat(evaluator.evaluate(null, resolver())).isTrue();
            assertThat(evaluator.evaluate("   ", resolver())).isTrue();
        }

        @Test
        @DisplayName("单引号字符串支持 '' 转义")
        void singleQuoteEscaping() {
            VariableResolver resolver = resolver("decision", "it's ok");
            assertThat(evaluator.evaluate("decision == 'it''s ok'", resolver)).isTrue();
        }
    }

    @Nested
    @DisplayName("fail-closed：语法与结构")
    class FailClosed {

        @Test
        @DisplayName("禁用 token（Runtime/ProcessBuilder/now()/random()）一律拒绝")
        void forbiddenTokensRejected() {
            for (String expression : List.of(
                    "T(java.lang.Runtime).getRuntime()",
                    "new ProcessBuilder('x')",
                    "run.input.x.getClass()",
                    "now() > 1",
                    "random() > 0.5",
                    "System.getenv('X') == '1'",
                    "run.input.x.getClass().forName('java.lang.Runtime')")) {
                assertThatThrownBy(() -> evaluator.evaluate(expression, resolver("run.input.x", 1)))
                        .as(expression)
                        .isInstanceOf(ExpressionException.class);
            }
        }

        @Test
        @DisplayName("结构字符与函数调用形态在词法层拒绝")
        void lexicalRejections() {
            for (String expression : List.of(
                    "run.input.x == 1;",
                    "run.input.x == (1)",
                    "run.input.x + 1 > 2",
                    "run.input.x * 2 > 2",
                    "run.input.x ? 1 : 2",
                    "run.input.x = 1",
                    "run.input.x == `id`",
                    "run.input.x == 'unterminated",
                    "run.input.x == 1 &&",
                    "&& run.input.x == 1",
                    "run.input.x ==",
                    "run..input.x == 1",
                    "run.input.x == 1 extra")) {
                assertThatThrownBy(() -> evaluator.evaluate(expression, resolver("run.input.x", 1)))
                        .as(expression)
                        .isInstanceOf(ExpressionException.class)
                        .satisfies(e -> assertThat(((ExpressionException) e).getCode())
                                .isIn(ExpressionException.SYNTAX, ExpressionException.FORBIDDEN));
            }
        }

        @Test
        @DisplayName("H3：超过 512 字符拒绝")
        void lengthLimit() {
            String longPath = "run.input." + "a".repeat(600);
            assertThatThrownBy(() -> evaluator.evaluate(longPath + " == 1", resolver()))
                    .isInstanceOf(ExpressionException.class)
                    .satisfies(e -> assertThat(((ExpressionException) e).getCode())
                            .isEqualTo(ExpressionException.LIMIT));
        }

        @Test
        @DisplayName("H5：AST 深度超限拒绝（连续 && 超过 32 层）")
        void depthLimit() {
            StringBuilder expression = new StringBuilder("run.input.x == 1");
            for (int i = 0; i < 40; i++) {
                expression.append(" && run.input.x == 1");
            }
            assertThatThrownBy(() -> evaluator.evaluate(expression.toString(), resolver("run.input.x", 1)))
                    .isInstanceOf(ExpressionException.class)
                    .satisfies(e -> assertThat(((ExpressionException) e).getCode())
                            .isEqualTo(ExpressionException.LIMIT));
        }

        @Test
        @DisplayName("H4：200 条逃逸语料全部 EXPRESSION_SYNTAX_ERROR / FORBIDDEN / LIMIT")
        void escapeCorpusAllRejected() {
            List<String> corpus = corpus();
            assertThat(corpus).hasSizeGreaterThanOrEqualTo(200);
            for (String expression : corpus) {
                assertThatThrownBy(() -> evaluator.evaluate(expression, resolver("run.input.x", 1)))
                        .as(expression)
                        .isInstanceOf(ExpressionException.class)
                        .satisfies(e -> assertThat(((ExpressionException) e).getCode())
                                .isIn(ExpressionException.SYNTAX, ExpressionException.FORBIDDEN,
                                        ExpressionException.LIMIT, ExpressionException.REFERENCE_NOT_FOUND));
            }
        }

        /** 逃逸语料：SpEL/OGNL/JS/SQL/shell 注入形态各若干。
         * 断言口径：全部被拒绝（语法/禁用/超限/引用不存在四类都算拒绝），没有一条被放行 */
        List<String> corpus() {
            List<String> corpus = new ArrayList<>();
            // SpEL / Java 反射
            corpus.add("T(java.lang.Runtime).getRuntime().exec('id')");
            corpus.add("T(javax.script.ScriptEngineManager).new().getEngineByName('js')");
            corpus.add("#this.getClass().forName('java.lang.Runtime')");
            corpus.add("new java.io.File('/etc/passwd')");
            corpus.add("run.input.x.getClass().getClassLoader()");
            corpus.add("''.class.forName('java.lang.Runtime')");
            // OGNL
            corpus.add("@java.lang.Runtime@getRuntime().exec('id')");
            corpus.add("#rt=@java.lang.Runtime@getRuntime(),#rt.exec('id')");
            // JS / 函数调用
            corpus.add("eval('1+1')");
            corpus.add("Function('return 1')()");
            corpus.add("alert(1)");
            corpus.add("run.input.x.constructor");
            corpus.add("run.input.x.valueOf()");
            // 时间/随机（H5 禁）
            corpus.add("now()");
            corpus.add("random()");
            corpus.add("currentTimeMillis()");
            // shell / 命令拼接
            corpus.add("run.input.x == 'a'; rm -rf /");
            corpus.add("run.input.x == 'a' && `id`");
            corpus.add("run.input.x == 'a' || $(reboot)");
            // SQL 注入形态
            corpus.add("run.input.x == 'a' OR 1=1");
            corpus.add("run.input.x == 'a'; DROP TABLE users");
            corpus.add("run.input.x == 'a' UNION SELECT 1");
            // 结构字符
            corpus.add("(run.input.x == 1)");
            corpus.add("run.input.x == 1 ? 2 : 3");
            corpus.add("run.input.x += 1");
            corpus.add("run.input.x == 1\nrun.input.x == 2");
            corpus.add("run.input.x == 'a' # comment");
            corpus.add("run.input.x == 1 -- comment");
            corpus.add("run.input.x == /* c */ 1");
            // 引号不闭合
            corpus.add("run.input.x == 'abc");
            corpus.add("run.input.x == \"abc\"");
            // 未声明命名空间
            corpus.add("secret.token == 'x'");
            corpus.add("vars.x == 1");
            corpus.add("global.x == 1");
            // 数字与字面量畸形
            corpus.add("run.input.x == 1.2.3");
            corpus.add("run.input.x == --1");
            corpus.add("run.input.x == 1e");
            // 批量生成达到 200+ 条：运算符错位、路径畸形、混合结构
            String[] brokenOps = {"=", "===", "!", "<>", "=>", "=~", "~=", "contains(", "&&&", "|||", "&", "|", "^"};
            for (String op : brokenOps) {
                corpus.add("run.input.x " + op + " 1");
                corpus.add("run.input.x " + op);
            }
            String[] brokenPaths = {"run.", ".run.input.x", "run..x", "nodes.", "nodes..output",
                    "nodes.disk.", "nodes.disk.output.", "run.input.", "run.input.x..y", "nodes[disk]",
                    "nodes['disk.output", "nodes[].output", "nodes['']."};
            for (String path : brokenPaths) {
                corpus.add(path + " == 1");
            }
            // 超长表达式（H3）
            corpus.add("run.input." + "x".repeat(600) + " == 1");
            corpus.add("run.input.x == '" + "a".repeat(600) + "'");
            // 组合生成：把上面的 payload 与畸形路径/运算符交叉，凑足 200+ 条
            String[] namespaces = {"secret.", "vars.", "global.", "ctx.", "env.", "system.", "java.",
                    "runtime.", "class.", "t(", "new ", "this.", "#", "@"};
            for (String namespace : namespaces) {
                corpus.add(namespace + "x == 1");
                corpus.add("run.input.x == " + namespace + "y");
                corpus.add(namespace + "x.output.y contains 'a'");
            }
            // 数字/科学计数/畸形字面量与运算符堆叠
            String[] numbers = {"1.2.3", "1e", "1e+", "0x10", "1_000", ".5", "--1", "++1",
                    "1.2.3.4", "1e999", "-", "+", "1..2"};
            for (String number : numbers) {
                corpus.add("run.input.x == " + number);
                corpus.add("run.input.x > " + number);
            }
            // 运算符交叉：只叠比较运算符（叠 && / || 会得到合法表达式，不算逃逸）
            String[] comparisonOps = {"==", "!=", ">", "<", ">=", "<=", "contains"};
            for (String left : comparisonOps) {
                for (String right : comparisonOps) {
                    corpus.add("run.input.x " + left + " 1 " + right + " 2");
                }
            }
            // 括号/引号/花括号形态
            for (String wrapper : new String[]{"(", ")", "[", "]", "{", "}", "\"", "`", "$", "\\"}) {
                corpus.add("run.input.x " + wrapper + " 1");
                corpus.add(wrapper + "run.input.x == 1");
                corpus.add("run.input.x == 1 " + wrapper);
            }
            return corpus;
        }
    }

    @Test
    @DisplayName("引号内的注入 payload 是惰性数据：不求值、不执行、只做字符串比较")
    void inertStringPayloads() {
        VariableResolver resolver = resolver("run.input.x", 1);
        for (String payload : List.of("$(id)", "`id`", "${7*7}", "{{7*7}}", "<%= 7*7 %>",
                "${jndi:ldap://x}", "rm -rf /", "\u0000", "%00", "../../etc/passwd",
                "${IFS}", "> /tmp/x", "\\x00", "?", "\\n", "\\t", "a b c")) {
            // 全部求值为 false（1 != payload），不抛异常也不执行任何东西。
            // contains 的左操作数是 number，按类型不匹配拒绝——同样是"不执行"的安全方向
            assertThat(evaluator.evaluate("run.input.x == '" + payload + "'", resolver))
                    .as(payload)
                    .isFalse();
            assertThatThrownBy(() -> evaluator.evaluate("run.input.x contains '" + payload + "'", resolver))
                    .as(payload)
                    .isInstanceOf(ExpressionException.class);
        }
    }

    @Nested
    @DisplayName("发布期静态分析与引用集合")
    class StaticAnalysis {

        @Test
        @DisplayName("staticAnalyze 返回语法错误清单；合法表达式返回空")
        void staticAnalyze() {
            assertThat(evaluator.staticAnalyze("nodes.disk.output.maxPcent > 90")).isEmpty();
            assertThat(evaluator.staticAnalyze("nodes.disk.output.maxPcent >")).hasSize(1);
            assertThat(evaluator.staticAnalyze("T(java.lang.Runtime)")).hasSize(1);
            assertThat(evaluator.staticAnalyze("")).isEmpty();
            assertThat(evaluator.staticAnalyze(null)).isEmpty();
        }

        @Test
        @DisplayName("referencedNodeKeys 提取被引用的 nodeKey（含括号路径）")
        void referencedNodeKeys() {
            assertThat(evaluator.referencedNodeKeys("nodes['disk'].output.maxPcent > run.input.threshold"))
                    .containsExactly("disk");
            assertThat(evaluator.referencedNodeKeys("nodes.a.output.x == 1 && nodes.b.status == 'succeeded'"))
                    .containsExactly("a", "b");
            assertThat(evaluator.referencedNodeKeys("run.input.threshold > 90")).isEmpty();
        }
    }

    @Nested
    @DisplayName("模板渲染")
    class Templates {

        @Test
        @DisplayName("${path} 插值；null/不存在的引用一律 fail-closed（M1 把 null 当不存在）")
        void templateRendering() {
            List<String> warnings = new ArrayList<>();
            VariableResolver resolver = resolver("nodes.disk.output.maxPcent", 93.2);
            assertThat(evaluator.renderTemplate("使用率 ${nodes.disk.output.maxPcent}%", null, resolver,
                    warnings::add)).isEqualTo("使用率 93.2%");
            // M1 裁定：变量值 null 与"变量不存在"同样按 fail-closed 拒绝——比设计的
            // "渲染为空串再被 L1 拒绝"更早失败、更少静默路径，安全结果一致
            assertThatThrownBy(() -> evaluator.renderTemplate("host=${nodes.disk.output.host}",
                    null, resolver, warnings::add))
                    .isInstanceOf(ExpressionException.class);
        }

        @Test
        @DisplayName("null 字面量渲染为空串并记 warning（ValueType 显式转换②）")
        void nullLiteralRendersEmpty() {
            List<String> warnings = new ArrayList<>();
            assertThat(com.easyshell.server.workflow.model.ValueType.renderToString(null, warnings))
                    .isEqualTo("");
            assertThat(warnings).isNotEmpty();
        }

        @Test
        @DisplayName("{{NAME}} 槽位从 parameters 取值（表达式或字面量）")
        void namedSlots() {
            VariableResolver resolver = resolver("nodes.disk.output.maxPcent", 93.2);
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("PCT", "${nodes.disk.output.maxPcent}");
            parameters.put("HOSTS", "12");
            assertThat(evaluator.renderTemplate("最高 {{PCT}}%，主机 {{HOSTS}} 台", parameters, resolver, null))
                    .isEqualTo("最高 93.2%，主机 12 台");
        }

        @Test
        @DisplayName("模板引用不存在的变量 → 抛异常（fail-closed）")
        void templateMissingReference() {
            assertThatThrownBy(() -> evaluator.renderTemplate("x=${nodes.nope.output.y}", null, resolver(), null))
                    .isInstanceOf(ExpressionException.class);
        }

        @Test
        @DisplayName("未闭合的 ${ 插值被拒绝（不静默当字面量发出）")
        void unclosedInterpolationRejected() {
            VariableResolver resolver = resolver("run.input.x", 1);
            assertThatThrownBy(() -> evaluator.renderTemplate("value=${run.input.x", null, resolver, null))
                    .isInstanceOf(ExpressionException.class);
        }
    }

    @Nested
    @DisplayName("H2：JsonPath 只读")
    class JsonPathReadOnly {

        @Test
        @DisplayName("readJsonPath 只做读取；非法 JSON 按不存在处理")
        void readJsonPath() {
            assertThat(ExpressionEvaluator.readJsonPath("{\"a\":{\"b\":[1,2]}}", "a.b[1]")).isEqualTo(2);
            assertThat(ExpressionEvaluator.readJsonPath("{\"a\":1}", "a.missing")).isNull();
            assertThat(ExpressionEvaluator.readJsonPath("not json", "a")).isNull();
            assertThat(ExpressionEvaluator.readJsonPath(null, "a")).isNull();
        }

        @Test
        @DisplayName("被截断的 preview 文本不是合法 JSON → 按不存在处理（下游 fail-closed）")
        void truncatedPreviewIsMissing() {
            String preview = "{\"a\":1}{\"b\":2}";
            assertThat(ExpressionEvaluator.readJsonPath(preview, "b")).isNull();
        }
    }

    @Test
    @DisplayName("Set/Map 等 exotic 值不参与求值（fail-closed）")
    void exoticValues() {
        VariableResolver resolver = resolver("run.input.tags", Set.of("a"), "run.input.cfg", Map.of("k", 1));
        assertThatThrownBy(() -> evaluator.evaluate("run.input.tags > 1", resolver))
                .isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> evaluator.evaluate("run.input.cfg", resolver))
                .isInstanceOf(ExpressionException.class);
    }
}
