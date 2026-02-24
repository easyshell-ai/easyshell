package com.easyshell.server.ai.risk;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.model.vo.CommandRisk;
import com.easyshell.server.ai.model.vo.RiskAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandRiskEngine {

    private final AgenticConfigService configService;

    private static final Set<String> FALLBACK_LOW_COMMANDS = Set.of(
            "ls", "pwd", "tree", "find", "cat", "head", "tail", "less", "more",
            "wc", "diff", "uname", "hostname", "whoami", "id", "uptime", "date",
            "ps", "top", "htop", "free", "df", "du", "ifconfig", "ip", "netstat",
            "ss", "ping", "dig", "nslookup", "grep", "awk", "sed", "sort",
            "echo", "env", "printenv"
    );

    private static final Set<String> FALLBACK_LOW_COMPOUND_COMMANDS = Set.of(
            "docker ps", "docker images", "docker logs", "docker inspect", "docker stats",
            "systemctl status", "systemctl is-active",
            "journalctl -u",
            "kubectl get", "kubectl describe", "kubectl logs",
            "git status", "git log", "git diff",
            "apt list", "yum list", "pip list", "npm list"
    );

    private static final Set<String> FALLBACK_HIGH_COMMANDS = Set.of(
            "reboot", "shutdown", "poweroff", "halt", "mkfs", "fdisk", "dd",
            "useradd", "userdel", "passwd", "chmod", "chown",
            "iptables", "firewall-cmd", "ufw", "modprobe",
            "systemctl start", "systemctl stop", "systemctl restart",
            "apt install", "apt remove", "yum install", "yum remove", "pip install",
            "docker rm", "docker rmi", "docker stop", "docker run", "crontab"
    );

    private static final Set<String> FALLBACK_BANNED_PATTERNS = Set.of(
            "rm -rf /", "rm -rf /*",
            ":(){ :|:& };:",
            "echo > /proc/", "> /etc/passwd", "> /etc/shadow",
            "/dev/zero",
            "nc -e", "bash -i >& /dev/tcp",
            "xmrig", "minerd",
            "chmod 4755", "chmod u+s"
    );

    private static final Set<String> RISK_MODIFIERS_SED = Set.of("-i");
    private static final Set<String> RISK_MODIFIERS_CURL = Set.of("-X", "--request", "-d", "--data", "--upload-file");

    public RiskAssessment assessScript(String scriptContent) {
        if (scriptContent == null || scriptContent.isBlank()) {
            return RiskAssessment.of(RiskLevel.LOW, Collections.emptyList());
        }

        List<String> bannedMatches = findBannedPatterns(scriptContent);
        if (!bannedMatches.isEmpty()) {
            return RiskAssessment.banned(bannedMatches);
        }

        List<CommandRisk> commandRisks = new ArrayList<>();
        String[] lines = scriptContent.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            if (trimmed.startsWith("#!/")) {
                continue;
            }

            String[] pipeSegments = trimmed.split("\\|");
            for (String segment : pipeSegments) {
                String cmd = segment.trim();
                if (cmd.isEmpty()) continue;

                RiskLevel level = classifyCommand(cmd);
                String reason = buildReason(cmd, level);
                commandRisks.add(CommandRisk.builder()
                        .command(cmd)
                        .level(level)
                        .reason(reason)
                        .build());
            }
        }

        if (commandRisks.isEmpty()) {
            return RiskAssessment.of(RiskLevel.LOW, commandRisks);
        }

        RiskLevel maxRisk = commandRisks.stream()
                .map(CommandRisk::getLevel)
                .max(Comparator.comparingInt(RiskLevel::getLevel))
                .orElse(RiskLevel.LOW);

        return RiskAssessment.of(maxRisk, commandRisks);
    }

    public RiskLevel classifyCommand(String command) {
        String trimmed = command.trim();

        for (String pattern : getAllBannedPatterns()) {
            if (trimmed.contains(pattern)) {
                return RiskLevel.BANNED;
            }
        }

        for (String pattern : List.of("wget .* \\| .*sh", "curl .* \\| .*sh")) {
            if (trimmed.matches(".*" + pattern + ".*")) {
                return RiskLevel.BANNED;
            }
        }

        String baseCommand = extractBaseCommand(trimmed);

        for (String compound : getAllLowCompoundCommands()) {
            if (trimmed.startsWith(compound)) {
                return RiskLevel.LOW;
            }
        }

        if (getAllLowCommands().contains(baseCommand)) {
            if (hasRiskModifier(trimmed, baseCommand)) {
                return RiskLevel.MEDIUM;
            }
            return RiskLevel.LOW;
        }

        if ("kill".equals(baseCommand)) {
            if (trimmed.contains("-9") || trimmed.contains("-KILL") || trimmed.contains("SIGKILL")) {
                return RiskLevel.HIGH;
            }
            return RiskLevel.MEDIUM;
        }

        if ("rm".equals(baseCommand)) {
            if (trimmed.contains("-rf") || trimmed.contains("-r")) {
                return RiskLevel.MEDIUM;
            }
            return RiskLevel.MEDIUM;
        }

        if (getAllHighCommands().contains(baseCommand)) {
            return RiskLevel.HIGH;
        }

        for (String highCmd : getAllHighCommands()) {
            if (trimmed.startsWith(highCmd + " ") || trimmed.equals(highCmd)) {
                return RiskLevel.HIGH;
            }
        }

        return RiskLevel.LOW;
    }

    private List<String> findBannedPatterns(String scriptContent) {
        List<String> matches = new ArrayList<>();
        String lower = scriptContent.toLowerCase();

        for (String pattern : getAllBannedPatterns()) {
            if (lower.contains(pattern.toLowerCase())) {
                matches.add(pattern);
            }
        }

        if (scriptContent.matches("(?s).*wget\\s+\\S+\\s*\\|\\s*sh.*")) {
            matches.add("wget ... | sh");
        }
        if (scriptContent.matches("(?s).*curl\\s+\\S+\\s*\\|\\s*sh.*")) {
            matches.add("curl ... | sh");
        }

        return matches;
    }

    private String extractBaseCommand(String command) {
        String cleaned = command.replaceAll("^(sudo\\s+)", "");
        String[] parts = cleaned.split("\\s+");
        if (parts.length == 0) return command;
        String base = parts[0];
        if (base.contains("/")) {
            base = base.substring(base.lastIndexOf('/') + 1);
        }
        return base;
    }

    private boolean hasRiskModifier(String command, String baseCommand) {
        if ("sed".equals(baseCommand) && containsAny(command, RISK_MODIFIERS_SED)) {
            return true;
        }
        if ("curl".equals(baseCommand) && containsAny(command, RISK_MODIFIERS_CURL)) {
            return true;
        }
        if ("wget".equals(baseCommand) && (command.contains("-O") || command.contains("--output-document"))) {
            return true;
        }
        return false;
    }

    private boolean containsAny(String text, Set<String> tokens) {
        String[] parts = text.split("\\s+");
        for (String part : parts) {
            if (tokens.contains(part)) return true;
        }
        return false;
    }

    private String buildReason(String command, RiskLevel level) {
        String base = extractBaseCommand(command);
        return switch (level) {
            case LOW -> "只读/信息采集命令";
            case MEDIUM -> "修改类命令，需人工确认: " + base;
            case HIGH -> "高危系统命令: " + base;
            case BANNED -> "封禁命令";
        };
    }

    private Set<String> getAllBannedPatterns() {
        Set<String> all = new LinkedHashSet<>(FALLBACK_BANNED_PATTERNS);
        all.addAll(configService.getStringSet("ai.risk.banned-commands", Collections.emptySet()));
        return all;
    }

    private Set<String> getAllLowCommands() {
        Set<String> all = new LinkedHashSet<>(FALLBACK_LOW_COMMANDS);
        all.addAll(configService.getStringSet("ai.risk.low-commands", Collections.emptySet()));
        return all;
    }

    private Set<String> getAllLowCompoundCommands() {
        Set<String> all = new LinkedHashSet<>(FALLBACK_LOW_COMPOUND_COMMANDS);
        all.addAll(configService.getStringSet("ai.risk.low-compound-commands", Collections.emptySet()));
        return all;
    }

    private Set<String> getAllHighCommands() {
        Set<String> all = new LinkedHashSet<>(FALLBACK_HIGH_COMMANDS);
        all.addAll(configService.getStringSet("ai.risk.high-commands", Collections.emptySet()));
        return all;
    }

    public Set<String> getEffectiveBannedPatterns() {
        return getAllBannedPatterns();
    }

    public Set<String> getEffectiveLowCommands() {
        return getAllLowCommands();
    }

    public Set<String> getEffectiveHighCommands() {
        return getAllHighCommands();
    }

    public Set<String> getDefaultBannedPatterns() {
        return FALLBACK_BANNED_PATTERNS;
    }

    public Set<String> getDefaultHighCommands() {
        return FALLBACK_HIGH_COMMANDS;
    }

    public Set<String> getDefaultLowCommands() {
        return FALLBACK_LOW_COMMANDS;
    }

    public List<String> getCustomBannedPatterns() {
        return configService.getStringList("ai.risk.banned-commands", Collections.emptyList());
    }

    public List<String> getCustomHighCommands() {
        return configService.getStringList("ai.risk.high-commands", Collections.emptyList());
    }

    public List<String> getCustomLowCommands() {
        return configService.getStringList("ai.risk.low-commands", Collections.emptyList());
    }
}
