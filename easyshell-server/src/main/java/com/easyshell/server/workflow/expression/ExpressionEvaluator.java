package com.easyshell.server.workflow.expression;

import com.jayway.jsonpath.JsonPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表达式求值器（§2.5：M1 子集，fail-closed）。
 *
 * <p><b>为什么不用 SpEL/OGNL/exp4j</b>：{@code T(java.lang.Runtime).getRuntime().exec(...)}
 * 是现成 RCE，而表达式的输入是工作流作者——一个可被分享/导入的产物。本类是
 * <b>自研白名单递归下降解析器</b>：词法只认路径、数字、单引号字符串、true/false/null
 * 与六个比较运算符加 contains/&&/||，其它一切字符（含 {@code (} {@code )} {@code ;}
 * {@code $} {@code `} {@code +} {@code *}）都在词法层拒绝。</p>
 *
 * <p><b>五条硬约束（B5，全部用测试锁定）</b>：H1 import 禁用（{@link ExpressionImportGuard}
 * + {@code ExpressionImportGuardTest} 源码扫描）；H2 JsonPath 只准 {@code read()}；
 * H3/H5 表达式 ≤512 字符、AST 深度 ≤32、禁 now()/random()；H4 ≥200 条逃逸语料全部
 * {@code EXPRESSION_SYNTAX_ERROR}。</p>
 *
 * <p><b>fail-closed 语义</b>：{@link #evaluate} 只有 true 或抛
 * {@link ExpressionException} 两种返回。边条件求值失败 ⇒ 该边不可通行（下游被跳过），
 * 条件节点求值失败 ⇒ 记 NODE_WARNING 并走 false 分支——两条路径都不会"放行"。</p>
 */
public final class ExpressionEvaluator {

    /** H3：表达式长度上限 */
    public static final int MAX_EXPRESSION_LENGTH = 512;

    /** H5：AST 深度上限 */
    public static final int MAX_AST_DEPTH = 32;

    /** 模板插值：${path} */
    private static final Pattern TEMPLATE_REF = Pattern.compile("\\$\\{([^{}]*)}");

    /** 节点配置模板里的 {{NAME}} 槽位（notify/approval 用；脚本正文的槽位走 ShellSafeRenderer） */
    private static final Pattern NAMED_SLOT = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    /** 括号路径归一化：nodes['disk'].output.x -> nodes.disk.output.x */
    private static final Pattern BRACKET_KEY = Pattern.compile("\\[\\s*'([^']*)'\\s*]|\\[\\s*\"([^\"]*)\"\\s*]");

    private static final Set<String> COMPARISON_OPS = Set.of("==", "!=", ">", "<", ">=", "<=", "contains");

    /** 合法路径：命名空间.段[.段...]，段可以是标识符或带引号的括号键（nodes['disk']） */
    private static final java.util.regex.Pattern VALID_PATH = java.util.regex.Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*|\\['[^']+']|\\[\"[^\"]+\"])*$");

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ExpressionEvaluator(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // 公开 API
    // ==================================================================

    /**
     * 条件求值：true 或抛 {@link ExpressionException}（fail-closed，无第三种返回）。
     */
    public boolean evaluate(String expression, VariableResolver resolver) {
        if (expression == null || expression.isBlank()) {
            // 空条件 = 恒真（§4.1：边的 condition 缺省 = 恒真）；空白串同样视为缺省
            return true;
        }
        List<Token> tokens = tokenize(expression);
        Parser parser = new Parser(tokens, expression);
        Node ast = parser.parse();
        parser.expectEnd();
        return evaluateNode(ast, resolver);
    }

    /**
     * 发布期静态分析：只解析不求值。
     *
     * @return 错误清单；空 list = 语法合法
     */
    public List<String> staticAnalyze(String expression) {
        List<String> errors = new ArrayList<>();
        if (expression == null || expression.isBlank()) {
            return errors;
        }
        try {
            ExpressionImportGuard.check(expression);
            List<Token> tokens = tokenize(expression);
            Parser parser = new Parser(tokens, expression);
            parser.parse();
            parser.expectEnd();
        } catch (ExpressionException e) {
            errors.add(e.getCode() + ": " + e.getMessage());
        }
        return errors;
    }

    /**
     * 模板渲染：把 {@code ${path}} 替换为解析值（§2.5 template = "${" path "}" | literal）。
     *
     * <p>用于 notify messageTemplate / approval detailTemplate 等非 shell 上下文。
     * 引用不存在 ⇒ 抛 {@link ExpressionException}（fail-closed，不静默留空）。</p>
     *
     * @param parameters 可选的 {@code {{NAME}}} 槽位取值（值为表达式或字面量）
     * @param warnings   非 null 时收集 warning（如 null 值渲染为空串）
     */
    public String renderTemplate(String template, java.util.Map<String, String> parameters,
                                 VariableResolver resolver, java.util.function.Consumer<String> warnings) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        // ① {{NAME}} 槽位：值来自 parameters（表达式或字面量）
        String step1 = template;
        if (parameters != null && !parameters.isEmpty()) {
            Matcher slot = NAMED_SLOT.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (slot.find()) {
                String name = slot.group(1);
                String raw = parameters.get(name);
                if (raw == null) {
                    throw new ExpressionException(ExpressionException.REFERENCE_NOT_FOUND,
                            "模板参数 {{" + name + "}} 未提供取值");
                }
                slot.appendReplacement(sb, Matcher.quoteReplacement(
                        resolveTemplateValue(raw, resolver, warnings)));
            }
            slot.appendTail(sb);
            step1 = sb.toString();
        }
        // ② ${path} 表达式引用
        Matcher ref = TEMPLATE_REF.matcher(step1);
        StringBuilder sb = new StringBuilder();
        while (ref.find()) {
            String path = ref.group(1).trim();
            Object value = resolvePath(path, resolver);
            ref.appendReplacement(sb, Matcher.quoteReplacement(
                    renderWithWarnings(value, warnings)));
        }
        ref.appendTail(sb);
        String rendered = sb.toString();
        // ③ fail-closed：残留的 "${" 说明有未闭合的插值，绝不静默当字面量发出
        if (rendered.contains("${")) {
            throw new ExpressionException(ExpressionException.SYNTAX,
                    "模板含未闭合的 ${ 插值: " + rendered);
        }
        return rendered;
    }

    /** 单值渲染：表达式或字面量 → 字符串 */
    public String resolveTemplateValue(String raw, VariableResolver resolver,
                                       java.util.function.Consumer<String> warnings) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            Object value = resolvePath(trimmed.substring(2, trimmed.length() - 1).trim(), resolver);
            return renderWithWarnings(value, warnings);
        }
        return raw;
    }

    /**
     * 表达式里引用到的 nodeKey 集合（发布期校验"引用集合 ⊆ 祖先集合"，§11.1 ERROR⑦）。
     */
    public Set<String> referencedNodeKeys(String expression) {
        Set<String> keys = new TreeSet<>();
        if (expression == null || expression.isBlank()) {
            return keys;
        }
        for (Token token : tokenize(expression)) {
            if (token.kind == Kind.PATH) {
                collectNodeKeys(token.text, keys);
            }
        }
        return keys;
    }

    private void collectNodeKeys(String path, Set<String> keys) {
        String normalized = normalizePath(path);
        String[] segments = normalized.split("\\.");
        if (segments.length >= 2 && "nodes".equals(segments[0])) {
            keys.add(segments[1]);
        }
    }

    // ==================================================================
    // 词法
    // ==================================================================

    private enum Kind {PATH, NUMBER, STRING, BOOL, NULL, OP, EOF}

    private record Token(Kind kind, String text, int position) {
    }

    private List<Token> tokenize(String expression) {
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new ExpressionException(ExpressionException.LIMIT,
                    "表达式长度 " + expression.length() + " 超过上限 " + MAX_EXPRESSION_LENGTH);
        }
        ExpressionImportGuard.check(expression);
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // 单引号字符串（'' 转义一个单引号，与 ShellSafeRenderer.singleQuote 的解构对称）
            if (c == '\'') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (j < n) {
                    char d = expression.charAt(j);
                    if (d == '\'') {
                        if (j + 1 < n && expression.charAt(j + 1) == '\'') {
                            sb.append('\'');
                            j += 2;
                            continue;
                        }
                        closed = true;
                        j++;
                        break;
                    }
                    sb.append(d);
                    j++;
                }
                if (!closed) {
                    throw new ExpressionException(ExpressionException.SYNTAX,
                            "字符串字面量未闭合（位置 " + i + "）");
                }
                tokens.add(new Token(Kind.STRING, sb.toString(), i));
                i = j;
                continue;
            }
            // 数字
            if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(expression.charAt(i + 1))
                    && !tokens.isEmpty() && isOperator(tokens.get(tokens.size() - 1)))) {
                int j = i;
                if (expression.charAt(j) == '-') {
                    j++;
                }
                while (j < n && (Character.isDigit(expression.charAt(j)) || expression.charAt(j) == '.')) {
                    j++;
                }
                String text = expression.substring(i, j);
                try {
                    Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw new ExpressionException(ExpressionException.SYNTAX, "数字字面量非法: " + text);
                }
                tokens.add(new Token(Kind.NUMBER, text, i));
                i = j;
                continue;
            }
            // 路径 / 关键字
            if (isPathStart(c)) {
                int j = i;
                while (j < n && isPathChar(expression.charAt(j))) {
                    j++;
                }
                String text = expression.substring(i, j);
                Kind kind = Kind.PATH;
                if ("true".equals(text) || "false".equals(text)) {
                    kind = Kind.BOOL;
                } else if ("null".equals(text)) {
                    kind = Kind.NULL;
                } else if (COMPARISON_OPS.contains(text)) {
                    kind = Kind.OP;
                } else if (!VALID_PATH.matcher(text).matches()) {
                    // 空段（run..x）、未闭合括号（nodes['a）、无引号键（nodes[a]）都是语法错误，
                    // 不能让它们活到求值期变成"变量不存在"——fail-closed 要在最早的地方发生
                    throw new ExpressionException(ExpressionException.SYNTAX,
                            "路径形态非法: " + text + "（位置 " + i + "）");
                }
                tokens.add(new Token(kind, text, i));
                i = j;
                continue;
            }
            // 运算符（必须在使用路径字符扫描之前判定 contains/&&/||，故放在路径分支后按顺序尝试）
            String op = matchOperator(expression, i);
            if (op != null) {
                tokens.add(new Token(Kind.OP, op, i));
                i += op.length();
                continue;
            }
            throw new ExpressionException(ExpressionException.SYNTAX,
                    "非法字符 '" + c + "'（位置 " + i + "）：表达式只支持路径/数字/字符串/比较运算符");
        }
        tokens.add(new Token(Kind.EOF, "<eof>", n));
        return tokens;
    }

    private static boolean isOperator(Token token) {
        return token.kind == Kind.OP && COMPARISON_OPS.contains(token.text);
    }

    private static boolean isPathStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '[';
    }

    private static boolean isPathChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '[' || c == ']'
                || c == '\'' || c == '"';
    }

    /** 按长度优先匹配 && || == != >= <= > < contains */
    private static String matchOperator(String s, int i) {
        for (String op : new String[]{"==", "!=", ">=", "<=", "&&", "||", ">", "<"}) {
            if (s.startsWith(op, i)) {
                return op;
            }
        }
        if (s.startsWith("contains", i)) {
            return "contains";
        }
        return null;
    }

    // ==================================================================
    // 语法（递归下降）
    // ==================================================================

    private static final class Parser {
        private final List<Token> tokens;
        private final String source;
        private int pos;

        Parser(List<Token> tokens, String source) {
            this.tokens = tokens;
            this.source = source;
        }

        Node parse() {
            return parseOr(0);
        }

        private Node parseOr(int depth) {
            enter(depth);
            Node left = parseAnd(depth + 1);
            while (peekOp("||")) {
                next();
                Node right = parseAnd(depth + 1);
                left = new OrNode(left, right);
            }
            return left;
        }

        private Node parseAnd(int depth) {
            enter(depth);
            Node left = parseComparison(depth + 1);
            while (peekOp("&&")) {
                next();
                Node right = parseComparison(depth + 1);
                left = new AndNode(left, right);
            }
            return left;
        }

        private Node parseComparison(int depth) {
            enter(depth);
            Operand left = parseOperand(depth + 1);
            if (peek().kind == Kind.OP && COMPARISON_OPS.contains(peek().text)) {
                String op = next().text;
                Operand right = parseOperand(depth + 1);
                return new ComparisonNode(left, op, right);
            }
            return new TruthNode(left);
        }

        private Operand parseOperand(int depth) {
            enter(depth);
            Token token = next();
            return switch (token.kind) {
                case PATH -> new PathOperand(token.text);
                case NUMBER -> new LiteralOperand(Double.parseDouble(token.text));
                case STRING -> new LiteralOperand(token.text);
                case BOOL -> new LiteralOperand(Boolean.parseBoolean(token.text));
                case NULL -> new LiteralOperand(null);
                default -> throw new ExpressionException(ExpressionException.SYNTAX,
                        "期望操作数，实际遇到 '" + token.text + "'（位置 " + token.position + "）");
            };
        }

        private void enter(int depth) {
            if (depth > MAX_AST_DEPTH) {
                throw new ExpressionException(ExpressionException.LIMIT,
                        "表达式 AST 深度超过上限 " + MAX_AST_DEPTH);
            }
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            Token token = tokens.get(pos);
            if (token.kind != Kind.EOF) {
                pos++;
            }
            return token;
        }

        private boolean peekOp(String op) {
            Token token = peek();
            return token.kind == Kind.OP && op.equals(token.text);
        }

        private void expectEnd() {
            Token token = peek();
            if (token.kind != Kind.EOF) {
                throw new ExpressionException(ExpressionException.SYNTAX,
                        "表达式末尾存在多余内容 '" + token.text + "'（位置 " + token.position + "），来源: " + source);
            }
        }
    }

    // ==================================================================
    // AST
    // ==================================================================

    private interface Node {
    }

    private record OrNode(Node left, Node right) implements Node {
    }

    private record AndNode(Node left, Node right) implements Node {
    }

    private record ComparisonNode(Operand left, String op, Operand right) implements Node {
    }

    private record TruthNode(Operand operand) implements Node {
    }

    private interface Operand {
    }

    private record LiteralOperand(Object value) implements Operand {
    }

    private record PathOperand(String rawPath) implements Operand {
    }

    // ==================================================================
    // 求值
    // ==================================================================

    private boolean evaluateNode(Node node, VariableResolver resolver) {
        if (node instanceof OrNode or) {
            return evaluateNode(or.left(), resolver) || evaluateNode(or.right(), resolver);
        }
        if (node instanceof AndNode and) {
            return evaluateNode(and.left(), resolver) && evaluateNode(and.right(), resolver);
        }
        if (node instanceof ComparisonNode cmp) {
            return evaluateComparison(cmp, resolver);
        }
        if (node instanceof TruthNode truth) {
            return truthiness(value(truth.operand(), resolver));
        }
        throw new ExpressionException(ExpressionException.SYNTAX, "无法求值的表达式节点");
    }

    private boolean evaluateComparison(ComparisonNode cmp, VariableResolver resolver) {
        Object left = value(cmp.left(), resolver);
        Object right = value(cmp.right(), resolver);
        String op = cmp.op();
        return switch (op) {
            case "==" -> equals(left, right);
            case "!=" -> !equals(left, right);
            case ">", "<", ">=", "<=" -> compare(left, right, op);
            case "contains" -> contains(left, right);
            default -> throw new ExpressionException(ExpressionException.SYNTAX, "未知运算符 " + op);
        };
    }

    /** 取操作数的值：路径不存在 ⇒ REFERENCE_NOT_FOUND（fail-closed） */
    private Object value(Operand operand, VariableResolver resolver) {
        if (operand instanceof LiteralOperand literal) {
            return literal.value();
        }
        if (operand instanceof PathOperand path) {
            return resolvePath(path.rawPath(), resolver);
        }
        throw new ExpressionException(ExpressionException.SYNTAX, "无法求值的操作数");
    }

    private Object resolvePath(String rawPath, VariableResolver resolver) {
        String path = normalizePath(rawPath);
        Optional<Object> resolved = resolver.resolve(path);
        if (resolved.isEmpty()) {
            throw new ExpressionException(ExpressionException.REFERENCE_NOT_FOUND,
                    "引用的变量不存在: " + path);
        }
        return resolved.get();
    }

    /** 括号路径归一化 + 去掉首尾空白 */
    public static String normalizePath(String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        Matcher m = BRACKET_KEY.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("." + (m.group(1) != null ? m.group(1) : m.group(2))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean equals(Object left, Object right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        if (left instanceof Number ln && right instanceof Number rn) {
            return Double.compare(ln.doubleValue(), rn.doubleValue()) == 0;
        }
        // 显式转换①：一侧 number、另一侧可解析为 number 的 string → 数值比较
        Double ln = com.easyshell.server.workflow.model.ValueType.asDouble(left);
        Double rn = com.easyshell.server.workflow.model.ValueType.asDouble(right);
        if (ln != null && rn != null) {
            return Double.compare(ln, rn) == 0;
        }
        if (left instanceof String ls && right instanceof String rs) {
            return ls.equals(rs);
        }
        if (left instanceof Boolean lb && right instanceof Boolean rb) {
            return lb.equals(rb);
        }
        return false;
    }

    private boolean compare(Object left, Object right, String op) {
        Double ln = com.easyshell.server.workflow.model.ValueType.asDouble(left);
        Double rn = com.easyshell.server.workflow.model.ValueType.asDouble(right);
        if (ln == null || rn == null) {
            throw new ExpressionException(ExpressionException.TYPE_MISMATCH,
                    "运算符 " + op + " 需要可比较的操作数，实际 "
                            + com.easyshell.server.workflow.model.ValueType.describe(left) + " 与 "
                            + com.easyshell.server.workflow.model.ValueType.describe(right));
        }
        int cmp = Double.compare(ln, rn);
        return switch (op) {
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            default -> throw new ExpressionException(ExpressionException.SYNTAX, "未知比较运算符 " + op);
        };
    }

    private boolean contains(Object left, Object right) {
        if (left instanceof String ls && right instanceof String rs) {
            return ls.contains(rs);
        }
        if (left instanceof List<?> list) {
            return list.contains(right);
        }
        throw new ExpressionException(ExpressionException.TYPE_MISMATCH,
                "contains 需要字符串或数组左操作数，实际 "
                        + com.easyshell.server.workflow.model.ValueType.describe(left));
    }

    /** 裸操作数的真值：Boolean 原样；"true"/"false" 字符串严格解析；number 非零为真 */
    private boolean truthiness(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(s.trim())) {
                return false;
            }
            throw new ExpressionException(ExpressionException.TYPE_MISMATCH,
                    "条件表达式需要布尔值，实际 string (\"" + s + "\")");
        }
        Double d = com.easyshell.server.workflow.model.ValueType.asDouble(value);
        if (d != null) {
            return d != 0d;
        }
        throw new ExpressionException(ExpressionException.TYPE_MISMATCH,
                "条件表达式需要布尔值，实际 "
                        + com.easyshell.server.workflow.model.ValueType.describe(value));
    }

    // ==================================================================
    // JsonPath（H2：只准 read()）
    // ==================================================================

    /**
     * 在 JSON 文本上按路径读取（H2 硬约束：只使用 JsonPath 的 {@code read()}）。
     *
     * @return 读到的值；路径不存在或文本不是合法 JSON 时返回 {@code null}
     */
    public static Object readJsonPath(String json, String path) {
        if (json == null || json.isEmpty() || path == null || path.isEmpty()) {
            return null;
        }
        String expression = path.startsWith("$") ? path : "$." + path;
        try {
            // H2 硬约束：只使用 JsonPath 的 read()（不做写、不做 compile 后的可变操作）
            return JsonPath.read(json, expression);
        } catch (Exception e) {
            // 上游输出不是合法 JSON（如被截断的 preview）：按"不存在"处理，由调用方 fail-closed
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Consumer<String> 告警出口与 List<String> 收集器之间的适配（null 安全） */
    private static String renderWithWarnings(Object value, java.util.function.Consumer<String> warnings) {
        if (warnings == null) {
            return com.easyshell.server.workflow.model.ValueType.renderToString(value, null);
        }
        List<String> collected = new ArrayList<>();
        String rendered = com.easyshell.server.workflow.model.ValueType.renderToString(value, collected);
        collected.forEach(warnings);
        return rendered;
    }

    /** 供测试：把 JSON 文本解析为对象（null = 非法 JSON） */
    public static Object parseJson(String json) {
        try {
            return JSON_MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
