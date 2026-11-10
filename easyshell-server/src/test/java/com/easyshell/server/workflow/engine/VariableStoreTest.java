package com.easyshell.server.workflow.engine;

import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.expression.VariableResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VariableStore 单测（§2.1 三条通道 + §2.10 分流规则 + §2.2 作用域）。
 *
 * <p>M1 只存在标量通道；artifact 引用通道降级为 preview（{@code workflow_artifact}
 * 表在 M2，X15）；secret 通道明确不做（X13）。</p>
 */
@DisplayName("VariableStore — 三条通道与作用域")
class VariableStoreTest {

    private VariableStore store;

    @BeforeEach
    void setUp() {
        store = new VariableStore();
    }

    @Nested
    @DisplayName("标量通道")
    class ScalarChannel {

        @Test
        @DisplayName("run.input.* 写入与读取")
        void runInput() {
            store.putInput("threshold", 90);
            assertThat(store.resolve("run.input.threshold").orElse(null)).isEqualTo(90);
            assertThat(store.resolve("run.input.missing")).isEmpty();
        }

        @Test
        @DisplayName("节点输出与 outputVar 别名始终等价（MF-11）")
        void outputVarAlias() {
            store.putNodeOutput("disk", "diskInfo", Map.of("maxPcent", 93.2));
            assertThat(store.resolve("nodes.disk.output")).contains(Map.of("maxPcent", 93.2));
            assertThat(store.resolve("diskInfo.maxPcent")).contains(93.2);
            assertThat(store.resolve("nodes.disk.output.maxPcent")).contains(93.2);
            // 别名与 nodes 通道读到同一个值
            assertThat(store.resolve("diskInfo.maxPcent"))
                    .isEqualTo(store.resolve("nodes.disk.output.maxPcent"));
        }

        @Test
        @DisplayName("JsonPath 深路径读取（含数组下标）")
        void jsonPathReads() {
            store.putNodeOutput("check", "check", Map.of("hosts", List.of("a", "b"), "top", Map.of("dir", "/var")));
            assertThat(store.resolve("nodes.check.output.hosts[1]")).contains("b");
            assertThat(store.resolve("nodes.check.output.top.dir")).contains("/var");
            assertThat(store.resolve("nodes.check.output.absent")).isEmpty();
        }

        @Test
        @DisplayName("节点元信息（status/durationMs/attempt）")
        void nodeMetadata() {
            store.putNodeStatus("disk", "succeeded", 1200L, 2);
            assertThat(store.resolve("nodes.disk.status")).contains("succeeded");
            assertThat(store.resolve("nodes.disk.durationMs")).contains(1200L);
            assertThat(store.resolve("nodes.disk.attempt")).contains(2);
        }

        @Test
        @DisplayName("run 元信息（id/workflowName/startedBy/triggerType）")
        void runMetadata() {
            store.putRunMetadata("run.id", "wfr_1");
            store.putRunMetadata("run.triggerType", "manual");
            assertThat(store.resolve("run.id")).contains("wfr_1");
            assertThat(store.resolve("run.triggerType")).contains("manual");
            assertThat(store.resolve("run.unknown")).isEmpty();
        }

        @Test
        @DisplayName("括号路径归一化后也能解析")
        void bracketPathNormalization() {
            store.putNodeOutput("disk", "disk", Map.of("maxPcent", 93.2));
            assertThat(store.resolve("nodes['disk'].output.maxPcent")).contains(93.2);
        }

        @Test
        @DisplayName("没有全局变量通道：vars.* / 任意裸名都解析不到")
        void noGlobalChannel() {
            store.putInput("threshold", 90);
            assertThat(store.resolve("vars.threshold")).isEmpty();
            assertThat(store.resolve("threshold")).isEmpty();
            assertThat(store.resolve("global.threshold")).isEmpty();
        }
    }

    @Nested
    @DisplayName("artifact 引用通道的 M1 降级形态（preview + truncated）")
    class ArtifactFallback {

        @Test
        @DisplayName("≤8192B 全文进标量通道，truncated=false")
        void smallOutputInline() {
            String payload = "x".repeat(8000);
            VariableStore.StoredOutput output = store.putNodeOutput("raw", "raw", payload);
            assertThat(output.truncated()).isFalse();
            assertThat(output.sizeBytes()).isEqualTo(8000);
            assertThat(output.sha256()).isNotBlank();
            assertThat(output.previewSha256()).isNull();
            assertThat(store.resolve("nodes.raw.output")).contains(payload);
            assertThat(store.drainWarnings()).isEmpty();
        }

        @Test
        @DisplayName("8192B < size ≤ 262144B：只放 preview + truncated=true + warning")
        void largeOutputPreview() {
            String payload = "y".repeat(20000);
            VariableStore.StoredOutput output = store.putNodeOutput("big", "big", payload);
            assertThat(output.truncated()).isTrue();
            assertThat(output.sizeBytes()).isEqualTo(20000);
            // preview = 前 6144 + 尾 2048
            assertThat(output.json()).hasSize(8192);
            assertThat(output.json()).startsWith("yyy").endsWith("yyy");
            assertThat(output.previewSha256()).isNotBlank();
            assertThat(store.drainWarnings()).anySatisfy(w -> assertThat(w).contains("超过内联阈值"));
        }

        @Test
        @DisplayName(">262144B：只放 preview + 硬上限 warning")
        void hugeOutputPreviewOnly() {
            String payload = "z".repeat(300000);
            VariableStore.StoredOutput output = store.putNodeOutput("huge", "huge", payload);
            assertThat(output.truncated()).isTrue();
            assertThat(output.sizeBytes()).isEqualTo(300000);
            assertThat(output.json()).hasSize(8192);
            assertThat(store.drainWarnings()).anySatisfy(w -> assertThat(w).contains("超过单值硬上限"));
        }

