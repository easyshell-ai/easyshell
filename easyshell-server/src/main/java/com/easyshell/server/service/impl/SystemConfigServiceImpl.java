package com.easyshell.server.service.impl;

import com.easyshell.server.ai.config.AiConfigRefreshService;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.SystemConfigRequest;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.model.vo.SystemConfigVO;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private final SystemConfigRepository systemConfigRepository;
    private final AiConfigRefreshService aiConfigRefreshService;

    @Override
    public List<SystemConfigVO> findAll() {
        return systemConfigRepository.findAll().stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public SystemConfigVO findByKey(String configKey) {
        SystemConfig config = systemConfigRepository.findByConfigKey(configKey)
                .orElseThrow(() -> new BusinessException(404, "配置项不存在: " + configKey));
        return toVO(config);
    }

    @Override
    public List<SystemConfigVO> findByGroup(String configGroup) {
        return systemConfigRepository.findByConfigGroup(configGroup).stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional
    public SystemConfigVO save(SystemConfigRequest request) {
        SystemConfig config = systemConfigRepository.findByConfigKey(request.getConfigKey())
                .orElse(null);

        if (config != null) {
            config.setConfigValue(request.getConfigValue());
            if (request.getDescription() != null) {
                config.setDescription(request.getDescription());
            }
            if (request.getConfigGroup() != null) {
                config.setConfigGroup(request.getConfigGroup());
            }
        } else {
            config = new SystemConfig();
            config.setConfigKey(request.getConfigKey());
            config.setConfigValue(request.getConfigValue());
            config.setDescription(request.getDescription());
            config.setConfigGroup(request.getConfigGroup());
        }

        config = systemConfigRepository.save(config);
        
        aiConfigRefreshService.onConfigChanged(request.getConfigKey());
        
        return toVO(config);
    }

    @Override
    @Transactional
    public SystemConfigVO update(Long id, SystemConfigRequest request) {
        SystemConfig config = systemConfigRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "配置项不存在"));

        config.setConfigKey(request.getConfigKey());
        config.setConfigValue(request.getConfigValue());
        if (request.getDescription() != null) {
            config.setDescription(request.getDescription());
        }
        if (request.getConfigGroup() != null) {
            config.setConfigGroup(request.getConfigGroup());
        }

        config = systemConfigRepository.save(config);
        
        aiConfigRefreshService.onConfigChanged(request.getConfigKey());
        
        return toVO(config);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!systemConfigRepository.existsById(id)) {
            throw new BusinessException(404, "配置项不存在");
        }
        systemConfigRepository.deleteById(id);
    }

    @Override
    public Map<String, String> getAgentConfig() {
        Map<String, String> configMap = new HashMap<>();
        List<SystemConfig> agentConfigs = systemConfigRepository.findByConfigGroup("agent");
        for (SystemConfig config : agentConfigs) {
            configMap.put(config.getConfigKey(), config.getConfigValue());
        }
        return configMap;
    }

    private SystemConfigVO toVO(SystemConfig config) {
        return SystemConfigVO.builder()
                .id(config.getId())
                .configKey(config.getConfigKey())
                .configValue(config.getConfigValue())
                .description(config.getDescription())
                .configGroup(config.getConfigGroup())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
