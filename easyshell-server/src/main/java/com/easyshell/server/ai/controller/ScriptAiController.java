package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.orchestrator.AgentEvent;
import com.easyshell.server.ai.service.ChatModelFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai/script")
@RequiredArgsConstructor
public class ScriptAiController {

    private final ChatModelFactory chatModelFactory;

    // Section delimiters used in the AI prompt
    private static final String DELIM_NAME = "===SCRIPT_NAME===";
    private static final String DELIM_DESC = "===SCRIPT_DESCRIPTION===";
    private static final String DELIM_CONTENT = "===SCRIPT_CONTENT===";
    private static final String DELIM_SUMMARY = "===CHANGE_SUMMARY===";

    @Data
    public static class ScriptGenerateRequest {
        private String prompt;
        private String os;           // ubuntu, centos, debian, rhel, alpine, generic
        private String scriptType;   // bash, sh, python
        private String language;     // ui language: en-US, zh-CN
        private String existingScript; // existing script content for modification
    }

    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<AgentEvent>>> generateScript(
            @RequestBody ScriptGenerateRequest request,
            Authentication auth) {

        boolean isModification = request.getExistingScript() != null && !request.getExistingScript().isBlank();
        String systemPrompt = buildSystemPrompt(request, isModification);
        String userPrompt;
        if (isModification) {
            userPrompt = "EXISTING SCRIPT TO MODIFY:\n```\n" + request.getExistingScript() + "\n```\n\nUSER REQUEST: " + request.getPrompt();
        } else {
            userPrompt = request.getPrompt();
        }

        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        // Run AI generation in background thread
        new Thread(() -> {
            try {
                ChatModel chatModel = chatModelFactory.getChatModel(null);
                Prompt prompt = new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userPrompt)
                ));

                // Accumulate full response, parse sections, then emit structured events
                StringBuilder fullResponse = new StringBuilder();

                Flux<ChatResponse> streamFlux = chatModel.stream(prompt);
                streamFlux.doOnNext(chunk -> {
                    if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                    String text = chunk.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        fullResponse.append(text);
                        // Emit raw CONTENT for live streaming display on frontend
                        sink.tryEmitNext(AgentEvent.content(text));
                    }
                }).doOnComplete(() -> {
                    // Parse completed response into structured sections
                    try {
                        String raw = fullResponse.toString();
                        emitParsedSections(raw, sink, isModification);
                    } catch (Exception e) {
                        log.warn("Failed to parse structured sections from AI response", e);
                    }
                    sink.tryEmitNext(AgentEvent.builder().type(AgentEvent.Type.DONE).build());
                    sink.tryEmitComplete();
                }).doOnError(err -> {
                    log.error("Script generation error", err);
                    sink.tryEmitNext(AgentEvent.error(err.getMessage()));
                    sink.tryEmitComplete();
                }).blockLast();
            } catch (Exception e) {
                log.error("Script generation failed", e);
                sink.tryEmitNext(AgentEvent.error(e.getMessage()));
                sink.tryEmitComplete();
            }
        }).start();

        Flux<ServerSentEvent<AgentEvent>> sseFlux = sink.asFlux()
                .map(event -> ServerSentEvent.<AgentEvent>builder().data(event).build());

        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .body(sseFlux);
    }

    /**
     * Parse the full AI response by section delimiters and emit structured AgentEvents.
     * After all CONTENT events (raw streaming) are done, we emit STEP_START events
     * with metadata containing the parsed section values.
     */
    private void emitParsedSections(String raw, Sinks.Many<AgentEvent> sink, boolean isModification) {
        String name = extractSection(raw, DELIM_NAME, DELIM_DESC);
        String description = extractSection(raw, DELIM_DESC, DELIM_CONTENT);
        String content = isModification
                ? extractSection(raw, DELIM_CONTENT, DELIM_SUMMARY)
                : extractSection(raw, DELIM_CONTENT, null);
        String summary = isModification ? extractSection(raw, DELIM_SUMMARY, null) : null;

        // Emit parsed metadata as STEP_START events so frontend can extract fields
        if (name != null) {
            sink.tryEmitNext(AgentEvent.builder()
                    .type(AgentEvent.Type.STEP_START)
                    .stepDescription("SCRIPT_NAME")
                    .content(name)
                    .metadata(Map.of("section", "name", "value", name))
                    .build());
        }
        if (description != null) {
            sink.tryEmitNext(AgentEvent.builder()
                    .type(AgentEvent.Type.STEP_START)
                    .stepDescription("SCRIPT_DESCRIPTION")
                    .content(description)
                    .metadata(Map.of("section", "description", "value", description))
                    .build());
        }
        if (content != null) {
            sink.tryEmitNext(AgentEvent.builder()
                    .type(AgentEvent.Type.STEP_START)
                    .stepDescription("SCRIPT_CONTENT")
                    .content(content)
                    .metadata(Map.of("section", "content", "value", content))
                    .build());
        }
        if (summary != null) {
            sink.tryEmitNext(AgentEvent.builder()
                    .type(AgentEvent.Type.PLAN_SUMMARY)
                    .content(summary)
                    .build());
        }
    }

    /**
     * Extract text between startDelim and endDelim from the raw AI response.
     * If endDelim is null, extract from startDelim to end of string.
     */
    private String extractSection(String raw, String startDelim, String endDelim) {
        int start = raw.indexOf(startDelim);
        if (start < 0) return null;
        start += startDelim.length();

        int end;
        if (endDelim != null) {
            end = raw.indexOf(endDelim, start);
            if (end < 0) end = raw.length();
        } else {
            end = raw.length();
        }

        String result = raw.substring(start, end).trim();
        return result.isEmpty() ? null : result;
    }

    private String buildSystemPrompt(ScriptGenerateRequest request, boolean isModification) {
        String scriptType = request.getScriptType() != null ? request.getScriptType() : "bash";
        String os = request.getOs() != null ? request.getOs() : "generic";
        boolean isChinese = "zh-CN".equals(request.getLanguage());

        String osDescription = switch (os) {
            case "ubuntu" -> "Ubuntu (Debian-based, uses apt/apt-get, systemd, ufw)";
            case "centos" -> "CentOS (RHEL-based, uses yum/dnf, systemd, firewalld)";
            case "debian" -> "Debian (uses apt/apt-get, systemd, iptables/nftables)";
            case "rhel" -> "Red Hat Enterprise Linux (uses yum/dnf, systemd, firewalld, SELinux)";
            case "alpine" -> "Alpine Linux (uses apk, OpenRC, busybox, musl libc)";
            default -> "Generic Linux (use POSIX-compatible commands, avoid distro-specific tools)";
        };

        String langInstruction = isChinese
                ? "Use Chinese for the name, description, and change summary fields. Write code comments in Chinese."
                : "Use English for the name, description, and change summary fields. Write code comments in English.";

        String summarySection = isModification ? """
                
                ===CHANGE_SUMMARY===
                (Describe what you changed and why, in a clear bulleted list. Respond in the SAME LANGUAGE as the user's request.)
                """ : "";

        return """
                You are a senior Linux systems engineer and script writer.
                
                TARGET OS: %s
                SCRIPT TYPE: %s
                
                %s
                
                IMPORTANT RULES:
                1. Generate production-quality scripts with proper error handling
                2. Use OS-appropriate package managers, service managers, and file paths
                3. Include proper shebang line (#!/bin/bash, #!/bin/sh, or #!/usr/bin/env python3)
                4. Add helpful comments explaining key sections
                5. Use best practices for the target OS (correct paths, permissions, service names)
                6. Handle edge cases and provide meaningful error messages
                7. When outputting to stdout, NEVER redirect to a variable like "-". Write directly to stdout.
                
                MODIFICATION MODE:
                - If the user provides an EXISTING SCRIPT, modify it according to their request
                - Preserve the original script's structure and style when possible
                - Only change what the user asks for, keep everything else intact
                - If no existing script is provided, create a new script from scratch
                
                RESPONSE FORMAT — You MUST respond using EXACTLY these section delimiters. No other format is accepted.
                
                ===SCRIPT_NAME===
                (A short descriptive name for this script, max 50 chars)
                
                ===SCRIPT_DESCRIPTION===
                (A one-line description of what this script does)
                
                ===SCRIPT_CONTENT===
                (The full script content with proper formatting. Output ONLY the raw script code, no markdown code fences.)
                %s
                CRITICAL RULES:
                - Start each section IMMEDIATELY after its delimiter on the next line
                - Do NOT wrap the script in ```bash``` or any code fences
                - The script content must be raw executable code
                - Output NOTHING before the first ===SCRIPT_NAME=== delimiter
                - Keep each section delimiter on its own line
                """.formatted(osDescription, scriptType, langInstruction, summarySection);
    }
}
