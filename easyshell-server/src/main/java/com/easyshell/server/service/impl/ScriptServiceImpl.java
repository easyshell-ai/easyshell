package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.ScriptRequest;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.service.ScriptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScriptServiceImpl implements ScriptService {

    private final ScriptRepository scriptRepository;

    @Override
    @Transactional
    public Script create(ScriptRequest request, Long userId) {
        Script script = new Script();
        script.setName(request.getName());
        script.setDescription(request.getDescription());
        script.setContent(request.getContent());
        script.setScriptType(request.getScriptType());
        script.setIsPublic(request.getIsPublic());
        script.setCreatedBy(userId);
        return scriptRepository.save(script);
    }

    @Override
    @Transactional
    public Script update(Long id, ScriptRequest request) {
        Script script = scriptRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Script not found"));
        script.setName(request.getName());
        script.setDescription(request.getDescription());
        script.setContent(request.getContent());
        script.setScriptType(request.getScriptType());
        script.setIsPublic(request.getIsPublic());
        script.setVersion(script.getVersion() + 1);
        return scriptRepository.save(script);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!scriptRepository.existsById(id)) {
            throw new BusinessException(404, "Script not found");
        }
        scriptRepository.deleteById(id);
    }

    @Override
    public Optional<Script> findById(Long id) {
        return scriptRepository.findById(id);
    }

    @Override
    public List<Script> findAll() {
        return scriptRepository.findAll();
    }

    @Override
    public List<Script> findTemplates() {
        return scriptRepository.findByIsTemplateTrue();
    }

    @Override
    public List<Script> findUserScripts() {
        return scriptRepository.findByIsTemplateFalse();
    }
}
