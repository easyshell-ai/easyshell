package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.model.dto.AiExecutionRequest;
import com.easyshell.server.ai.model.vo.AiExecutionResult;
import com.easyshell.server.ai.service.AiExecutionService;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SoftwareDetectTool {

    private final AgentRepository agentRepository;
    private final AiExecutionService aiExecutionService;

    private Long currentUserId;
    private String currentSourceIp;

    public void setContext(Long userId, String sourceIp) {
        this.currentUserId = userId;
        this.currentSourceIp = sourceIp;
    }

    @Tool(description = "探测指定主机上运行的软件和服务，包括 Nginx、MySQL、PostgreSQL、Java、PHP、Node.js、Python、Docker 等")
    public String detectSoftware(@ToolParam(description = "主机 ID") String agentId) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return "主机不存在: " + agentId;
        }

        String script = """
                echo "=== 软件探测报告 ==="
                echo "主机: $(hostname) ($(hostname -I | awk '{print $1}'))"
                echo ""
                echo "--- 运行中的服务 ---"
                for svc in nginx apache2 httpd mysql mysqld mariadb postgres postgresql java php-fpm php node python3 python redis-server redis mongod docker containerd; do
                    if pgrep -x "$svc" > /dev/null 2>&1 || pgrep -f "$svc" > /dev/null 2>&1; then
                        ver=""
                        case "$svc" in
                            nginx) ver=$($svc -v 2>&1 | head -1) ;;
                            mysql|mysqld|mariadb) ver=$(mysql --version 2>/dev/null | head -1) ;;
                            postgres|postgresql) ver=$(postgres --version 2>/dev/null || psql --version 2>/dev/null | head -1) ;;
                            java) ver=$(java -version 2>&1 | head -1) ;;
                            node) ver=$(node --version 2>/dev/null) ;;
                            python3|python) ver=$($svc --version 2>&1 | head -1) ;;
                            docker) ver=$(docker --version 2>/dev/null) ;;
                            redis-server|redis) ver=$(redis-server --version 2>/dev/null | head -1) ;;
                        esac
                        echo "  [运行中] $svc ${ver:+- $ver}"
                    fi
                done
                echo ""
                echo "--- Docker 信息 ---"
                if command -v docker &> /dev/null; then
                    echo "Docker 已安装"
                    echo "容器列表:"
                    docker ps --format "  {{.Names}} | {{.Image}} | {{.Status}}" 2>/dev/null || echo "  (无法获取容器信息)"
                    echo "镜像列表:"
                    docker images --format "  {{.Repository}}:{{.Tag}} | {{.Size}}" 2>/dev/null | head -20 || echo "  (无法获取镜像信息)"
                else
                    echo "Docker 未安装"
                fi
                echo ""
                echo "--- 监听端口 ---"
                ss -tlnp 2>/dev/null | grep LISTEN | awk '{print "  " $4 " -> " $6}' | head -30 || netstat -tlnp 2>/dev/null | grep LISTEN | awk '{print "  " $4 " -> " $7}' | head -30
                """;

        AiExecutionRequest request = new AiExecutionRequest();
        request.setScriptContent(script);
        request.setAgentIds(List.of(agentId));
        request.setDescription("软件探测 - " + agent.getHostname());
        request.setTimeoutSeconds(30);
        request.setUserId(currentUserId != null ? currentUserId : 0L);
        request.setSourceIp(currentSourceIp != null ? currentSourceIp : "ai-chat");

        AiExecutionResult result = aiExecutionService.execute(request);
        if ("executed".equals(result.getStatus())) {
            return "软件探测任务已执行，任务ID: " + result.getTaskId() + "。请稍后查看任务结果获取探测报告。";
        }
        return "软件探测失败: " + result.getMessage();
    }
}
