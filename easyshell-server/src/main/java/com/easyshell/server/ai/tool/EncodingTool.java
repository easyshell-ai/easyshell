package com.easyshell.server.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

@Slf4j
@Component
public class EncodingTool {

    private static final Set<String> SUPPORTED_HASH_ALGORITHMS = Set.of(
            "MD5", "SHA-1", "SHA-256", "SHA-512"
    );

    @Tool(description = "Base64 编码或解码。")
    public String base64(
            @ToolParam(description = "要处理的字符串") String input,
            @ToolParam(description = "操作：'encode'（编码）或 'decode'（解码）") String operation) {
        try {
            if (input == null || input.isEmpty()) return "输入不能为空";
            if (operation == null || operation.isBlank()) return "请指定操作：encode 或 decode";

            String op = operation.trim().toLowerCase();
            return switch (op) {
                case "encode" -> {
                    String encoded = Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
                    yield "Base64 编码结果：\n" + encoded;
                }
                case "decode" -> {
                    byte[] decoded = Base64.getDecoder().decode(input.trim());
                    yield "Base64 解码结果：\n" + new String(decoded, StandardCharsets.UTF_8);
                }
                default -> "不支持的操作：" + operation + "，请使用 encode 或 decode";
            };
        } catch (IllegalArgumentException e) {
            return "Base64 解码失败：输入不是有效的 Base64 字符串";
        } catch (Exception e) {
            return "Base64 操作失败：" + e.getMessage();
        }
    }

    @Tool(description = "URL 编码或解码。")
    public String urlEncode(
            @ToolParam(description = "要处理的字符串") String input,
            @ToolParam(description = "操作：'encode'（编码）或 'decode'（解码）") String operation) {
        try {
            if (input == null || input.isEmpty()) return "输入不能为空";
            if (operation == null || operation.isBlank()) return "请指定操作：encode 或 decode";

            String op = operation.trim().toLowerCase();
            return switch (op) {
                case "encode" -> {
                    String encoded = URLEncoder.encode(input, StandardCharsets.UTF_8);
                    yield "URL 编码结果：\n" + encoded;
                }
                case "decode" -> {
                    String decoded = URLDecoder.decode(input, StandardCharsets.UTF_8);
                    yield "URL 解码结果：\n" + decoded;
                }
                default -> "不支持的操作：" + operation + "，请使用 encode 或 decode";
            };
        } catch (Exception e) {
            return "URL 编码/解码失败：" + e.getMessage();
        }
    }

    @Tool(description = "计算字符串的哈希值。")
    public String hash(
            @ToolParam(description = "要计算哈希的字符串") String input,
            @ToolParam(description = "哈希算法：'MD5'、'SHA-1'、'SHA-256'、'SHA-512'") String algorithm) {
        try {
            if (input == null || input.isEmpty()) return "输入不能为空";
            if (algorithm == null || algorithm.isBlank()) return "请指定哈希算法：MD5、SHA-1、SHA-256、SHA-512";

            String algo = algorithm.trim().toUpperCase();
            if (!SUPPORTED_HASH_ALGORITHMS.contains(algo)) {
                return "不支持的哈希算法：" + algorithm + "，支持：MD5、SHA-1、SHA-256、SHA-512";
            }

            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            String hexString = HexFormat.of().formatHex(hashBytes);

            return String.format("%s 哈希值：\n%s", algo, hexString);
        } catch (Exception e) {
            return "哈希计算失败：" + e.getMessage();
        }
    }
}
