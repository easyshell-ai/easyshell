package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/system/version")
public class VersionController {

    @Value("${easyshell.version:1.1.1}")
    private String version;

    @GetMapping
    public R<Map<String, String>> getVersion() {
        String normalized = version.startsWith("v") || version.startsWith("V")
                ? version.substring(1) : version;
        return R.ok(Map.of(
                "version", version,
                "agentVersion", normalized
        ));
    }
}
