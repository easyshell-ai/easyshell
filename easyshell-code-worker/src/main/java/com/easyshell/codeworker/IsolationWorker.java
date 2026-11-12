package com.easyshell.codeworker;

import java.nio.charset.StandardCharsets;

/**
 * Execution mechanism for a fixed trusted external deployment only.
 * This process cannot attest isolation. Never expose it without verified external
 * resource limits, empty JVM environment, deadlines, and independent cleanup.
 */
public final class IsolationWorker {
    private IsolationWorker() { }

    public static void main(String[] args) throws Exception {
        // No enable flags or request-selected execution mode.
        if (args.length != 0) {
            System.out.write("{\"status\":\"failed\",\"error\":\"INVALID_REQUEST\"}\n"
                    .getBytes(StandardCharsets.UTF_8));
        } else {
            System.out.write(ExecutionCore.runIsolated(System.in));
            System.out.write('\n');
        }
        System.out.flush();
    }
}
