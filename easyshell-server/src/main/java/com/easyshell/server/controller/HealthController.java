package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public R<String> health() {
        return R.ok("EasyShell Server is running");
    }
}
