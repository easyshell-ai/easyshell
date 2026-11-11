package com.easyshell.codeworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Fixed, trusted probes only. This class is absent from the production distribution. */
class GraalPythonEnvironmentProbeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_JSON_BYTES = 4096;
    private static final String MARKER = "synthetic-environment-marker-25-2-4";
    private static final String HOME = "/nonexistent-easyshell-synthetic-python-home-25-2-4";

    @Test
    @Timeout(60)
    void environmentAndHostCapabilitiesAreRestricted() throws Exception {
        // Prove the fixture exists in the actual test JVM before initializing Python.
        assertEquals(MARKER, System.getenv("EASYSHELL_PROBE_MARKER"));
        assertEquals(HOME, System.getenv("GRAAL_PYTHONHOME"));
        Path readableFile = Path.of("build.gradle.kts").toAbsolutePath();
        assertTrue(Files.isReadable(readableFile));
        String input = JSON.writeValueAsString(Map.of("file", readableFile.toString(),
                "java", Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        assertTrue(input.getBytes(StandardCharsets.UTF_8).length <= MAX_JSON_BYTES);

        try (Context context = Context.newBuilder("python")
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(name -> false)
                .allowHostClassLoading(false)
                .allowIO(IOAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .option("python.PosixModuleBackend", "java")
                .in(InputStream.nullInputStream())
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .build()) {
            context.eval("python", """
                    import json, os, posix

                    def denied(operation):
                        try:
                            operation()
                        except Exception:
                            return True
                        return False

                    def host_lookup():
                        import java
                        return java.type('java.lang.System')

                    def read_file(path):
                        with open(path) as f:
                            return f.read(1)

                    def process(java):
                        import subprocess
                        return subprocess.run([java, '-version'], capture_output=True, timeout=2)

                    def thread():
                        import threading
                        t = threading.Thread(target=lambda: None, daemon=True)
                        t.start()
                        t.join(2)

                    def native():
                        import ctypes
                        return ctypes.CDLL(None)

                    def probe(i):
                        return {
                            'environment_runtime_constant_only': dict(os.environ) == {'UNSAFE_PYO3_SKIP_VERSION_CHECK': '1'},
                            'bytes_environment_runtime_constant_only': dict(os.environb) == {b'UNSAFE_PYO3_SKIP_VERSION_CHECK': b'1'},
                            'posix_environment_runtime_constant_only': posix.environ == {b'UNSAFE_PYO3_SKIP_VERSION_CHECK': b'1'},
                            'marker_absent': os.getenv('EASYSHELL_PROBE_MARKER') is None,
                            'home_absent': os.getenv('GRAAL_PYTHONHOME') is None,
                            'host_denied': denied(host_lookup),
                            'file_denied': denied(lambda: read_file(i['file'])),
                            'process_denied': denied(lambda: process(i['java'])),
                            'native_denied': denied(native)
                        }
                    """);
            String response = context.eval("python", "json.dumps(probe(json.loads("
                    + JSON.writeValueAsString(input) + ")))").asString();
            assertTrue(response.getBytes(StandardCharsets.UTF_8).length <= MAX_JSON_BYTES);
            JsonNode result = JSON.readTree(response);
            assertEquals(9, result.size());
            result.fields().forEachRemaining(entry ->
                    assertTrue(entry.getValue().isBoolean() && entry.getValue().booleanValue(), entry.getKey()));
            assertFalse(response.contains(MARKER));
            assertFalse(response.contains(HOME));
            PolyglotException threadFailure = assertThrows(PolyglotException.class,
                    () -> context.eval("python", "thread()"));
            assertTrue(threadFailure.getMessage().contains("Creating threads is not allowed."));
        }
    }
}
