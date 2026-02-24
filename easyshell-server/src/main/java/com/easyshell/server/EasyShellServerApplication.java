package com.easyshell.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class EasyShellServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EasyShellServerApplication.class, args);
    }
}
