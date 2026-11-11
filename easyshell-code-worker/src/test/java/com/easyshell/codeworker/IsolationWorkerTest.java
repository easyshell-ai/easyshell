package com.easyshell.codeworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Mechanisms only; fixed trusted probes, no external isolation claims. */
class IsolationWorkerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode run(String language, String source, int timeout) throws Exception {
        byte[] input = JSON.writeValueAsBytes(Map.of("language", language, "source", source,
                "input", Map.of("value", "quoted'\\\"雪", "n", 3), "timeoutMs", timeout));
        return JSON.readTree(ExecutionCore.runIsolated(new ByteArrayInputStream(input)));
    }

    @Test void pythonAndJavascriptCopyJson() throws Exception {
        for (String language : new String[] {"python", "javascript"}) {
            String code = language.equals("python") ? "def main(i):\n return i"
                    : "function main(i) { return i; }";
            JsonNode result = run(language, code, 5000);
            assertEquals("succeeded", result.path("status").asText());
            assertEquals("quoted'\\\"雪", result.path("output").path("value").asText());
            assertEquals(3, result.path("output").path("n").intValue());
        }
    }

    @Test void syntheticEnvironmentAndHomeAreInvisible() throws Exception {
        assertNotNull(System.getenv("EASYSHELL_PROBE_MARKER"));
        assertNotNull(System.getenv("GRAAL_PYTHONHOME"));
        JsonNode result = run("python", """
                import os, posix
                def main(i):
                    return (os.getenv('EASYSHELL_PROBE_MARKER') is None
                        and os.getenv('GRAAL_PYTHONHOME') is None
                        and dict(os.environ) == {'UNSAFE_PYO3_SKIP_VERSION_CHECK': '1'}
                        and os.environb == {b'UNSAFE_PYO3_SKIP_VERSION_CHECK': b'1'}
                        and posix.environ == {b'UNSAFE_PYO3_SKIP_VERSION_CHECK': b'1'})
                """, 5000);
        assertTrue(result.path("output").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "return open('/proc/self/environ').read()",
            "return open('build.gradle.kts').read()",
            "import socket; return socket.create_connection(('127.0.0.1', 9), timeout=0.1)",
            "import java; return java.type('java.lang.System')",
            "import subprocess; return subprocess.run(['/nonexistent-synthetic-probe'])",
            "import threading; t = threading.Thread(target=lambda: None); t.start(); return True",
            "import ctypes; return ctypes.CDLL(None)"
    })
    void pythonCapabilitiesFail(String body) throws Exception {
        JsonNode result = run("python", "def main(i):\n " + body, 5000);
        assertEquals("failed", result.path("status").asText());
        assertEquals("EXECUTION_FAILED", result.path("error").asText());
        assertEquals(2, result.size());
    }

    @Test void bothLanguagesCancelInfiniteLoops() throws Exception {
        assertEquals("TIMEOUT", run("python", "def main(i):\n while True: pass", 1000).path("error").asText());
        assertEquals("TIMEOUT", run("javascript", "function main(i) { while(true) {} }", 1000).path("error").asText());
    }

    @Test void outputLimitsAndNonJsonValues() throws Exception {
        assertEquals("RESPONSE_TOO_LARGE", run("python", "def main(i):\n return 'x' * 70000", 5000).path("error").asText());
        assertEquals("EXECUTION_FAILED", run("python", "def main(i):\n return float('nan')", 5000).path("error").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{} {}", "{}", "{\"x\":NaN}", "{\"language\":1,\"language\":2}"})
    void malformedJsonRejected(String input) throws Exception {
        assertEquals("INVALID_REQUEST", JSON.readTree(ExecutionCore.runIsolated(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)))).path("error").asText());
    }

    @Test void requestLimitRemainsBounded() throws Exception {
        assertEquals("REQUEST_TOO_LARGE", JSON.readTree(ExecutionCore.runIsolated(
                new ByteArrayInputStream(new byte[65537]))).path("error").asText());
    }

    @Test void entryPointRejectsArgumentsWithoutReadingInput() throws Exception {
        InputStream previousIn = System.in;
        PrintStream previousOut = System.out;
        var output = new ByteArrayOutputStream();
        try {
            System.setIn(new InputStream() {
                @Override public int read() { fail("arguments must not enable execution"); return -1; }
            });
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            IsolationWorker.main(new String[] {"--enable"});
            assertEquals("INVALID_REQUEST", JSON.readTree(output.toByteArray()).path("error").asText());
        } finally {
            System.setIn(previousIn);
            System.setOut(previousOut);
        }
    }

    @Test void entryPointOutputsOnlyJson() throws Exception {
        InputStream previousIn = System.in;
        PrintStream previousOut = System.out;
        var output = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(JSON.writeValueAsBytes(Map.of(
                    "language", "python", "source", "def main(i):\n print('discarded')\n return i",
                    "input", Map.of("n", 7), "timeoutMs", 5000))));
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            IsolationWorker.main(new String[0]);
            JsonNode result = JSON.readTree(output.toByteArray());
            assertEquals("succeeded", result.path("status").asText());
            assertEquals(7, result.path("output").path("n").intValue());
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("discarded"));
        } finally {
            System.setIn(previousIn);
            System.setOut(previousOut);
        }
    }
}
