package com.easyshell.server.ai.tool;

import com.easyshell.server.model.dto.HostProvisionRequest;
import com.easyshell.server.model.vo.HostCredentialVO;
import com.easyshell.server.service.HostProvisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HostManageTool {

    private final HostProvisionService hostProvisionService;

    @Tool(description = "添加新主机到EasyShell管理平台。需要提供目标主机的IP地址、SSH端口、SSH用户名和SSH密码。系统会通过SSH连接到目标主机，自动安装并启动Agent。安装过程是异步的，返回部署记录ID供后续查询状态。")
    public String addHost(
            @ToolParam(description = "目标主机IP地址") String ip,
            @ToolParam(description = "SSH端口，默认22") Integer sshPort,
            @ToolParam(description = "SSH用户名") String sshUsername,
            @ToolParam(description = "SSH密码") String sshPassword) {
        try {
            HostProvisionRequest request = new HostProvisionRequest();
            request.setIp(ip);
            request.setSshPort(sshPort != null ? sshPort : 22);
            request.setSshUsername(sshUsername);
            request.setSshPassword(sshPassword);

            HostCredentialVO vo = hostProvisionService.provision(request);
            hostProvisionService.startProvisionAsync(vo.getId());

            return String.format("已提交主机部署任务。\n" +
                    "- 目标IP: %s\n" +
                    "- SSH端口: %d\n" +
                    "- 部署记录ID: %d\n" +
                    "- 当前状态: %s\n" +
                    "Agent正在后台安装中，通常需要1-2分钟完成。安装成功后主机会自动注册到平台。",
                    vo.getIp(), vo.getSshPort(), vo.getId(), vo.getProvisionStatus());
        } catch (Exception e) {
            return "添加主机失败: " + e.getMessage();
        }
    }

    @Tool(description = "卸载并移除主机。会通过SSH远程停止Agent服务、删除Agent文件，并从数据库中移除主机记录。需要主机的agentId。")
    public String removeHost(
            @ToolParam(description = "主机的Agent ID") String agentId) {
        try {
            HostCredentialVO vo = hostProvisionService.uninstall(agentId);
            hostProvisionService.startUninstallAsync(vo.getId());

            return String.format("已提交主机卸载任务。\n" +
                    "- 目标IP: %s\n" +
                    "- 部署记录ID: %d\n" +
                    "- 当前状态: %s\n" +
                    "Agent正在后台卸载中，通常需要30秒-1分钟完成。",
                    vo.getIp(), vo.getId(), vo.getProvisionStatus());
        } catch (Exception e) {
            return "卸载主机失败: " + e.getMessage();
        }
    }
}