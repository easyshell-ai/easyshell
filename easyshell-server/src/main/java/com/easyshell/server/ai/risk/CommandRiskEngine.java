package com.easyshell.server.ai.risk;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.model.vo.CommandRisk;
import com.easyshell.server.ai.model.vo.RiskAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

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
            "reboot", "shutdown", "poweroff", "halt", "mkfs", "mkfs.ext2", "mkfs.ext3",
            "mkfs.ext4", "mkfs.xfs", "mkswap", "fdisk", "parted", "dd",
            "useradd", "userdel", "usermod", "passwd", "chmod", "chown",
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

    /**
     * find 的写操作选项：删除/执行/写文件。与 {@link #isReadOnlySimpleCommand} 的 find
     * 分支保持同一份名单——解释器 -c 降级路径早已禁掉这些用法，但 classifyCommand 主
     * 路径此前只查 sed/curl/wget 的风险修饰符，于是裸 {@code find ... -delete} 直接判
     * LOW 免审（MF-1：直接执行比包一层 bash -c 更危险，后者被判 HIGH）。
     */
    private static final Set<String> FIND_WRITE_FLAGS =
            Set.of("-delete", "-exec", "-execdir", "-fprint", "-fprintf", "-ok", "-okdir");

    /** 词边界匹配：-delete 不得误伤 --help 或文件名参数里的 "deleted" */
    private static final Pattern FIND_WRITE_FLAG_PATTERN = Pattern.compile(
            "(?<![\\w-])(-delete|-exec|-execdir|-fprint|-fprintf|-ok|-okdir)(?![\\w-])");

    /**
     * 参与“解释器 -c 只读降级”的 shell 解释器。python/perl/node 等属于代码执行而非命令组合，
     * 无法用命令白名单判定其副作用，一律不放行（保持 HIGH）。
     */
    private static final Set<String> SHELL_INTERPRETERS = Set.of("bash", "sh", "zsh", "dash", "ksh");

    /**
     * 解释器 -c 载荷的只读白名单: 只有当 -c 之后的命令串按 ; && || | 与换行切分后，
     * 每一段都命中该白名单且参数用法只读时，才把 bash -c "..." 整体降级为 LOW。
     * 保守起步: 不在表内、参数用法不确定、解析失败的一律不放行（fail-closed 保持 HIGH）。
     */
    private static final Set<String> READONLY_INTERPRETER_COMMANDS = Set.of(
            // 文件与目录查看
            "ls", "pwd", "cat", "head", "tail", "less", "more", "tree", "stat", "file",
            "find", "which", "command",
            // 文本处理（只读用法）
            "grep", "egrep", "fgrep", "sed", "awk", "gawk", "wc", "sort", "uniq", "cut", "tr", "diff",
            // 系统与进程信息
            "uname", "hostname", "uptime", "date", "id", "who", "w", "last", "nproc", "lscpu",
            "lsblk", "mount", "ps", "top", "free", "df", "du", "env", "printenv", "echo", "test", "[",
            // 网络状态
            "netstat", "ss", "ip", "ifconfig", "ping", "dig", "nslookup",
            // 服务、容器与计划任务（仅白名单内的只读子命令）
            "systemctl", "journalctl", "docker", "kubectl", "crontab"
    );

    /** 只读降级允许的重定向目标，其它任何 >/>> 写入都视为危险信号 */
    private static final Set<String> SAFE_REDIRECT_TARGETS =
            Set.of("/dev/null", "/dev/stdout", "/dev/stderr", "/dev/tty");

    /**
     * 命令位置前缀: 行首或 ; && || | 换行 ( 之后，确保命中“命令名”而不是文件名或参数
     * （例如 cat /etc/passwd 不应被 passwd 黑名单误伤）。
     */
    private static final String CMD_POSITION = "(^|[\\n;&|(]\\s*)";

    /**
     * 解释器 -c 载荷的危险信号: 命中任意一条即放弃降级，交回 classifyCommand 判定。
     * 这是安全底线，宁可误拦（维持人工审批）也不放过潜在破坏性载荷。
     */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // 删除 / 覆盖 / 磁盘操作
            Pattern.compile(CMD_POSITION + "rm\\s+-\\w*[rf]", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "rm\\s+--(force|recursive)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "(shred|truncate|mkfs|mkswap|fdisk|parted|dd)\\b", Pattern.CASE_INSENSITIVE),
            // 下载即执行 / 管道交给 shell 或解释器
            Pattern.compile("\\|\\s*(sudo\\s+)?(ba|z|da|a)?sh\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\|\\s*(python\\d*|perl|ruby|node|php|lua|Rscript)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\|\\s*sudo\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(curl|wget)\\b[^\\n]{0,300}\\|\\s*(ba|z|da|a)?sh\\b", Pattern.CASE_INSENSITIVE),
            // 编码 / 混淆载荷与命令替换
            Pattern.compile("\\bbase64\\s+(-d|--decode)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(xxd|od)\\s+-[a-z]*r", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\$\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("`"),
            Pattern.compile("\\$\\{"),
            Pattern.compile(CMD_POSITION + "(eval|exec)\\b", Pattern.CASE_INSENSITIVE),
            // 权限与属主
            Pattern.compile(CMD_POSITION + "chmod\\s+(-\\S+\\s+)*0*777", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "chmod\\s+-[a-z]*R", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "chmod\\s+(-\\S+\\s+)*[2-7][0-7]{3}", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "chmod\\s+(-\\S+\\s+)*[ugoa]\\+s", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "chown\\s+-[a-z]*R", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "chattr\\b", Pattern.CASE_INSENSITIVE),
            // 用户与认证
            Pattern.compile(CMD_POSITION + "(useradd|usermod|userdel|passwd|chpasswd|gpasswd|visudo)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "(ssh-keygen|ssh-copy-id)\\b", Pattern.CASE_INSENSITIVE),
            // 服务与系统状态
            Pattern.compile(CMD_POSITION + "systemctl\\s+(stop|disable|mask|restart|reload|kill|set-property|try-restart|preset)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "service\\s+\\S+\\s+(stop|restart|reload)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "(reboot|shutdown|poweroff|halt)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "init\\s+[06]\\b", Pattern.CASE_INSENSITIVE),
            // 进程杀死
            Pattern.compile(CMD_POSITION + "kill\\s+-\\w*(9|KILL|SIGKILL)", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "(killall|pkill)\\b", Pattern.CASE_INSENSITIVE),
            // 网络与防火墙
            Pattern.compile(CMD_POSITION + "(iptables|nftables|nft|firewall-cmd|ufw)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "(nc|ncat)\\s+-[a-z]*e", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/dev/tcp/", Pattern.CASE_INSENSITIVE),
            // 计划任务（仅放行 -l 列出）
            Pattern.compile(CMD_POSITION + "crontab\\s+-[a-z]*[re]", Pattern.CASE_INSENSITIVE),
            // 容器与编排写操作
            Pattern.compile(CMD_POSITION + "docker\\s+run\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "docker\\s+(-\\S+\\s+)*-(v|volume)\\s+/:", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "kubectl\\s+(delete|apply|exec|edit|patch|drain|cordon|scale|rollout|replace|create|label|annotate|taint|set)\\b", Pattern.CASE_INSENSITIVE),
            // 提权与痕迹清理
            Pattern.compile(CMD_POSITION + "sudo\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "su\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(CMD_POSITION + "history\\s+-c", Pattern.CASE_INSENSITIVE),
            // 直接写块设备
            Pattern.compile(">\\s*/dev/(sd|hd|vd|nvme)", Pattern.CASE_INSENSITIVE)
    );

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

            // 引号感知切分: 引号内的 | ; && || 不视为分隔符（避免 grep -E "a|b" file 被误切）
            for (String segment : splitCommandSegments(trimmed)) {
                String cmd = segment.trim();
                if (cmd.isEmpty()) continue;

                RiskLevel level;
                String reason;
                if (isReadOnlyInterpreterCommand(cmd)) {
                    // 解释器 -c 载荷经递归解析后全部命中只读白名单，可安全降级
                    level = RiskLevel.LOW;
                    reason = "解释器 -c 载荷均为只读命令";
                } else {
                    level = classifyCommand(cmd);
                    reason = buildReason(cmd, level);
                }
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
        if (command == null || command.isBlank()) {
            return RiskLevel.LOW;
        }
        String trimmed = command.trim();

        for (String pattern : getAllBannedPatterns()) {
            if (matchesPattern(trimmed, pattern)) {
                return RiskLevel.BANNED;
            }
        }

        for (String pattern : List.of("wget .* \\| .*sh", "curl .* \\| .*sh")) {
            if (trimmed.matches(".*" + pattern + ".*")) {
                return RiskLevel.BANNED;
            }
        }

        // Obfuscation / interpreter execution: the classic way to smuggle a payload past a
        // keyword blacklist. Treat as high risk instead of silently trusting it.
        if (isObfuscated(trimmed)) {
            return RiskLevel.HIGH;
        }

        String baseCommand = extractBaseCommand(trimmed);

        // MF-1：find 的写操作（删除/执行/写文件）不是只读用法，排除出 LOW 白名单，
        // 与解释器 -c 降级路径（isReadOnlySimpleCommand 的 find 分支）同一份名单。
        // 只读用法（-print/-mtime/-newer/-iname/-type 等）不受影响。
        if ("find".equals(baseCommand) && FIND_WRITE_FLAG_PATTERN.matcher(trimmed).find()) {
            return RiskLevel.MEDIUM;
        }

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

        if (isNetworkEgress(baseCommand)) {
            return isNetworkWriteOrExecute(trimmed) ? RiskLevel.HIGH : RiskLevel.MEDIUM;
        }

        // Unknown command: fail closed. Anything that is not explicitly recognised as
        // read-only requires human confirmation instead of being auto-executed.
        return RiskLevel.MEDIUM;
    }

    private boolean isNetworkEgress(String baseCommand) {
        return "curl".equals(baseCommand) || "wget".equals(baseCommand)
                || "nc".equals(baseCommand) || "ncat".equals(baseCommand)
                || "telnet".equals(baseCommand) || "ssh".equals(baseCommand)
                || "scp".equals(baseCommand) || "ftp".equals(baseCommand);
    }

    private boolean isNetworkWriteOrExecute(String command) {
        String lower = command.toLowerCase();
        return lower.contains(" -d") || lower.contains("--data")
                || lower.contains("--upload-file") || lower.contains(" -t ")
                || lower.contains(" -f ") || lower.contains("--form")
                || lower.contains("| sh") || lower.contains("| bash")
                || lower.contains("|sh") || lower.contains("|bash")
                || lower.contains(" -o ") || lower.contains("--output-document");
    }

    /**
     * Detects interpreter execution and encoded payloads, which are the standard way to hide a
     * command from keyword matching ("python3 -c ...", "base64 -d ... | sh", "$IFS", escapes).
     */
    private boolean isObfuscated(String command) {
        String lower = command.toLowerCase();
        for (String marker : List.of(
                "python -c", "python3 -c", "python2 -c",
                "perl -e", "ruby -e", "node -e", "node --eval",
                "php -r", "lua -e", "Rscript -e")) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        if (lower.matches(".*\\b(eval|exec|source)\\s+.*") && lower.contains("$")) {
            return true;
        }
        if (lower.contains("base64 -d") || lower.contains("base64 --decode")
                || lower.contains("base64 --dec") || lower.contains("openssl base64 -d")) {
            return true;
        }
        if (command.contains("$IFS") || command.contains("${IFS}")) {
            return true;
        }
        if (lower.contains("xxd -r") || lower.contains("xxd -p -r")) {
            return true;
        }
        // printf/echo with hex or octal escapes used to rebuild a command string
        if ((lower.startsWith("printf") || lower.startsWith("echo -e") || lower.startsWith("echo -ne"))
                && (command.contains("\\x") || command.contains("\\0"))) {
            return true;
        }
        if (lower.matches(".*\\b(bash|sh|zsh|dash)\\s+-c\\b.*")) {
            return true;
        }
        return false;
    }

    /**
     * 判断一条命令是否属于“解释器 -c 执行纯只读命令”的安全形式，例如 bash -c "df -h && free -m"。
     * 只有 bash/sh/zsh/dash 参与降级，且必须同时满足:
     * 1. 整条命令不含任何危险信号（见 DANGEROUS_PATTERNS）；
     * 2. 不含任何写重定向（> / >>，/dev/null 等标准丢弃口除外）；
     * 3. -c 之后的命令串递归切分后，每一段都是只读命令（白名单外一律不放行）。
     */
    private boolean isReadOnlyInterpreterCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (containsDangerousPattern(command) || containsWriteRedirection(command)) {
            return false;
        }
        List<String> tokens = tokenizeCommand(command);
        if (tokens.size() < 3) {
            // 至少需要 解释器 + -c + 载荷 三段
            return false;
        }
        if (!SHELL_INTERPRETERS.contains(baseName(tokens.get(0)))) {
            return false;
        }
        int flagIndex = -1;
        for (int i = 1; i < tokens.size(); i++) {
            if ("-c".equals(tokens.get(i))) {
                flagIndex = i;
                break;
            }
            if (tokens.get(i).startsWith("-")) {
                // 允许 -e/-l 等前置选项
                continue;
            }
            // -c 之前出现位置参数，形态不标准，不放行
            return false;
        }
        if (flagIndex < 0 || flagIndex == tokens.size() - 1) {
            return false;
        }
        String payload = String.join(" ", tokens.subList(flagIndex + 1, tokens.size())).trim();
        if (payload.isEmpty()) {
            return false;
        }
        for (String segment : splitCommandSegments(payload)) {
            String inner = segment.trim();
            if (inner.isEmpty()) continue;
            // 危险信号在载荷段内同样一票否决
            if (containsDangerousPattern(inner)) {
                return false;
            }
            // 递归解析: 嵌套的 bash -c 同样要求载荷全部只读
            if (isReadOnlyInterpreterCommand(inner)) continue;
            if (!isReadOnlySimpleCommand(inner)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断单个简单命令是否为只读用法: 命令必须命中只读白名单，且参数中不含写入/破坏性选项。
     */
    private boolean isReadOnlySimpleCommand(String segment) {
        // 只读降级不接受任何写重定向（如 bash -c "df -h > /tmp/out.txt" 中的载荷写入）
        if (containsWriteRedirection(segment)) {
            return false;
        }
        List<String> tokens = tokenizeCommand(segment);
        if (tokens.isEmpty()) {
            return false;
        }
        String base = baseName(tokens.get(0));
        if (!READONLY_INTERPRETER_COMMANDS.contains(base)) {
            return false;
        }
        List<String> args = tokens.subList(1, tokens.size());
        return switch (base) {
            case "find" -> !containsAnyToken(args, FIND_WRITE_FLAGS.toArray(String[]::new));
            case "sed" -> containsToken(args, "-n") && !containsInPlaceFlag(args);
            case "awk", "gawk" -> isReadOnlyAwkProgram(segment);
            case "sort" -> !containsAnyToken(args, "-o", "--output", "--compress-program")
                    && !args.stream().anyMatch(a -> a.startsWith("--output="));
            case "top" -> containsToken(args, "-b");
            case "mount" -> args.isEmpty();
            // hostname 带参数即修改主机名（与 date -s 同类的系统状态修改）
            case "hostname" -> args.isEmpty();
            case "crontab" -> isReadOnlyCrontab(args);
            case "systemctl" -> isReadOnlySubcommand(args, "status", "is-active", "is-enabled");
            case "docker" -> isReadOnlySubcommand(args,
                    "ps", "images", "stats", "logs", "inspect", "version", "info", "top");
            case "kubectl" -> isReadOnlySubcommand(args,
                    "get", "describe", "logs", "top", "version", "cluster-info", "explain",
                    "api-resources", "api-versions");
            case "journalctl" -> args.stream().noneMatch(a ->
                    a.startsWith("--vacuum") || "--rotate".equals(a) || "--flush".equals(a));
            case "date" -> !hasShortFlagLetter(args, 's') && !containsToken(args, "--set");
            case "ping" -> !hasShortFlagLetter(args, 'f');
            case "ip" -> !containsAnyToken(args, "set", "add", "del", "change", "replace", "flush");
            case "ifconfig" -> !containsAnyToken(args, "up", "down");
            case "env" -> args.isEmpty() || args.stream().allMatch(a -> a.startsWith("-"));
            case "command" -> !args.isEmpty() && ("-v".equals(args.get(0)) || "-V".equals(args.get(0)));
            default -> true;
        };
    }

    /** awk 程序里不允许 system()/getline 以及向文件或管道输出 */
    private boolean isReadOnlyAwkProgram(String segment) {
        String lower = segment.toLowerCase();
        return !lower.contains("system(") && !lower.contains("getline")
                && !lower.contains("|") && !lower.contains(">");
    }

    /** crontab 仅放行列出: crontab -l / crontab -l -u <user> */
    private boolean isReadOnlyCrontab(List<String> args) {
        if (args.isEmpty() || (!"-l".equals(args.get(0)) && !"--list".equals(args.get(0)))) {
            return false;
        }
        for (int i = 1; i < args.size(); i++) {
            if ("-u".equals(args.get(i)) || "--user".equals(args.get(i))) {
                i++;
            } else {
                return false;
            }
        }
        return true;
    }

    /** systemctl/docker/kubectl 仅放行枚举出的只读子命令（跳过前置选项） */
    private boolean isReadOnlySubcommand(List<String> args, String... allowed) {
        String subcommand = null;
        for (String arg : args) {
            if (!arg.startsWith("-")) {
                subcommand = arg;
                break;
            }
        }
        return subcommand != null && List.of(allowed).contains(subcommand);
    }

    private boolean containsToken(List<String> args, String token) {
        return args.contains(token);
    }

    private boolean containsAnyToken(List<String> args, String... tokens) {
        List<String> all = List.of(tokens);
        return args.stream().anyMatch(all::contains);
    }

    /** 判断是否存在包含指定字母的短选项（如 date -s、ping -f） */
    private boolean hasShortFlagLetter(List<String> args, char letter) {
        return args.stream()
                .anyMatch(a -> a.startsWith("-") && !a.startsWith("--") && a.indexOf(letter) > 0);
    }

    /** sed 原地编辑选项: -i / -i.bak / --in-place / --in-place=... */
    private boolean containsInPlaceFlag(List<String> args) {
        return args.stream().anyMatch(a ->
                "-i".equals(a) || a.matches("-i\\..*") || "--in-place".equals(a)
                        || a.startsWith("--in-place="));
    }

    private String baseName(String token) {
        String base = token;
        if (base.contains("/")) {
            base = base.substring(base.lastIndexOf('/') + 1);
        }
        return base;
    }

    /**
     * 引号感知的命令切分: 单引号/双引号内的 | ; && || 与换行都不作为分隔符，
     * 引号外的反斜杠转义（如 find -exec ... \;）同样不切分。
     */
    private List<String> splitCommandSegments(String command) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                current.append(c);
                if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                current.append(c);
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < command.length()) {
                    current.append(command.charAt(++i));
                }
                continue;
            }
            if (c == '\\' && i + 1 < command.length()) {
                // 引号外的转义: \; \| \& 等保持原样，不当分隔符
                current.append(c).append(command.charAt(++i));
                continue;
            }
            if (c == '\'' || c == '"') {
                if (c == '\'') {
                    inSingle = true;
                } else {
                    inDouble = true;
                }
                current.append(c);
                continue;
            }
            if (c == '|' || c == ';' || c == '\n') {
                if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                    // || 两个字符作为一个分隔符
                    segments.add(current.toString());
                    current.setLength(0);
                    i++;
                    continue;
                }
                segments.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                segments.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
        }
        segments.add(current.toString());
        return segments;
    }

    /** 引号感知的简单分词: 去掉引号本身，保留引号内的空格与分隔符 */
    private List<String> tokenizeCommand(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < command.length() && "\"\\$`".indexOf(command.charAt(i + 1)) >= 0) {
                    current.append(command.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** 解释器载荷命中任意危险信号即放弃降级（fail-closed） */
    private boolean containsDangerousPattern(String command) {
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 引号感知的写重定向检测: 只读降级不允许任何写操作，
     * 仅放行 2>&1 这类文件描述符复制与 >/dev/null 等标准丢弃口。
     */
    private boolean containsWriteRedirection(String command) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            if (c != '>') {
                continue;
            }
            if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
                // 2>&1 文件描述符复制，不是写文件
                i++;
                continue;
            }
            if (i + 1 < command.length() && command.charAt(i + 1) == '>') {
                i++;
            }
            int j = i + 1;
            while (j < command.length() && Character.isWhitespace(command.charAt(j))) {
                j++;
            }
            StringBuilder target = new StringBuilder();
            while (j < command.length() && !Character.isWhitespace(command.charAt(j))) {
                target.append(command.charAt(j++));
            }
            if (!SAFE_REDIRECT_TARGETS.contains(target.toString())) {
                return true;
            }
            i = j - 1;
        }
        return false;
    }

    private List<String> findBannedPatterns(String scriptContent) {
        List<String> matches = new ArrayList<>();
        String lower = scriptContent.toLowerCase();

        for (String pattern : getAllBannedPatterns()) {
            if (matchesPatternInScript(lower, pattern.toLowerCase())) {
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
        // 与 isReadOnlySimpleCommand（解释器 -c 降级路径）同口径的只读用法校验。
        // 下列命令外表是信息采集，但带写参数时会改文件/改系统状态；主路径此前只查
        // sed/curl/wget，导致同一用法直接执行判 LOW、包一层 bash -c 反而 HIGH
        // （MF-1 的 find 写操作只是其中最重的一个，实测还发现了 awk system()/
        // print > file、sort -o、date -s、ping -f、ip set、ifconfig up、
        // env <cmd>、hostname <新名> 同类漏洞）。
        List<String> tokens = tokenizeCommand(command);
        List<String> args = tokens.size() > 1 ? tokens.subList(1, tokens.size()) : List.of();
        return switch (baseCommand) {
            // awk 程序里不允许 system()/getline 以及向文件或管道输出（与降级路径同一判定）
            case "awk", "gawk" -> !isReadOnlyAwkProgram(command);
            case "sort" -> containsAnyToken(args, "-o", "--output", "--compress-program")
                    || args.stream().anyMatch(a -> a.startsWith("--output="));
            case "date" -> hasShortFlagLetter(args, 's') || containsToken(args, "--set");
            case "ping" -> hasShortFlagLetter(args, 'f');
            case "ip" -> containsAnyToken(args, "set", "add", "del", "change", "replace", "flush");
            case "ifconfig" -> containsAnyToken(args, "up", "down");
            case "env" -> args.stream().anyMatch(a -> !a.startsWith("-"));
            case "hostname" -> !args.isEmpty();
            default -> false;
        };
    }

    private boolean containsAny(String text, Set<String> tokens) {
        String[] parts = text.split("\\s+");
        for (String part : parts) {
            if (tokens.contains(part)) return true;
        }
        return false;
    }

    /**
     * Check if a single command line matches a banned pattern using word-boundary matching.
     * For short patterns (single word like "rm", "dd"), uses word-boundary regex to avoid
     * false positives (e.g., "rm" matching "format", "inform").
     * For multi-word patterns (like "rm -rf /", "nc -e"), uses substring matching since
     * they are specific enough to not cause false positives.
     */
    private boolean matchesPattern(String command, String pattern) {
        String lowerCmd = command.toLowerCase();
        String lowerPattern = pattern.toLowerCase();
        if (!pattern.contains(" ")) {
            // Single-word pattern: use word-boundary matching
            return Pattern.compile("(?i)\\b" + Pattern.quote(lowerPattern) + "\\b").matcher(lowerCmd).find();
        }
        // Multi-word pattern: substring matching is safe (specific enough)
        return lowerCmd.contains(lowerPattern);
    }

    /**
     * Check if a full script (multi-line) contains a banned pattern.
     * Same word-boundary logic as matchesPattern but applied to the full script content.
     */
    private boolean matchesPatternInScript(String scriptLower, String patternLower) {
        if (!patternLower.contains(" ")) {
            // Single-word pattern: use word-boundary matching
            return Pattern.compile("\\b" + Pattern.quote(patternLower) + "\\b").matcher(scriptLower).find();
        }
        // Multi-word pattern: substring matching
        return scriptLower.contains(patternLower);
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
