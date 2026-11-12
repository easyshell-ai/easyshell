package com.easyshell.codeworker;

import java.nio.charset.StandardCharsets;

/** No self-attestation can establish trusted, enforced cgroup membership. */
public final class Worker {
    private Worker() { }

    public static void main(String[] args) throws Exception {
        System.out.write("{\"status\":\"failed\",\"error\":\"ISOLATION_UNVERIFIED\"}\n"
                .getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
