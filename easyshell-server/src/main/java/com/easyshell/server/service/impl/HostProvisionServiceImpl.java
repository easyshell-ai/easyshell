package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.HostProvisionRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.HostCredential;
import com.easyshell.server.model.vo.HostCredentialVO;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.HostCredentialRepository;
import com.easyshell.server.service.HostProvisionService;
import com.easyshell.server.util.CryptoUtils;
import com.jcraft.jsch.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HostProvisionServiceImpl implements HostProvisionService {

    private final HostCredentialRepository credentialRepository;
    private final AgentRepository agentRepository;
    private final CryptoUtils cryptoUtils;
    private final TransactionTemplate transactionTemplate;

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

        HostCredential credential = new HostCredential();
        credential.setIp(request.getIp());
        credential.setSshPort(request.getSshPort() != null ? request.getSshPort() : 22);
        credential.setSshUsername(request.getSshUsername());
        credential.setSshPasswordEncrypted(cryptoUtils.encrypt(request.getSshPassword()));
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
                startReinstallAsync(vo.getId());
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
            String password = cryptoUtils.decrypt(credential.getSshPasswordEncrypted());

            saveStatus(credentialId, "UNINSTALLING", "正在连接 " + credential.getIp() + ":" + credential.getSshPort() + " ...");

            JSch jsch = new JSch();
            session = jsch.getSession(credential.getSshUsername(), credential.getIp(), credential.getSshPort());
            session.setPassword(password);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.setTimeout(30000);
            session.connect(30000);

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

            // Clean up DB records
            String agentId = credential.getAgentId();
            transactionTemplate.executeWithoutResult(status -> {
                if (agentId != null) {
                    agentRepository.deleteById(agentId);
                }
                credentialRepository.deleteById(credentialId);
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
            String password = cryptoUtils.decrypt(credential.getSshPasswordEncrypted());

            saveStatus(credentialId, "CONNECTING", "正在连接 " + credential.getIp() + ":" + credential.getSshPort() + " ...");

            JSch jsch = new JSch();
            session = jsch.getSession(credential.getSshUsername(), credential.getIp(), credential.getSshPort());
            session.setPassword(password);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.setTimeout(30000);
            session.connect(30000);

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

            String agentYaml = "server:\n  url: " + serverUrl + "\n\nagent:\n  id: \"\"\n\nheartbeat:\n  interval: 30\n\nmetrics:\n  interval: 60\n\nlog:\n  level: info\n";

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

    private HostCredentialVO toVO(HostCredential entity) {
        return HostCredentialVO.builder()
                .id(entity.getId())
                .ip(entity.getIp())
                .sshPort(entity.getSshPort())
                .sshUsername(entity.getSshUsername())
                .agentId(entity.getAgentId())
                .provisionStatus(entity.getProvisionStatus())
                .provisionLog(entity.getProvisionLog())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FMT) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FMT) : null)
                .build();
    }
}
