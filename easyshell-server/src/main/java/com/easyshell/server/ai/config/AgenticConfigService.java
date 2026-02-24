package com.easyshell.server.ai.config;

import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgenticConfigService {

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;

    public String get(String key, String defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(config -> {
                    try {
                        return Integer.parseInt(config.getConfigValue().trim());
                    } catch (NumberFormatException e) {
                        log.warn("Invalid int value for config key '{}': '{}', using default: {}",
                                key, config.getConfigValue(), defaultValue);
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    public double getDouble(String key, double defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(config -> {
                    try {
                        return Double.parseDouble(config.getConfigValue().trim());
                    } catch (NumberFormatException e) {
                        log.warn("Invalid double value for config key '{}': '{}', using default: {}",
                                key, config.getConfigValue(), defaultValue);
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    /** Recognizes: "true", "1", "yes", "on" (case-insensitive) as true. */
    public boolean getBoolean(String key, boolean defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(config -> {
                    String val = config.getConfigValue().trim().toLowerCase();
                    return "true".equals(val) || "1".equals(val)
                            || "yes".equals(val) || "on".equals(val);
                })
                .orElse(defaultValue);
    }

    /** Parses stored value as JSON array, e.g. ["rm", "mkfs", "dd"] */
    public Set<String> getStringSet(String key, Set<String> defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(config -> {
                    String raw = config.getConfigValue().trim();
                    if (raw.isEmpty() || "[]".equals(raw)) {
                        return defaultValue;
                    }
                    try {
                        List<String> list = objectMapper.readValue(raw, new TypeReference<>() {});
                        return new LinkedHashSet<>(list);
                    } catch (Exception e) {
                        log.warn("Invalid JSON array for config key '{}': '{}', using default",
                                key, raw);
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    /** Parses stored value as JSON array, e.g. ["rm -rf /", "mkfs"] */
    public List<String> getStringList(String key, List<String> defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(config -> {
                    String raw = config.getConfigValue().trim();
                    if (raw.isEmpty() || "[]".equals(raw)) {
                        return defaultValue;
                    }
                    try {
                        return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
                    } catch (Exception e) {
                        log.warn("Invalid JSON array for config key '{}': '{}', using default",
                                key, raw);
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(config -> {
                    try {
                        return Long.parseLong(config.getConfigValue().trim());
                    } catch (NumberFormatException e) {
                        log.warn("Invalid long value for config key '{}': '{}', using default: {}",
                                key, config.getConfigValue(), defaultValue);
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }
}
