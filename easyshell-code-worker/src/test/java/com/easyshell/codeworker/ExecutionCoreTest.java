package com.easyshell.codeworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionCoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private String run(String language, String source, int timeout) throws Exception {
        byte[] request = JSON.writeValueAsBytes(Map.of("language", language, "source", source,
                "input", Map.of("x", 3), "timeoutMs", timeout));
        return new String(ExecutionCore.run(new ByteArrayInputStream(request)), StandardCharsets.UTF_8);
    }

    @Test void javascriptExecutesAndPythonFailsClosed() throws Exception {
        assertEquals(4, JSON.readTree(run("javascript", "function main(input) { return {n: input.x+1}; }", 5000)).path("output").path("n").intValue());
        assertEquals("ISOLATION_UNVERIFIED", JSON.readTree(run("python", "def main(input):\n return {'n': input['x']+1}", 5000)).path("error").asText());
    }

    @Test void rejectsHostAccessAndTimesOut() throws Exception {
        assertTrue(run("javascript", "function main(i) { return Java.type('java.lang.System'); }", 5000).contains("EXECUTION_FAILED"));
        assertTrue(run("javascript", "function main(i) { while(true) {} }", 2000).contains("TIMEOUT"));
    }

    @Test void limitsAndMalformedRequests() throws Exception {
        assertTrue(new String(ExecutionCore.run(new ByteArrayInputStream(new byte[ExecutionCore.MAX_REQUEST + 1])), StandardCharsets.UTF_8).contains("REQUEST_TOO_LARGE"));
        assertTrue(run("javascript", "function main(i) { return 'x'.repeat(70000); }", 5000).contains("RESPONSE_TOO_LARGE"));
        assertTrue(new String(ExecutionCore.run(new ByteArrayInputStream("{} {}".getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8).contains("INVALID_REQUEST"));
    }

    @Test void entryPointAlwaysFailsClosed() throws Exception {
        PrintStream previous = System.out;
        var capture = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            Worker.main(new String[0]);
        } finally {
            System.setOut(previous);
        }
        assertEquals("ISOLATION_UNVERIFIED", JSON.readTree(capture.toByteArray()).path("error").asText());
    }
}
