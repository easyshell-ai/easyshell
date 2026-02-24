package com.easyshell.server.ai.service;

import com.easyshell.server.ai.model.entity.HostSoftwareInventory;
import com.easyshell.server.ai.repository.HostSoftwareInventoryRepository;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.AgentTag;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Tag;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.AgentTagRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TagRepository;
import com.easyshell.server.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HostIntelligenceService {

    private final TaskService taskService;
    private final JobRepository jobRepository;
    private final AgentRepository agentRepository;
    private final TagRepository tagRepository;
    private final AgentTagRepository agentTagRepository;
    private final HostSoftwareInventoryRepository inventoryRepository;

    private static final String DETECTION_SCRIPT = """
            #!/bin/bash
            echo "===DETECTION_START==="
            echo "--- SOFTWARE ---"
            for svc in nginx apache2 httpd mysql mysqld mariadb postgres postgresql java php-fpm node python3 redis-server mongod docker; do
                pid=$(pgrep -x "$svc" 2>/dev/null | head -1)
                if [ -z "$pid" ]; then
                    pid=$(pgrep -f "^/.*/$svc" 2>/dev/null | head -1)
                fi
                if [ -n "$pid" ]; then
                    ver=""
                    ports=""
                    stype="service"
                    case "$svc" in
                        nginx) ver=$(nginx -v 2>&1 | sed 's/.*\\///' | head -1); stype="service" ;;
                        apache2|httpd) ver=$(apache2 -v 2>/dev/null | head -1 || httpd -v 2>/dev/null | head -1); stype="service" ;;
                        mysql|mysqld|mariadb) ver=$(mysql --version 2>/dev/null | grep -oP '\\d+\\.\\d+\\.\\d+' | head -1); stype="database" ;;
                        postgres|postgresql) ver=$(postgres --version 2>/dev/null | grep -oP '\\d+\\.\\d+' | head -1 || psql --version 2>/dev/null | grep -oP '\\d+\\.\\d+' | head -1); stype="database" ;;
                        java) ver=$(java -version 2>&1 | head -1 | grep -oP '"[^"]+"' | tr -d '"'); stype="runtime" ;;
                        php-fpm) ver=$(php -v 2>/dev/null | head -1 | grep -oP '\\d+\\.\\d+\\.\\d+'); stype="runtime" ;;
                        node) ver=$(node --version 2>/dev/null | tr -d 'v'); stype="runtime" ;;
                        python3) ver=$(python3 --version 2>&1 | grep -oP '\\d+\\.\\d+\\.\\d+'); stype="runtime" ;;
                        redis-server) ver=$(redis-server --version 2>/dev/null | grep -oP 'v=\\K[\\d.]+'); stype="database" ;;
                        mongod) ver=$(mongod --version 2>/dev/null | grep -oP 'v\\K[\\d.]+' | head -1); stype="database" ;;
                        docker) ver=$(docker --version 2>/dev/null | grep -oP '\\d+\\.\\d+\\.\\d+'); stype="container_engine" ;;
                    esac
                    ports=$(ss -tlnp 2>/dev/null | grep "pid=$pid" | awk '{print $4}' | grep -oP '\\d+$' | sort -u | tr '\\n' ',' | sed 's/,$//')
                    echo "SW|$svc|$ver|$stype|$pid|$ports"
                fi
            done
            echo "--- DOCKER_CONTAINERS ---"
            if command -v docker &> /dev/null && docker info &>/dev/null; then
                docker ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}' 2>/dev/null
            else
                echo "NO_DOCKER"
            fi
            echo "===DETECTION_END==="
            """;

    public String triggerDetection(String agentId, Long userId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException(404, "主机不存在: " + agentId));

        if (agent.getStatus() != 1) {
            throw new BusinessException(400, "主机不在线，无法探测");
        }

        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("[AI] 软件探测 - " + agent.getHostname());
        request.setScriptContent(DETECTION_SCRIPT);
        request.setAgentIds(List.of(agentId));
        request.setTimeoutSeconds(30);

        Task task = taskService.createAndDispatch(request, userId);
        task.setSource("ai_detect");
        log.info("Triggered software detection for agent {} ({}), taskId={}", agentId, agent.getHostname(), task.getId());
        return task.getId();
    }

    @Transactional
    public List<HostSoftwareInventory> parseAndStore(String taskId, String agentId) {
        List<Job> jobs = jobRepository.findByTaskId(taskId);
        Job targetJob = jobs.stream()
                .filter(j -> agentId.equals(j.getAgentId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "未找到该主机的探测任务"));

        // 0=pending, 1=running, 2=success, 3=failed, 4=timeout, 5=cancelled
        if (targetJob.getStatus() == 0 || targetJob.getStatus() == 1) {
            throw new BusinessException(202, "探测任务仍在执行中");
        }

        if (targetJob.getStatus() != 2) {
            throw new BusinessException(400, "探测任务执行失败，退出码: " + targetJob.getExitCode());
        }

        String output = targetJob.getOutput();
        if (output == null || !output.contains("===DETECTION_START===")) {
            throw new BusinessException(400, "探测输出格式异常");
        }

        inventoryRepository.deleteByAgentId(agentId);

        LocalDateTime now = LocalDateTime.now();
        List<HostSoftwareInventory> results = new ArrayList<>();
        Set<String> autoTags = new HashSet<>();

        String[] lines = output.split("\n");
        boolean inSoftware = false;
        boolean inDocker = false;

        for (String line : lines) {
            line = line.trim();

            if ("--- SOFTWARE ---".equals(line)) {
                inSoftware = true;
                inDocker = false;
                continue;
            }
            if ("--- DOCKER_CONTAINERS ---".equals(line)) {
                inSoftware = false;
                inDocker = true;
                continue;
            }
            if ("===DETECTION_END===".equals(line)) {
                break;
            }

            if (inSoftware && line.startsWith("SW|")) {
                HostSoftwareInventory item = parseSoftwareLine(line, agentId, now);
                if (item != null) {
                    results.add(inventoryRepository.save(item));
                    collectAutoTags(item.getSoftwareName(), item.getSoftwareType(), autoTags);
                }
            }

            if (inDocker && !"NO_DOCKER".equals(line) && !line.isEmpty()) {
                HostSoftwareInventory item = parseDockerLine(line, agentId, now);
                if (item != null) {
                    results.add(inventoryRepository.save(item));
                    autoTags.add("docker");
                }
            }
        }

        applyAutoTags(agentId, autoTags);

        log.info("Parsed {} software/container entries for agent {}, auto-tags: {}", results.size(), agentId, autoTags);
        return results;
    }

    public List<HostSoftwareInventory> getSoftwareList(String agentId) {
        return inventoryRepository.findByAgentIdAndIsDockerContainerFalseOrderByDetectedAtDesc(agentId);
    }

    public List<HostSoftwareInventory> getDockerContainers(String agentId) {
        return inventoryRepository.findByAgentIdAndIsDockerContainerTrueOrderByDetectedAtDesc(agentId);
    }

    public List<HostSoftwareInventory> getAllInventory(String agentId) {
        return inventoryRepository.findByAgentIdOrderByDetectedAtDesc(agentId);
    }

    private HostSoftwareInventory parseSoftwareLine(String line, String agentId, LocalDateTime now) {
        // Format: SW|name|version|type|pid|ports
        String[] parts = line.split("\\|", -1);
        if (parts.length < 6) return null;

        try {
            HostSoftwareInventory item = new HostSoftwareInventory();
            item.setAgentId(agentId);
            item.setSoftwareName(parts[1].trim());
            item.setSoftwareVersion(parts[2].trim().isEmpty() ? null : parts[2].trim());
            item.setSoftwareType(parts[3].trim().isEmpty() ? "service" : parts[3].trim());
            item.setProcessId(parts[4].trim().isEmpty() ? null : Integer.parseInt(parts[4].trim()));
            item.setListeningPorts(parts[5].trim().isEmpty() ? null : parts[5].trim());
            item.setDetectedAt(now);
            item.setIsDockerContainer(false);
            return item;
        } catch (Exception e) {
            log.warn("Failed to parse software line: {}", line, e);
            return null;
        }
    }

    private HostSoftwareInventory parseDockerLine(String line, String agentId, LocalDateTime now) {
        // Format from docker ps: name|image|status|ports
        String[] parts = line.split("\\|", -1);
        if (parts.length < 2) return null;

        try {
            HostSoftwareInventory item = new HostSoftwareInventory();
            item.setAgentId(agentId);
            item.setSoftwareName("docker-container");
            item.setSoftwareType("container");
            item.setDetectedAt(now);
            item.setIsDockerContainer(true);
            item.setDockerContainerName(parts[0].trim());
            item.setDockerImage(parts.length > 1 ? parts[1].trim() : null);
            item.setDockerContainerStatus(parts.length > 2 ? parts[2].trim() : null);
            item.setDockerPorts(parts.length > 3 ? parts[3].trim() : null);
            return item;
        } catch (Exception e) {
            log.warn("Failed to parse docker line: {}", line, e);
            return null;
        }
    }

    private void collectAutoTags(String softwareName, String softwareType, Set<String> tags) {
        String name = softwareName.toLowerCase();

        if (name.contains("nginx") || name.contains("apache") || name.contains("httpd")) {
            tags.add("web");
        }
        if ("database".equals(softwareType) || name.contains("mysql") || name.contains("mariadb")
                || name.contains("postgres") || name.contains("redis") || name.contains("mongod")) {
            tags.add("db");
        }
        if (name.contains("redis")) {
            tags.add("cache");
        }
        if (name.contains("docker")) {
            tags.add("docker");
        }
        if (name.contains("java")) {
            tags.add("java");
        }
        if (name.contains("node")) {
            tags.add("nodejs");
        }
        if (name.contains("php")) {
            tags.add("php");
        }
        if (name.contains("python")) {
            tags.add("python");
        }
    }

    private void applyAutoTags(String agentId, Set<String> tagNames) {
        if (tagNames.isEmpty()) return;

        for (String tagName : tagNames) {
            Tag tag = tagRepository.findByName(tagName).orElseGet(() -> {
                Tag newTag = new Tag();
                newTag.setName(tagName);
                newTag.setColor(getTagColor(tagName));
                return tagRepository.save(newTag);
            });

            if (!agentTagRepository.existsByAgentIdAndTagId(agentId, tag.getId())) {
                AgentTag agentTag = new AgentTag();
                agentTag.setAgentId(agentId);
                agentTag.setTagId(tag.getId());
                agentTagRepository.save(agentTag);
                log.debug("Auto-tagged agent {} with '{}'", agentId, tagName);
            }
        }
    }

    private String getTagColor(String tagName) {
        return switch (tagName) {
            case "web" -> "#1890ff";
            case "db" -> "#52c41a";
            case "cache" -> "#fa8c16";
            case "docker" -> "#13c2c2";
            case "java" -> "#722ed1";
            case "nodejs" -> "#389e0d";
            case "php" -> "#8c8c8c";
            case "python" -> "#fadb14";
            default -> "#1890ff";
        };
    }
}
