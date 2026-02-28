package com.easyshell.server.ai.tool;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TextProcessTool {

    private static final int REGEX_TIMEOUT_SECONDS = 5;

    @Tool(description = "使用正则表达式从文本中提取内容。返回所有匹配项。")
    public String extractByRegex(
            @ToolParam(description = "要搜索的文本") String text,
            @ToolParam(description = "正则表达式") String regex,
            @ToolParam(description = "要提取的捕获组编号，0 表示整个匹配，默认 0") int group) {
        try {
            if (text == null || text.isBlank()) return "文本不能为空";
            if (regex == null || regex.isBlank()) return "正则表达式不能为空";

            int effectiveGroup = Math.max(0, group);

            // Execute regex with timeout to prevent ReDoS
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(() -> {
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(text);
                List<String> matches = new ArrayList<>();
                while (matcher.find()) {
                    if (effectiveGroup <= matcher.groupCount()) {
                        matches.add(matcher.group(effectiveGroup));
                    }
                    if (matches.size() >= 1000) break; // Safety limit
                }
                return formatMatches(matches);
            });

            try {
                return future.get(REGEX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return "正则表达式执行超时（" + REGEX_TIMEOUT_SECONDS + "秒），可能是表达式过于复杂导致回溯过多。请简化正则表达式。";
            } finally {
                executor.shutdownNow();
            }
        } catch (Exception e) {
            return "正则提取失败：" + e.getMessage();
        }
    }

    private String formatMatches(List<String> matches) {
        if (matches.isEmpty()) {
            return "未找到匹配项";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共找到 ").append(matches.size()).append(" 个匹配项：\n\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "对比两段文本的差异，返回 diff 结果。")
    public String diffText(
            @ToolParam(description = "原始文本") String originalText,
            @ToolParam(description = "修改后的文本") String modifiedText,
            @ToolParam(description = "输出格式：'unified'（统一 diff 格式）、'summary'（差异摘要），默认 unified") String format) {
        try {
            if (originalText == null) originalText = "";
            if (modifiedText == null) modifiedText = "";

            List<String> originalLines = Arrays.asList(originalText.split("\n", -1));
            List<String> modifiedLines = Arrays.asList(modifiedText.split("\n", -1));

            Patch<String> patch = DiffUtils.diff(originalLines, modifiedLines);

            if (patch.getDeltas().isEmpty()) {
                return "两段文本完全相同，没有差异。";
            }

            String effectiveFormat = (format != null && !format.isBlank()) ? format.toLowerCase() : "unified";

            if ("summary".equals(effectiveFormat)) {
                int insertions = 0, deletions = 0, changes = 0;
                for (var delta : patch.getDeltas()) {
                    switch (delta.getType()) {
                        case INSERT -> insertions += delta.getTarget().size();
                        case DELETE -> deletions += delta.getSource().size();
                        case CHANGE -> changes += Math.max(delta.getSource().size(), delta.getTarget().size());
                        default -> {}
                    }
                }
                return String.format("差异摘要：%d 处修改\n- 新增行：%d\n- 删除行：%d\n- 变更行：%d",
                        patch.getDeltas().size(), insertions, deletions, changes);
            }

            // Unified diff
            List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                    "original", "modified", originalLines, patch, 3);

            StringBuilder sb = new StringBuilder();
            for (String line : unifiedDiff) {
                sb.append(line).append("\n");
            }

            // Truncate if too long
            if (sb.length() > 8000) {
                return sb.substring(0, 8000) + "\n... [diff 结果已截断]";
            }

            return sb.toString();
        } catch (Exception e) {
            return "文本对比失败：" + e.getMessage();
        }
    }

    @Tool(description = "统计文本信息：字符数、单词数、行数等。")
    public String textStats(
            @ToolParam(description = "要统计的文本") String text) {
        try {
            if (text == null || text.isEmpty()) {
                return "文本为空";
            }

            int charCount = text.length();
            int lineCount = text.split("\n", -1).length;
            int wordCount = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
            long chineseCharCount = text.codePoints()
                    .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                    .count();
            int nonBlankLineCount = (int) Arrays.stream(text.split("\n", -1))
                    .filter(line -> !line.isBlank())
                    .count();

            return String.format("文本统计：\n- 总字符数：%d\n- 总行数：%d\n- 非空行数：%d\n- 单词/词数：%d\n- 中文字符数：%d",
                    charCount, lineCount, nonBlankLineCount, wordCount, chineseCharCount);
        } catch (Exception e) {
            return "文本统计失败：" + e.getMessage();
        }
    }
}
