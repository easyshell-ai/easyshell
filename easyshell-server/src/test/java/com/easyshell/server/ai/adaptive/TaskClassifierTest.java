package com.easyshell.server.ai.adaptive;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.service.ChatModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskClassifier — rule-based task classification")
class TaskClassifierTest {

    @Mock
    private AgenticConfigService configService;
    @Mock
    private ChatModelFactory chatModelFactory;

    private TaskClassifier classifier;

    @BeforeEach
    void setUp() {
        // Use lenient stubs — not all code paths hit both config keys
        lenient().when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(true);
        lenient().when(configService.getBoolean(eq("ai.adaptive.classifier-use-llm"), anyBoolean())).thenReturn(false);
        classifier = new TaskClassifier(configService, chatModelFactory);
    }

    private void disableAdaptive() {
        // Override the lenient default for disabled tests
        lenient().when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(false);
    }

    @Test
    void disabledAdaptive_returnsGeneral() {
        disableAdaptive();
        assertThat(classifier.classify("查看所有主机")).isEqualTo(TaskType.GENERAL);
    }

    @Test
    void nullMessage_returnsGeneral() {
        assertThat(classifier.classify(null)).isEqualTo(TaskType.GENERAL);
    }

    @Test
    void blankMessage_returnsGeneral() {
        assertThat(classifier.classify("   ")).isEqualTo(TaskType.GENERAL);
    }

    @Nested
    @DisplayName("Chinese patterns")
    class ChinesePatterns {
        @Test
        void query_chinese() {
            assertThat(classifier.classify("查看所有在线主机")).isEqualTo(TaskType.QUERY);
            assertThat(classifier.classify("列出所有脚本")).isEqualTo(TaskType.QUERY);
            assertThat(classifier.classify("显示集群状态")).isEqualTo(TaskType.QUERY);
        }

        @Test
        void execute_chinese() {
            assertThat(classifier.classify("执行脚本在主机上")).isEqualTo(TaskType.EXECUTE);
            assertThat(classifier.classify("重启nginx服务")).isEqualTo(TaskType.EXECUTE);
            assertThat(classifier.classify("安装docker")).isEqualTo(TaskType.EXECUTE);
        }

        @Test
        void troubleshoot_chinese() {
            assertThat(classifier.classify("排查为什么服务挂了")).isEqualTo(TaskType.TROUBLESHOOT);
            assertThat(classifier.classify("诊断超时问题")).isEqualTo(TaskType.TROUBLESHOOT);
            assertThat(classifier.classify("服务器宕机了")).isEqualTo(TaskType.TROUBLESHOOT);
        }

        @Test
        void deploy_chinese() {
            assertThat(classifier.classify("部署服务到生产环境")).isEqualTo(TaskType.DEPLOY);
            assertThat(classifier.classify("回滚到上个发布")).isEqualTo(TaskType.DEPLOY);
            assertThat(classifier.classify("升级mysql")).isEqualTo(TaskType.DEPLOY);
        }

        @Test
        void monitor_chinese() {
            assertThat(classifier.classify("监控CPU使用率")).isEqualTo(TaskType.MONITOR);
            assertThat(classifier.classify("告警阈值设置")).isEqualTo(TaskType.MONITOR);
            assertThat(classifier.classify("磁盘负载趋势分析")).isEqualTo(TaskType.MONITOR);
        }
    }

    @Nested
    @DisplayName("English patterns")
    class EnglishPatterns {
        @Test
        void query_english() {
            assertThat(classifier.classify("list all hosts")).isEqualTo(TaskType.QUERY);
            assertThat(classifier.classify("show status")).isEqualTo(TaskType.QUERY);
            assertThat(classifier.classify("check the server")).isEqualTo(TaskType.QUERY);
        }

        @Test
        void execute_english() {
            assertThat(classifier.classify("run the script")).isEqualTo(TaskType.EXECUTE);
            assertThat(classifier.classify("restart apache")).isEqualTo(TaskType.EXECUTE);
            assertThat(classifier.classify("delete old logs")).isEqualTo(TaskType.EXECUTE);
        }

        @Test
        void troubleshoot_english() {
            assertThat(classifier.classify("why is the server down")).isEqualTo(TaskType.TROUBLESHOOT);
            assertThat(classifier.classify("diagnose the timeout error")).isEqualTo(TaskType.TROUBLESHOOT);
            assertThat(classifier.classify("cannot connect to database")).isEqualTo(TaskType.TROUBLESHOOT);
        }

        @Test
        void deploy_english() {
            assertThat(classifier.classify("deploy the new version")).isEqualTo(TaskType.DEPLOY);
            assertThat(classifier.classify("rollback release")).isEqualTo(TaskType.DEPLOY);
            assertThat(classifier.classify("publish to production")).isEqualTo(TaskType.DEPLOY);
        }

        @Test
        void monitor_english() {
            assertThat(classifier.classify("monitor CPU usage")).isEqualTo(TaskType.MONITOR);
            assertThat(classifier.classify("disk metrics threshold")).isEqualTo(TaskType.MONITOR);
            assertThat(classifier.classify("alert threshold for traffic")).isEqualTo(TaskType.MONITOR);
        }
    }

    @Test
    void generalMessage_returnsGeneral() {
        assertThat(classifier.classify("你好")).isEqualTo(TaskType.GENERAL);
        assertThat(classifier.classify("hello there")).isEqualTo(TaskType.GENERAL);
    }
}
