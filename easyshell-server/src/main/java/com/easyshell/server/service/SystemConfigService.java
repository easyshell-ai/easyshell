package com.easyshell.server.service;

import com.easyshell.server.model.dto.SystemConfigRequest;
import com.easyshell.server.model.vo.SystemConfigVO;

import java.util.List;
import java.util.Map;

public interface SystemConfigService {

    List<SystemConfigVO> findAll();

    SystemConfigVO findByKey(String configKey);

    List<SystemConfigVO> findByGroup(String configGroup);

    SystemConfigVO save(SystemConfigRequest request);

    SystemConfigVO update(Long id, SystemConfigRequest request);

    void delete(Long id);

    Map<String, String> getAgentConfig();
}
