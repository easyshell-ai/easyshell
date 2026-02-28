package com.easyshell.server.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
public class DateTimeTool {

    private static final List<DateTimeFormatter> COMMON_FORMATS = List.of(
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    );

    @Tool(description = "获取当前时间。可指定时区和输出格式。")
    public String getCurrentTime(
            @ToolParam(description = "时区，如 'Asia/Shanghai'、'UTC'、'America/New_York'，默认 UTC") String timezone,
            @ToolParam(description = "输出格式，如 'yyyy-MM-dd HH:mm:ss'、'ISO8601'，默认 ISO8601") String format) {
        try {
            ZoneId zone = parseZone(timezone);
            ZonedDateTime now = ZonedDateTime.now(zone);

            String formatted;
            if (format == null || format.isBlank() || "ISO8601".equalsIgnoreCase(format)) {
                formatted = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } else {
                formatted = now.format(DateTimeFormatter.ofPattern(format));
            }

            return String.format("当前时间（%s）：%s", zone.getId(), formatted);
        } catch (Exception e) {
            return "获取时间失败：" + e.getMessage();
        }
    }

    @Tool(description = "解析时间字符串为标准格式。支持多种常见格式的自动识别，也支持 Unix 时间戳（秒/毫秒）。")
    public String parseTime(
            @ToolParam(description = "要解析的时间字符串或 Unix 时间戳") String timeStr,
            @ToolParam(description = "输入时间的格式（可选，不提供则自动识别）") String inputFormat,
            @ToolParam(description = "目标输出格式，默认 yyyy-MM-dd HH:mm:ss") String outputFormat) {
        try {
            if (timeStr == null || timeStr.isBlank()) {
                return "时间字符串不能为空";
            }

            String outFmt = (outputFormat != null && !outputFormat.isBlank()) ? outputFormat : "yyyy-MM-dd HH:mm:ss";
            DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern(outFmt);

            // Try Unix timestamp first
            String trimmed = timeStr.trim();
            if (trimmed.matches("^\\d{10,13}$")) {
                long ts = Long.parseLong(trimmed);
                if (trimmed.length() == 13) {
                    ts = ts / 1000; // milliseconds to seconds
                }
                LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneId.of("UTC"));
                return String.format("解析结果（UTC）：%s\n原始时间戳：%s", dt.format(outputFormatter), trimmed);
            }

            // Try explicit input format
            if (inputFormat != null && !inputFormat.isBlank()) {
                DateTimeFormatter inFormatter = DateTimeFormatter.ofPattern(inputFormat);
                LocalDateTime dt = LocalDateTime.parse(trimmed, inFormatter);
                return "解析结果：" + dt.format(outputFormatter);
            }

            // Auto-detect format
            for (DateTimeFormatter fmt : COMMON_FORMATS) {
                try {
                    LocalDateTime dt = LocalDateTime.parse(trimmed, fmt);
                    return "解析结果：" + dt.format(outputFormatter);
                } catch (DateTimeParseException ignored) {
                }
                try {
                    LocalDate d = LocalDate.parse(trimmed, fmt);
                    return "解析结果：" + d.atStartOfDay().format(outputFormatter);
                } catch (DateTimeParseException ignored) {
                }
            }

            return "无法识别时间格式，请指定 inputFormat 参数。支持的格式包括：ISO8601、yyyy-MM-dd HH:mm:ss、Unix 时间戳等";
        } catch (Exception e) {
            return "解析时间失败：" + e.getMessage();
        }
    }

    @Tool(description = "计算两个时间点之间的差值。支持 ISO8601 或 'yyyy-MM-dd HH:mm:ss' 格式。")
    public String timeDiff(
            @ToolParam(description = "开始时间") String startTime,
            @ToolParam(description = "结束时间") String endTime,
            @ToolParam(description = "返回单位：'seconds'、'minutes'、'hours'、'days'、'human'（人类可读），默认 human") String unit) {
        try {
            LocalDateTime start = parseDateTime(startTime);
            LocalDateTime end = parseDateTime(endTime);

            if (start == null) return "无法解析开始时间：" + startTime;
            if (end == null) return "无法解析结束时间：" + endTime;

            Duration duration = Duration.between(start, end);
            String effectiveUnit = (unit != null && !unit.isBlank()) ? unit.toLowerCase() : "human";

            return switch (effectiveUnit) {
                case "seconds" -> String.format("时间差：%d 秒", duration.toSeconds());
                case "minutes" -> String.format("时间差：%d 分钟", duration.toMinutes());
                case "hours" -> String.format("时间差：%.2f 小时", duration.toSeconds() / 3600.0);
                case "days" -> String.format("时间差：%.2f 天", duration.toSeconds() / 86400.0);
                default -> {
                    long totalSeconds = Math.abs(duration.toSeconds());
                    long days = totalSeconds / 86400;
                    long hours = (totalSeconds % 86400) / 3600;
                    long minutes = (totalSeconds % 3600) / 60;
                    long seconds = totalSeconds % 60;
                    StringBuilder sb = new StringBuilder("时间差：");
                    if (duration.isNegative()) sb.append("-");
                    if (days > 0) sb.append(days).append("天 ");
                    if (hours > 0) sb.append(hours).append("小时 ");
                    if (minutes > 0) sb.append(minutes).append("分钟 ");
                    sb.append(seconds).append("秒");
                    yield sb.toString();
                }
            };
        } catch (Exception e) {
            return "计算时间差失败：" + e.getMessage();
        }
    }

    private LocalDateTime parseDateTime(String str) {
        if (str == null || str.isBlank()) return null;
        String trimmed = str.trim();

        // Unix timestamp
        if (trimmed.matches("^\\d{10,13}$")) {
            long ts = Long.parseLong(trimmed);
            if (trimmed.length() == 13) ts = ts / 1000;
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneId.of("UTC"));
        }

        for (DateTimeFormatter fmt : COMMON_FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) {
            }
            try {
                return LocalDate.parse(trimmed, fmt).atStartOfDay();
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}
