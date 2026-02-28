package com.easyshell.server.ai.tool;

import lombok.extern.slf4j.Slf4j;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Slf4j
@Component
public class CalculatorTool {

    private static final Map<String, Long> STORAGE_UNITS = Map.of(
            "B", 1L,
            "KB", 1024L,
            "MB", 1024L * 1024,
            "GB", 1024L * 1024 * 1024,
            "TB", 1024L * 1024 * 1024 * 1024,
            "PB", 1024L * 1024 * 1024 * 1024 * 1024
    );

    @Tool(description = "计算数学表达式。支持 +、-、*、/、^（幂）、%（取模）、括号、常用数学函数（sqrt、sin、cos、log、abs、ceil、floor 等）。")
    public String calculate(
            @ToolParam(description = "数学表达式，如 '(100 - 75) / 100 * 100' 或 'sqrt(144) + 2^3'") String expression) {
        try {
            if (expression == null || expression.isBlank()) {
                return "表达式不能为空";
            }

            Expression exp = new ExpressionBuilder(expression)
                    .build();

            double result = exp.evaluate();

            // Format result nicely
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return String.format("计算结果：%s = %d", expression, (long) result);
            } else {
                BigDecimal bd = BigDecimal.valueOf(result).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
                return String.format("计算结果：%s = %s", expression, bd.toPlainString());
            }
        } catch (Exception e) {
            return "计算失败：" + e.getMessage() + "\n请确认表达式格式正确。支持运算符：+、-、*、/、^、%，函数：sqrt、sin、cos、tan、log、log2、log10、abs、ceil、floor";
        }
    }

    @Tool(description = "存储单位换算。在 B、KB、MB、GB、TB、PB 之间转换。")
    public String convertStorageUnit(
            @ToolParam(description = "数值") double value,
            @ToolParam(description = "源单位：B/KB/MB/GB/TB/PB") String fromUnit,
            @ToolParam(description = "目标单位：B/KB/MB/GB/TB/PB") String toUnit) {
        try {
            if (fromUnit == null || toUnit == null) {
                return "单位不能为空，支持：B、KB、MB、GB、TB、PB";
            }

            String from = fromUnit.trim().toUpperCase();
            String to = toUnit.trim().toUpperCase();

            Long fromBytes = STORAGE_UNITS.get(from);
            Long toBytes = STORAGE_UNITS.get(to);

            if (fromBytes == null) {
                return "不支持的源单位：" + fromUnit + "，支持：B、KB、MB、GB、TB、PB";
            }
            if (toBytes == null) {
                return "不支持的目标单位：" + toUnit + "，支持：B、KB、MB、GB、TB、PB";
            }

            double bytes = value * fromBytes;
            double result = bytes / toBytes;

            BigDecimal bd = BigDecimal.valueOf(result).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
            return String.format("换算结果：%.2f %s = %s %s", value, from, bd.toPlainString(), to);
        } catch (Exception e) {
            return "单位换算失败：" + e.getMessage();
        }
    }
}
