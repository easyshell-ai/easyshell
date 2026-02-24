package com.easyshell.server.service;

import com.easyshell.server.model.dto.ScriptRequest;
import com.easyshell.server.model.entity.Script;

import java.util.List;
import java.util.Optional;

public interface ScriptService {

    Script create(ScriptRequest request, Long userId);

    Script update(Long id, ScriptRequest request);

    void delete(Long id);

    Optional<Script> findById(Long id);

    List<Script> findAll();

    List<Script> findTemplates();

    List<Script> findUserScripts();
}