        @Test
        @DisplayName("截断后下游永远拿不到完整数据（要么读不到，要么只读到 preview 片段）")
        void truncatedPreviewNeverGivesFullData() {
            String payload = "{\"a\":\"" + "1".repeat(20000) + "\"}";
            store.putNodeOutput("big", "big", payload);
            assertThat(store.nodeOutput("big").truncated()).isTrue();
            assertThat(store.nodeOutput("big").sizeBytes())
                    .isEqualTo(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            Optional<Object> value = store.resolve("nodes.big.output.a");
            if (value.isPresent()) {
                assertThat(String.valueOf(value.get()).length()).isLessThan(20000);
            }
        }
    }

    @Nested
    @DisplayName("并发写与快照")
    class ConcurrencyAndSnapshot {

        @Test
        @DisplayName("同键并发写：后写者覆盖 + warning（M1 无 fan-in 合并策略）")
        void concurrentSameKeyWrite() {
            store.putNodeOutput("a", "shared", "first");
            store.putNodeOutput("b", "shared", "second");
            assertThat(store.resolve("shared")).contains("second");
            assertThat(store.drainWarnings()).anySatisfy(w -> assertThat(w).contains("并发写者"));
        }

        @Test
        @DisplayName("波末快照：全量标量 + 512KB 上限截断")
        void snapshot() {
            store.putRunMetadata("run.id", "wfr_1");
            store.putInput("threshold", 90);
            store.putNodeOutput("disk", "disk", Map.of("maxPcent", 93.2));

            VariableStore.Snapshot snapshot = store.snapshot();
            assertThat(snapshot.truncated()).isFalse();
            assertThat(snapshot.json()).contains("run.input.threshold");
            assertThat(snapshot.json()).contains("nodes.disk.output");
            assertThat(snapshot.json()).contains("run.id");

            // 超限：保留最近 20 个标量 + truncated=1
            VariableStore huge = new VariableStore();
            for (int i = 0; i < 40; i++) {
                huge.putInput("k" + i, "v".repeat(20000));
            }
            VariableStore.Snapshot truncated = huge.snapshot();
            assertThat(truncated.truncated()).isTrue();
            assertThat(truncated.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo((int) VariableStore.SNAPSHOT_LIMIT);
        }

        @Test
        @DisplayName("putInput / putRunMetadata 对 null 安全（ConcurrentHashMap 不接受 null）")
        void nullWritesAreIgnored() {
            store.putInput("optional", null);
            store.putRunMetadata("run.workflowName", null);
            assertThat(store.resolve("run.input.optional")).isEmpty();
            assertThat(store.resolve("run.workflowName")).isEmpty();
        }

        @Test
        @DisplayName("createSnapshot：节点只能读其拓扑祖先的输出（§2.2 ③）")
        void snapshotIsolation() {
            store.putInput("threshold", 90);
            store.putNodeOutput("upstream", "up", Map.of("x", 1));
            store.putNodeOutput("sibling", "sib", Map.of("y", 2));
            store.putNodeOutput("self", null, Map.of("z", 3));
            store.putNodeStatus("upstream", "succeeded", 10L, 1);

            VariableStore view = store.createSnapshot(Set.of("upstream"));
            assertThat(view.resolve("run.input.threshold").orElse(null)).isEqualTo(90);
            assertThat(view.resolve("nodes.upstream.output.x")).contains(1);
            assertThat(view.resolve("up.x")).contains(1);
            // 非祖先与自己都读不到
            assertThat(view.resolve("nodes.sibling.output.y")).isEmpty();
            assertThat(view.resolve("sib.y")).isEmpty();
            assertThat(view.resolve("nodes.self.output.z")).isEmpty();
            // 元信息仍然可见（状态查询不是数据通道）
            assertThat(view.resolve("nodes.upstream.status")).contains("succeeded");
        }
    }

    @Nested
    @DisplayName("与表达式的联动")
    class WithExpressions {

        @Test
        @DisplayName("作为 VariableResolver 实现：resolve 返回 empty 时求值方 fail-closed")
        void resolverContract() {
            store.putInput("threshold", 90);
            VariableResolver resolver = store;
            assertThat(resolver.resolve("run.input.threshold")).isPresent();
            assertThat(resolver.resolve("nodes.nope.output")).isEmpty();
            // 空/ null 路径
            assertThat(resolver.resolve(null)).isEmpty();
            assertThat(resolver.resolve("")).isEmpty();
            assertThat(resolver.resolve("run")).isEmpty();
            assertThat(resolver.resolve("run.input")).isEmpty();
            assertThat(resolver.resolve("nodes.disk")).isEmpty();
            assertThat(resolver.resolve("nodes.disk.unknown")).isEmpty();
        }
    }

    @Test
    @DisplayName("warning 上限 32 条（防止异常循环把 memory 打爆）")
    void warningCap() {
        for (int i = 0; i < 100; i++) {
            store.addWarning("w" + i);
        }
        List<String> warnings = store.drainWarnings();
        assertThat(warnings).hasSize(32);
        assertThat(store.drainWarnings()).isEmpty();
    }

    @Test
    @DisplayName("putNodeOutput 对 null 值也安全（序列化为空对象）")
    void nullOutputSafe() {
        VariableStore.StoredOutput output = store.putNodeOutput("n", null, null);
        assertThat(output.json()).isEqualTo("{}");
        assertThat(output.truncated()).isFalse();
    }
}
