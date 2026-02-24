package com.easyshell.server;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Disabled("Requires full infrastructure (Redis, ClickHouse) — run in CI with docker-compose")
class EasyShellServerApplicationTests {

    @Test
    void contextLoads() {
    }
}
