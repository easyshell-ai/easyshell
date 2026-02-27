package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.HostProvisionRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.HostCredential;
import com.easyshell.server.model.vo.HostCredentialVO;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.HostCredentialRepository;
import com.easyshell.server.service.HostProvisionService;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.util.CryptoUtils;
import com.jcraft.jsch.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HostProvisionServiceImpl implements HostProvisionService {

    // Self-reference to invoke @Async methods through the Spring proxy
    @Autowired
    private ApplicationContext applicationContext;

    private HostProvisionService self() {
        return applicationContext.getBean(HostProvisionService.class);
    }

    private final HostCredentialRepository credentialRepository;
    private final AgentRepository agentRepository;
    private final CryptoUtils cryptoUtils;
    private final TransactionTemplate transactionTemplate;
    private final SystemConfigRepository systemConfigRepository;

    @Value("${easyshell.provision.server-url:http://127.0.0.1:18080}")
    private String serverUrl;

    @Value("${easyshell.provision.agent-binary-dir:/root/easyshell/easyshell-agent}")
    private String agentBinaryDir;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional
    public HostCredentialVO provision(HostProvisionRequest request) {
        credentialRepository.findByIp(request.getIp()).ifPresent(existing -> {
            if ("SUCCESS".equals(existing.getProvisionStatus())) {
                throw new BusinessException(400, "该IP已部署成功，如需重新部署请先删除记录");
            }
            credentialRepository.delete(existing);
            credentialRepository.flush();
        });

        String authType = request.getAuthType() != null ? request.getAuthType() : "password";

        // Validate based on auth type
        if ("password".equals(authType)) {
            if (request.getSshPassword() == null || request.getSshPassword().isBlank()) {
                throw new BusinessException(400, "密码登录方式必须提供SSH密码");
            }
        } else if ("key".equals(authType)) {
            if (request.getSshPrivateKey() == null || request.getSshPrivateKey().isBlank()) {
                throw new BusinessException(400, "密钥登录方式必须提供SSH私钥");
            }
        } else {
            throw new BusinessException(400, "不支持的认证方式: " + authType);
        }

        HostCredential credential = new HostCredential();
        credential.setIp(request.getIp());
        credential.setSshPort(request.getSshPort() != null ? request.getSshPort() : 22);
        credential.setSshUsername(request.getSshUsername());
        credential.setAuthType(authType);
        credential.setHostName(request.getHostName());

        if ("password".equals(authType)) {
            credential.setSshPasswordEncrypted(cryptoUtils.encrypt(request.getSshPassword()));
        } else {
            credential.setSshPrivateKeyEncrypted(cryptoUtils.encrypt(request.getSshPrivateKey()));
        }

        credential.setProvisionStatus("PENDING");
        credential.setProvisionLog("");

        credential = credentialRepository.save(credential);
        return toVO(credential);
    }

    @Async
    @Override
    public void startProvisionAsync(Long credentialId) {
        executeProvision(credentialId);
    }

    @Override
    public List<HostCredentialVO> listAll() {
        return credentialRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public HostCredentialVO getById(Long id) {
        return credentialRepository.findById(id)
                .map(this::toVO)
                .orElseThrow(() -> new BusinessException(404, "记录不存在"));
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        credentialRepository.deleteById(id);
    }

    @Override
    @Transactional
    public HostCredentialVO retry(Long id) {
        HostCredential credential = credentialRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "记录不存在"));

        credential.setProvisionStatus("PENDING");
        credential.setProvisionLog("");
        credential.setErrorMessage(null);
        credential.setAgentId(null);
        credentialRepository.save(credential);
        return toVO(credential);
    }

    @Async
    @Override
    public void startRetryAsync(Long credentialId) {
        executeProvision(credentialId);
    }

    @Override
    @Transactional
    public HostCredentialVO reinstall(String agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException(404, "Agent不存在: " + agentId));

        HostCredential credential = credentialRepository.findByIp(agent.getIp())
                .orElseThrow(() -> new BusinessException(404,
                        "未找到该主机的SSH凭证，无法重新安装。请通过添加服务器重新部署。"));

        credential.setProvisionStatus("PENDING");
        credential.setProvisionLog("");
        credential.setErrorMessage(null);
        credentialRepository.save(credential);
        return toVO(credential);
    }

    @Override
    @Transactional
    public HostCredentialVO reinstallByCredentialId(Long credentialId) {
        HostCredential credential = credentialRepository.findById(credentialId)
                .orElseThrow(() -> new BusinessException(404, "凭证记录不存在: " + credentialId));
        credential.setProvisionStatus("PENDING");
        credential.setProvisionLog("");
        credential.setErrorMessage(null);
        credentialRepository.save(credential);
        return toVO(credential);
    }

    @Async
    @Override
    public void startReinstallAsync(Long credentialId) {
        executeProvision(credentialId);
    }

    @Override
    public List<HostCredentialVO> batchReinstall(List<String> agentIds) {
        List<HostCredentialVO> results = new java.util.ArrayList<>();
        for (String agentId : agentIds) {
            try {
                HostCredentialVO vo = reinstall(agentId);
                self().startReinstallAsync(vo.getId());
                results.add(vo);
            } catch (Exception e) {
                log.warn("Failed to start reinstall for agent {}: {}", agentId, e.getMessage());
            }
        }
        return results;
    }


    @Override
    @Transactional
    public HostCredentialVO uninstall(String agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException(404, "Agent不存在: " + agentId));

        HostCredential credential = credentialRepository.findByIp(agent.getIp())
                .orElseThrow(() -> new BusinessException(404,
                        "未找到该主机的SSH凭证，无法远程卸载。请使用删除功能直接从数据库移除。"));

        credential.setProvisionStatus("UNINSTALLING");
        credential.setProvisionLog("");
        credential.setErrorMessage(null);
        credentialRepository.save(credential);
        return toVO(credential);
    }

    @Async
    @Override
    public void startUninstallAsync(Long credentialId) {
        executeUninstall(credentialId);
    }

    private void executeUninstall(Long credentialId) {
        HostCredential credential = transactionTemplate.execute(status ->
                credentialRepository.findById(credentialId).orElse(null)
        );
        if (credential == null) return;

        Session session = null;
        try {
            saveStatus(credentialId, "UNINSTALLING", "正在连接 " + credential.getIp() + ":" + credential.getSshPort() + " ...");

            JSch jsch = new JSch();
            session = createSshSession(jsch, credential);

            saveLog(credentialId, "SSH连接已建立，开始卸载Agent");

            // Stop agent service
            boolean hasSystemd = detectSystemd(session);
            if (hasSystemd) {
                execCommand(session, "systemctl stop easyshell-agent 2>/dev/null || true");
                execCommand(session, "systemctl disable easyshell-agent 2>/dev/null || true");
                execCommand(session, "rm -f /etc/systemd/system/easyshell-agent.service");
                execCommand(session, "systemctl daemon-reload");
                saveLog(credentialId, "已停止并移除systemd服务");
            } else {
                execCommand(session, "/etc/init.d/easyshell-agent stop 2>/dev/null || true");
                execCommand(session, "rm -f /etc/init.d/easyshell-agent");
                execCommand(session, "command -v chkconfig >/dev/null 2>&1 && chkconfig --del easyshell-agent 2>/dev/null || true");
                execCommand(session, "command -v update-rc.d >/dev/null 2>&1 && update-rc.d easyshell-agent remove 2>/dev/null || true");
                saveLog(credentialId, "已停止并移除sysvinit服务");
            }

            // Kill any remaining processes
            execCommand(session, "pkill -f 'easyshell-agent' 2>/dev/null || true");
            saveLog(credentialId, "已终止残留进程");

            // Remove agent files
            execCommand(session, "rm -rf /opt/easyshell/");
            saveLog(credentialId, "已删除Agent文件 /opt/easyshell/");

            // Mark as UNINSTALLED instead of deleting
            String agentId = credential.getAgentId();
            transactionTemplate.executeWithoutResult(status -> {
                if (agentId != null) {
                    agentRepository.deleteById(agentId);
                }
                HostCredential c = credentialRepository.findById(credentialId).orElse(null);
                if (c != null) {
                    c.setProvisionStatus("UNINSTALLED");
                    c.setAgentId(null);
                    appendLog(c, "卸载完成");
                    credentialRepository.save(c);
                }
            });

            log.info("Uninstall completed for host {} (credential {})", credential.getIp(), credentialId);

        } catch (Exception e) {
            log.error("Uninstall failed for host {}: {}", credentialId, e.getMessage(), e);
            transactionTemplate.executeWithoutResult(status -> {
                HostCredential c = credentialRepository.findById(credentialId).orElse(null);
                if (c != null) {
                    c.setProvisionStatus("UNINSTALL_FAILED");
                    c.setErrorMessage(e.getMessage());
                    appendLog(c, "卸载失败: " + e.getMessage());
                    credentialRepository.save(c);
                }
            });
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    private void executeProvision(Long credentialId) {
        HostCredential credential = transactionTemplate.execute(status ->
                credentialRepository.findById(credentialId).orElse(null)
        );
        if (credential == null) return;

        Session session = null;
        try {
            saveStatus(credentialId, "CONNECTING", "正在连接 " + credential.getIp() + ":" + credential.getSshPort() + " ...");

            JSch jsch = new JSch();
            session = createSshSession(jsch, credential);

            saveLog(credentialId, "SSH连接已建立");

            // --- Detect target architecture ---
            String unameOutput = execCommand(session, "uname -m").trim();
            String arch = mapArch(unameOutput);
            saveLog(credentialId, "检测到目标架构: " + unameOutput + " → " + arch);

            String binaryFileName = "easyshell-agent-linux-" + arch;
            String localBinaryPath = agentBinaryDir + "/" + binaryFileName;
            File localBinary = new File(localBinaryPath);
            if (!localBinary.exists()) {
                throw new RuntimeException("找不到架构对应的Agent二进制: " + localBinaryPath
                        + " (目标架构: " + unameOutput + ")");
            }

            saveStatus(credentialId, "UPLOADING", "正在上传Agent二进制文件 (" + arch + ")...");

            execCommand(session, "mkdir -p /opt/easyshell/configs");
            
            String existingAgent = execCommand(session, "ls -la /opt/easyshell/easyshell-agent 2>/dev/null || echo 'not exists'").trim();
            if (!existingAgent.contains("not exists")) {
                saveLog(credentialId, "检测到现有Agent文件: " + existingAgent);
                execCommand(session, "pkill -f 'easyshell-agent' 2>/dev/null || true");
                Thread.sleep(1000);
                execCommand(session, "rm -f /opt/easyshell/easyshell-agent");
                saveLog(credentialId, "已停止并删除现有Agent");
            }
            
            String diskSpace = execCommand(session, "df -h /opt 2>/dev/null | tail -1 || echo 'unknown'").trim();
            saveLog(credentialId, "目标磁盘空间: " + diskSpace);

            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(30000);
            try (FileInputStream fis = new FileInputStream(localBinary)) {
                sftp.put(fis, "/opt/easyshell/easyshell-agent", ChannelSftp.OVERWRITE);
            } catch (SftpException sftpEx) {
                sftp.disconnect();
                String permCheck = execCommand(session, "ls -la /opt/easyshell/ 2>&1 && touch /opt/easyshell/test_write 2>&1 && rm /opt/easyshell/test_write 2>&1 || echo 'Permission denied or path issue'");
                throw new RuntimeException("SFTP上传失败 (错误码: " + sftpEx.id + "): " + sftpEx.getMessage() 
                        + "\n目录状态: " + permCheck);
            }
            sftp.disconnect();

            execCommand(session, "chmod +x /opt/easyshell/easyshell-agent");
            saveLog(credentialId, "Agent二进制文件已上传至 /opt/easyshell/easyshell-agent (" + binaryFileName + ")");

            saveStatus(credentialId, "INSTALLING", "正在创建配置和服务...");

            String resolvedServerUrl = resolveServerUrl();
            String agentYaml = "server:\n  url: " + resolvedServerUrl + "\n\nagent:\n  id: \"\"\n\nheartbeat:\n  interval: 30\n\nmetrics:\n  interval: 60\n\nlog:\n  level: info\n";

            execCommand(session, "cat > /opt/easyshell/configs/agent.yaml << 'EOFCONFIG'\n" + agentYaml + "EOFCONFIG");
            saveLog(credentialId, "Agent配置文件已写入 /opt/easyshell/configs/agent.yaml");

            // --- Detect init system and install service ---
            boolean hasSystemd = detectSystemd(session);
            saveLog(credentialId, "Init系统检测: " + (hasSystemd ? "systemd" : "sysvinit/其他"));

            saveStatus(credentialId, "STARTING", "正在启动Agent服务...");

            if (hasSystemd) {
                installWithSystemd(session, credentialId);
            } else {
                installWithSysvinit(session, credentialId);
            }

            Thread.sleep(5000);

            // --- Verify agent is running ---
            String pidCheck = execCommand(session, "pgrep -f 'easyshell-agent' || echo ''").trim();
            if (!pidCheck.isEmpty()) {
                saveStatus(credentialId, "SUCCESS", "Agent进程已启动 (PID: " + pidCheck.split("\\n")[0] + ")，等待自动注册到Server...");
            } else {
                String agentLog = execCommand(session, "tail -20 /opt/easyshell/agent.log 2>/dev/null || journalctl -u easyshell-agent --no-pager -n 20 2>/dev/null || echo 'No logs'");
                throw new RuntimeException("Agent进程未启动\n日志:\n" + agentLog);
            }

        } catch (Exception e) {
            log.error("Provisioning failed for host {}: {}", credentialId, e.getMessage(), e);
            transactionTemplate.executeWithoutResult(status -> {
                HostCredential c = credentialRepository.findById(credentialId).orElse(null);
                if (c != null) {
                    c.setProvisionStatus("FAILED");
                    c.setErrorMessage(e.getMessage());
                    appendLog(c, "部署失败: " + e.getMessage());
                    credentialRepository.save(c);
                }
            });
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * Map uname -m output to Go GOARCH naming convention.
     */
    private String mapArch(String unameArch) {
        return switch (unameArch) {
            case "x86_64", "amd64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new RuntimeException("不支持的CPU架构: " + unameArch
                    + "。当前支持: x86_64 (amd64), aarch64 (arm64)");
        };
    }

    /**
     * Detect whether the target system uses systemd.
     */
    private boolean detectSystemd(Session session) throws Exception {
        // Check if systemctl exists and PID 1 is systemd
        String result = execCommand(session, "test -x /usr/bin/systemctl -o -x /bin/systemctl && echo 'yes' || echo 'no'").trim();
        return "yes".equals(result);
    }

    /**
     * Install and start agent using systemd (modern distros).
     */
    private void installWithSystemd(Session session, Long credentialId) throws Exception {
        String serviceUnit = "[Unit]\n" +
                "Description=EasyShell Agent\n" +
                "After=network.target\n\n" +
                "[Service]\n" +
                "Type=simple\n" +
                "ExecStart=/opt/easyshell/easyshell-agent --config /opt/easyshell/configs/agent.yaml\n" +
                "Restart=always\n" +
                "RestartSec=10\n" +
                "WorkingDirectory=/opt/easyshell\n" +
                "StandardOutput=journal\n" +
                "StandardError=journal\n\n" +
                "[Install]\n" +
                "WantedBy=multi-user.target\n";

        execCommand(session, "cat > /etc/systemd/system/easyshell-agent.service << 'EOFSERVICE'\n" + serviceUnit + "EOFSERVICE");
        saveLog(credentialId, "Systemd服务文件已写入 /etc/systemd/system/easyshell-agent.service");

        execCommand(session, "systemctl daemon-reload");
        saveLog(credentialId, "执行 systemctl daemon-reload");
        execCommand(session, "systemctl enable easyshell-agent");
        saveLog(credentialId, "执行 systemctl enable easyshell-agent");
        execCommand(session, "systemctl stop easyshell-agent 2>/dev/null; sleep 1");
        execCommand(session, "systemctl start easyshell-agent");
        saveLog(credentialId, "执行 systemctl start easyshell-agent");
    }

    /**
     * Install and start agent using SysVinit script (legacy distros without systemd).
     */
    private void installWithSysvinit(Session session, Long credentialId) throws Exception {
        String initScript = "#!/bin/sh\n" +
                "### BEGIN INIT INFO\n" +
                "# Provides:          easyshell-agent\n" +
                "# Required-Start:    $network $remote_fs\n" +
                "# Required-Stop:     $network $remote_fs\n" +
                "# Default-Start:     2 3 4 5\n" +
                "# Default-Stop:      0 1 6\n" +
                "# Description:       EasyShell Agent\n" +
                "### END INIT INFO\n\n" +
                "DAEMON=/opt/easyshell/easyshell-agent\n" +
                "DAEMON_ARGS=\"--config /opt/easyshell/configs/agent.yaml\"\n" +
                "PIDFILE=/var/run/easyshell-agent.pid\n" +
                "LOGFILE=/opt/easyshell/agent.log\n\n" +
                "case \"$1\" in\n" +
                "  start)\n" +
                "    echo \"Starting easyshell-agent...\"\n" +
                "    cd /opt/easyshell\n" +
                "    nohup $DAEMON $DAEMON_ARGS >> $LOGFILE 2>&1 &\n" +
                "    echo $! > $PIDFILE\n" +
                "    echo \"Started (PID: $(cat $PIDFILE))\"\n" +
                "    ;;\n" +
                "  stop)\n" +
                "    echo \"Stopping easyshell-agent...\"\n" +
                "    if [ -f $PIDFILE ]; then\n" +
                "      kill $(cat $PIDFILE) 2>/dev/null\n" +
                "      rm -f $PIDFILE\n" +
                "    fi\n" +
                "    pkill -f 'easyshell-agent' 2>/dev/null\n" +
                "    echo \"Stopped\"\n" +
                "    ;;\n" +
                "  restart)\n" +
                "    $0 stop\n" +
                "    sleep 1\n" +
                "    $0 start\n" +
                "    ;;\n" +
                "  status)\n" +
                "    if [ -f $PIDFILE ] && kill -0 $(cat $PIDFILE) 2>/dev/null; then\n" +
                "      echo \"Running (PID: $(cat $PIDFILE))\"\n" +
                "    else\n" +
                "      echo \"Stopped\"\n" +
                "      exit 1\n" +
                "    fi\n" +
                "    ;;\n" +
                "  *)\n" +
                "    echo \"Usage: $0 {start|stop|restart|status}\"\n" +
                "    exit 1\n" +
                "    ;;\n" +
                "esac\n" +
                "exit 0\n";

        execCommand(session, "cat > /etc/init.d/easyshell-agent << 'EOFINIT'\n" + initScript + "EOFINIT");
        execCommand(session, "chmod +x /etc/init.d/easyshell-agent");
        saveLog(credentialId, "SysVinit服务脚本已写入 /etc/init.d/easyshell-agent");

        // Try to register with chkconfig (CentOS/RHEL) or update-rc.d (Debian/Ubuntu)
        execCommand(session, "command -v chkconfig >/dev/null 2>&1 && chkconfig --add easyshell-agent && chkconfig easyshell-agent on || " +
                "command -v update-rc.d >/dev/null 2>&1 && update-rc.d easyshell-agent defaults || true");
        saveLog(credentialId, "已注册开机自启");

        // Stop any existing instance, then start
        execCommand(session, "pkill -f 'easyshell-agent' 2>/dev/null; sleep 1");
        execCommand(session, "/etc/init.d/easyshell-agent start");
        saveLog(credentialId, "执行 /etc/init.d/easyshell-agent start");
    }

    private String execCommand(Session session, String command) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setInputStream(null);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        channel.setOutputStream(outputStream);
        channel.setErrStream(errorStream);

        channel.connect(30000);

        while (!channel.isClosed()) {
            Thread.sleep(100);
        }

        int exitStatus = channel.getExitStatus();
        channel.disconnect();

        String output = outputStream.toString("UTF-8");
        String error = errorStream.toString("UTF-8");

        if (exitStatus != 0 && !command.contains("2>/dev/null") && !command.contains("systemctl stop")) {
            log.warn("Command '{}' exited {}: stdout={}, stderr={}", command, exitStatus, output, error);
        }

        return output;
    }

    private void saveStatus(Long credentialId, String status, String logMessage) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            HostCredential c = credentialRepository.findById(credentialId).orElse(null);
            if (c != null) {
                c.setProvisionStatus(status);
                appendLog(c, "[" + status + "] " + logMessage);
                credentialRepository.save(c);
            }
        });
    }

    private void saveLog(Long credentialId, String logMessage) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            HostCredential c = credentialRepository.findById(credentialId).orElse(null);
            if (c != null) {
                appendLog(c, logMessage);
                credentialRepository.save(c);
            }
        });
    }

    private void appendLog(HostCredential credential, String message) {
        String existing = credential.getProvisionLog() != null ? credential.getProvisionLog() : "";
        if (!existing.isEmpty()) existing += "\n";
        credential.setProvisionLog(existing + message);
    }

    /**
     * Resolve the server URL for agent configuration.
     * Priority: DB config (server.external-url) > env var (PROVISION_SERVER_URL) > application.yml default.
     * Throws if no usable URL is configured.
     */
    private String resolveServerUrl() {
        // 1. Try DB config first
        String dbUrl = systemConfigRepository.findByConfigKey("server.external-url")
                .map(SystemConfig::getConfigValue)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .orElse(null);
        if (dbUrl != null) {
            log.info("Using server URL from system config: {}", dbUrl);
            return dbUrl;
        }

        // 2. Fallback to env / application.yml value
        if (serverUrl != null && !serverUrl.trim().isEmpty()
                && !"http://127.0.0.1:18080".equals(serverUrl)
                && !"http://localhost:18080".equals(serverUrl)
                && !serverUrl.contains("easyshell-server")) {
            log.info("Using server URL from environment: {}", serverUrl);
            return serverUrl;
        }

        // 3. No valid URL — abort
        throw new BusinessException(400,
                "请先在【系统管理 → 系统配置】中设置 server.external-url 为本服务器的公网可访问地址（例如 http://your-ip:18080），否则 Agent 无法连接 Server");
    }

    private HostCredentialVO toVO(HostCredential entity) {
        return HostCredentialVO.builder()
                .id(entity.getId())
                .ip(entity.getIp())
                .sshPort(entity.getSshPort())
                .sshUsername(entity.getSshUsername())
                .authType(entity.getAuthType())
                .hostName(entity.getHostName())
                .agentId(entity.getAgentId())
                .provisionStatus(entity.getProvisionStatus())
                .provisionLog(entity.getProvisionLog())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FMT) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FMT) : null)
                .build();
    }

    /**
     * Build a HostCredentialVO that merges Agent data when available.
     */
    private HostCredentialVO toUnifiedVO(HostCredential entity, Agent agent) {
        HostCredentialVO.HostCredentialVOBuilder builder = HostCredentialVO.builder()
                .id(entity.getId())
                .ip(entity.getIp())
                .sshPort(entity.getSshPort())
                .sshUsername(entity.getSshUsername())
                .authType(entity.getAuthType())
                .hostName(entity.getHostName())
                .agentId(entity.getAgentId())
                .provisionStatus(entity.getProvisionStatus())
                .provisionLog(entity.getProvisionLog())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FMT) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FMT) : null);

        if (agent != null) {
            // Override agentId from matched agent when credential doesn't have it
            if (entity.getAgentId() == null) {
                builder.agentId(agent.getId());
            }
            builder.hostname(agent.getHostname())
                    .os(agent.getOs())
                    .arch(agent.getArch())
                    .kernel(agent.getKernel())
                    .cpuModel(agent.getCpuModel())
                    .cpuCores(agent.getCpuCores())
                    .memTotal(agent.getMemTotal())
                    .agentVersion(agent.getAgentVersion())
                    .agentStatus(agent.getStatus())
                    .lastHeartbeat(agent.getLastHeartbeat() != null ? agent.getLastHeartbeat().format(FMT) : null)
                    .cpuUsage(agent.getCpuUsage())
                    .memUsage(agent.getMemUsage())
                    .diskUsage(agent.getDiskUsage())
                    .registeredAt(agent.getRegisteredAt() != null ? agent.getRegisteredAt().format(FMT) : null);
        }

        return builder.build();
    }

    /**
     * Create SSH session supporting both password and key authentication.
     */
    private Session createSshSession(JSch jsch, HostCredential credential) throws Exception {
        String authType = credential.getAuthType() != null ? credential.getAuthType() : "password";

        if ("key".equals(authType)) {
            String privateKey = cryptoUtils.decrypt(credential.getSshPrivateKeyEncrypted());
            jsch.addIdentity("deploy-key-" + credential.getId(), privateKey.getBytes(StandardCharsets.UTF_8), null, null);
        }

        Session session = jsch.getSession(credential.getSshUsername(), credential.getIp(), credential.getSshPort());

        if ("password".equals(authType)) {
            String password = cryptoUtils.decrypt(credential.getSshPasswordEncrypted());
            session.setPassword(password);
        }

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.setTimeout(30000);
        session.connect(30000);
        return session;
    }

    // ===== New methods for host deployment enhancement =====

    @Override
    public List<HostCredentialVO> listUnified() {
        List<HostCredential> credentials = credentialRepository.findAllByOrderByCreatedAtDesc();
        // Build agent lookup map by IP
        List<Agent> agents = agentRepository.findAll();
        Map<String, Agent> agentByIp = new HashMap<>();
        Map<String, Agent> agentById = new HashMap<>();
        for (Agent agent : agents) {
            if (agent.getIp() != null) agentByIp.put(agent.getIp(), agent);
            agentById.put(agent.getId(), agent);
        }

        // Track which agent IPs are covered by credentials
        Set<String> coveredIps = new HashSet<>();

        List<HostCredentialVO> result = new ArrayList<>();
        for (HostCredential cred : credentials) {
            Agent agent = null;
            if (cred.getAgentId() != null) {
                agent = agentById.get(cred.getAgentId());
            }
            if (agent == null && cred.getIp() != null) {
                agent = agentByIp.get(cred.getIp());
            }
            result.add(toUnifiedVO(cred, agent));
            coveredIps.add(cred.getIp());
        }

        // Add agents that have no corresponding credential (legacy agents registered before this feature)
        for (Agent agent : agents) {
            if (agent.getIp() != null && !coveredIps.contains(agent.getIp())) {
                HostCredentialVO vo = HostCredentialVO.builder()
                        .ip(agent.getIp())
                        .agentId(agent.getId())
                        .provisionStatus("SUCCESS")
                        .hostname(agent.getHostname())
                        .os(agent.getOs())
                        .arch(agent.getArch())
                        .kernel(agent.getKernel())
                        .cpuModel(agent.getCpuModel())
                        .cpuCores(agent.getCpuCores())
                        .memTotal(agent.getMemTotal())
                        .agentVersion(agent.getAgentVersion())
                        .agentStatus(agent.getStatus())
                        .lastHeartbeat(agent.getLastHeartbeat() != null ? agent.getLastHeartbeat().format(FMT) : null)
                        .cpuUsage(agent.getCpuUsage())
                        .memUsage(agent.getMemUsage())
                        .diskUsage(agent.getDiskUsage())
                        .registeredAt(agent.getRegisteredAt() != null ? agent.getRegisteredAt().format(FMT) : null)
                        .build();
                result.add(vo);
            }
        }

        return result;
    }

    @Override
    public List<HostCredentialVO> batchDeploy(List<Long> credentialIds) {
        List<HostCredential> credentials = credentialRepository.findAllByIdIn(credentialIds);
        List<HostCredentialVO> results = new ArrayList<>();
        for (HostCredential credential : credentials) {
            if (!"PENDING".equals(credential.getProvisionStatus()) && !"FAILED".equals(credential.getProvisionStatus()) && !"UNINSTALLED".equals(credential.getProvisionStatus())) {
                log.warn("Skipping batch deploy for credential {} (status={})", credential.getId(), credential.getProvisionStatus());
                continue;
            }
            credential.setProvisionStatus("PENDING");
            credential.setProvisionLog("");
            credential.setErrorMessage(null);
            credentialRepository.save(credential);
            self().startProvisionAsync(credential.getId());
            results.add(toVO(credential));
        }
        return results;
    }

    @Override
    public List<HostCredentialVO> importFromCsv(InputStream csvInputStream) {
        List<HostCredentialVO> results = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvInputStream, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new BusinessException(400, "CSV文件为空");
            }
            // Skip BOM if present
            if (headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
            }

            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",", -1);
                if (parts.length < 3) {
                    log.warn("CSV line {} has insufficient columns, skipping: {}", lineNum, line);
                    continue;
                }

                String ip = parts[0].trim();
                String sshPortStr = parts.length > 1 ? parts[1].trim() : "22";
                String sshUsername = parts.length > 2 ? parts[2].trim() : "";
                String sshPassword = parts.length > 3 ? parts[3].trim() : "";
                String hostName = parts.length > 4 ? parts[4].trim() : "";

                if (ip.isEmpty() || sshUsername.isEmpty() || sshPassword.isEmpty()) {
                    log.warn("CSV line {} missing required fields (ip, username, password), skipping", lineNum);
                    continue;
                }

                int sshPort;
                try {
                    sshPort = sshPortStr.isEmpty() ? 22 : Integer.parseInt(sshPortStr);
                } catch (NumberFormatException e) {
                    log.warn("CSV line {} has invalid port '{}', using default 22", lineNum, sshPortStr);
                    sshPort = 22;
                }

                // Check if IP already exists
                Optional<HostCredential> existing = credentialRepository.findByIp(ip);
                if (existing.isPresent()) {
                    log.warn("CSV line {} IP {} already exists, skipping", lineNum, ip);
                    continue;
                }

                HostCredential credential = new HostCredential();
                credential.setIp(ip);
                credential.setSshPort(sshPort);
                credential.setSshUsername(sshUsername);
                credential.setSshPasswordEncrypted(cryptoUtils.encrypt(sshPassword));
                credential.setAuthType("password");
                credential.setHostName(hostName.isEmpty() ? null : hostName);
                credential.setProvisionStatus("PENDING");
                credential.setProvisionLog("");

                credential = credentialRepository.save(credential);
                results.add(toVO(credential));
                // Auto-deploy after import
                self().startProvisionAsync(credential.getId());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "CSV解析失败: " + e.getMessage());
        }
        return results;
    }

    @Override
    public byte[] generateCsvTemplate() {
        String bom = "\uFEFF";
        String header = "ip,ssh_port,ssh_username,ssh_password,host_name";
        String example = "192.168.1.100,22,root,your_password,Web服务器1";
        String content = bom + header + "\n" + example + "\n";
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
