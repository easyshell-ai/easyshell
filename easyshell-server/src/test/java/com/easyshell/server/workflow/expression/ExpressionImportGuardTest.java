package com.easyshell.server.workflow.expression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B5 H1：表达式 import 禁用闸的两半——运行时 token 黑名单 + 源码扫描。
 *
 * <p>源码扫描那一半是"未来有人想加一条 {@code Runtime.getRuntime().exec()} 到解析器里"
 * 的报警器：CI 红即构建失败。与 {@code DispatchAuthorityArchTest} 同一思路。</p>
 */
@DisplayName("表达式 import 禁用闸（B5 H1）")
class ExpressionImportGuardTest {

    /** workflow/expression 包禁止出现的 import / 调用 */
    private static final Set<String> FORBIDDEN_SOURCE_TOKENS = Set.of(
            "import java.lang.Runtime",
            "import java.lang.ProcessBuilder",
            "import java.lang.reflect",
            "import java.io.",
            "import java.nio.file.",
            "System.getenv",
            "System.getProperty",
            "Runtime.getRuntime",
            "new ProcessBuilder",
            "Class.forName",
            "ScriptEngineManager",
            "javax.script");

    @Test
    @DisplayName("运行时：禁用 token 一律抛 EXPRESSION_FORBIDDEN_TOKEN")
    void forbiddenTokensThrow() {
        for (String token : List.of("runtime", "processbuilder", "getclass", "forname", "java.lang",
                "java.io", "java.nio", "system.getenv", "reflect.", "now(", "random(", "eval(", "new ")) {
            assertThatThrownBy(() -> ExpressionImportGuard.check("run.input.x == '" + token + "'"))
                    .as(token)
                    .isInstanceOf(ExpressionException.class)
                    .satisfies(e -> assertThat(((ExpressionException) e).getCode())
                            .isEqualTo(ExpressionException.FORBIDDEN));
        }
    }

    @Test
    @DisplayName("运行时：正常表达式不误伤")
    void legitimateExpressionsPass() {
        ExpressionImportGuard.check("nodes['disk'].output.maxPcent > run.input.threshold");
        ExpressionImportGuard.check("nodes.check.output.hosts contains 'web'");
        ExpressionImportGuard.check("run.input.env == 'prod' && run.input.threshold >= 90");
        ExpressionImportGuard.check(null);
        ExpressionImportGuard.check("");
    }

    @Test
    @DisplayName("源码扫描：workflow/expression 包无禁用 import 与反射/进程调用")
    void expressionPackageHasNoForbiddenImports() throws IOException {
        Path root = Path.of("src/main/java/com/easyshell/server/workflow/expression");
        Set<String> offenders = new TreeSet<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path);
                for (String token : FORBIDDEN_SOURCE_TOKENS) {
                    if (content.contains(token)) {
                        offenders.add(path.getFileName() + " 含 " + token);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("禁用 token 表本身不含空串（防止 contains(\"\") 恒真把全部表达式误杀）")
    void tokenTableHasNoBlank() {
        assertThat(ExpressionImportGuard.forbiddenTokens()).noneMatch(String::isBlank);
    }
}
