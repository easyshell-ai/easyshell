package com.easyshell.codeworker;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/** Trusted destructive acceptance probe ONLY. Never run on the host or outside a verified cgroup. */
public final class MemoryLimitProbe {
    private MemoryLimitProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No arguments accepted");
        // Wait for trusted host guard verification and full delivery. Payload is ignored, never eval'd.
        if (System.in.readNBytes(65537).length > 65536) return;
        var retained = new ArrayList<ByteBuffer>();
        for (int i = 0; i < 128; i++) {
            ByteBuffer block = ByteBuffer.allocateDirect(8 * 1024 * 1024);
            for (int offset = 0; offset < block.capacity(); offset += 4096) block.put(offset, (byte) 1);
            retained.add(block);
        }
        // Java/direct limit failure or survival is never kernel-OOM proof.
        System.out.println("{\"status\":\"failed\",\"error\":\"EXECUTION_FAILED\"}");
    }
}
