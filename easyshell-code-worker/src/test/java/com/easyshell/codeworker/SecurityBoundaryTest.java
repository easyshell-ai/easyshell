package com.easyshell.codeworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Mechanism tests only: none of these prove external resource isolation. */
class SecurityBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode execute(String language, String source) throws Exception {
        byte[] request = JSON.writeValueAsBytes(Map.of("language", language, "source", source,
                "input", Map.of(), "timeoutMs", 5000));
        return JSON.readTree(ExecutionCore.run(new ByteArrayInputStream(request)));
    }

    @Test void pythonCannotReadHostFilesOrStartProcesses() throws Exception {
        assertEquals("ISOLATION_UNVERIFIED", execute("python",
                "def main(i):\n return open('/etc/passwd').read()").path("error").asText());
        assertEquals("ISOLATION_UNVERIFIED", execute("python",
                "def main(i):\n import subprocess\n return subprocess.check_output(['id']).decode()").path("error").asText());
    }

    @Test void pythonCannotSeeHostEnvironment() throws Exception {
        JsonNode result = execute("python", "import os\ndef main(i):\n return len(os.environ)");
        assertEquals("failed", result.path("status").asText());
        assertEquals("ISOLATION_UNVERIFIED", result.path("error").asText());
        assertEquals(2, result.size());
    }

    @Test void pythonIsRejectedBeforeAnyTopLevelCodeRuns() throws Exception {
        JsonNode result = execute("python", "raise Exception('should never execute')");
        assertEquals("failed", result.path("status").asText());
        assertEquals("ISOLATION_UNVERIFIED", result.path("error").asText());
        assertEquals(2, result.size());
    }

    @Test void entryPointNeverReadsInputEvenWhenArgumentsAreProvided() throws Exception {
        InputStream oldIn = System.in;
        PrintStream oldOut = System.out;
        var capture = new ByteArrayOutputStream();
        try {
            System.setIn(new InputStream() {
                @Override public int read() { fail("entry point must not read untrusted input"); return -1; }
            });
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            Worker.main(new String[] {"--enable", "--unsafe"});
        } finally {
            System.setIn(oldIn);
            System.setOut(oldOut);
        }
        JsonNode result = JSON.readTree(capture.toByteArray());
        assertEquals("failed", result.path("status").asText());
        assertEquals("ISOLATION_UNVERIFIED", result.path("error").asText());
        assertEquals(2, result.size());
    }

    @Test void exceptionsDoNotEchoSourceOrInput() throws Exception {
        String secret = "unique-sensitive-marker-12974";
        byte[] request = JSON.writeValueAsBytes(Map.of("language", "javascript",
                "source", "function main(i) { throw new Error('" + secret + "'); }",
                "input", Map.of("secret", secret), "timeoutMs", 5000));
        String response = new String(ExecutionCore.run(new ByteArrayInputStream(request)), StandardCharsets.UTF_8);
        assertEquals("EXECUTION_FAILED", JSON.readTree(response).path("error").asText());
        assertFalse(response.contains(secret));
    }
}
