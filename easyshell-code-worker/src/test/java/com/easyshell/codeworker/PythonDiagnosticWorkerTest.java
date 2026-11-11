package com.easyshell.codeworker;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PythonDiagnosticWorkerTest {
    @Test void detailedFixedProbeRedactsEnvironmentAndBoundsMessagesAndFrames() throws Exception {
        Throwable error = new IllegalStateException("private-env-value\n\u001b /opt/fixed/resource " + "x".repeat(2000));
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("trusted.Fixed", "initialize", "private-source-path", 55)});
        String result = PythonDiagnosticWorker.detailedDiagnostic(error, "context_create",
                java.util.Map.of("SYNTHETIC", "private-env-value"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
        assertFalse(result.contains("private-env-value"));
        assertFalse(result.contains("private-source-path"));
        assertTrue(json.path("initialization_messages").get(0).asText().length() <= 1000);
        assertFalse(json.path("initialization_messages").get(0).asText().contains("\n"));
        assertEquals("trusted.Fixed#initialize", json.path("class_methods").get(0).asText());
    }
    @Test void literalProbeNeverReadsStdin() {
        InputStream oldIn = System.in;
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try {
            System.setIn(new InputStream() {
                @Override public int read() { fail("diagnostic must not read stdin"); return -1; }
            });
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            PythonDiagnosticWorker.main(new String[0]);
            assertEquals("{\"status\":\"succeeded\",\"probe\":\"python_literal\"}\n", out.toString(StandardCharsets.UTF_8));
            assertEquals("", err.toString(StandardCharsets.UTF_8));
            out.reset();
            PythonDiagnosticWorker.main(new String[] {"--source"});
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("INVALID_REQUEST"));
        } finally {
            System.setIn(oldIn);
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test void categoriesNeverContainExceptionMessage() {
        assertEquals("SECURITY_EXCEPTION", PythonDiagnosticWorker.category(new SecurityException("synthetic-secret")));
        assertEquals("LINKAGE_ERROR", PythonDiagnosticWorker.category(new UnsatisfiedLinkError("synthetic-secret")));
        assertEquals("RUNTIME_ERROR", PythonDiagnosticWorker.category(new RuntimeException("synthetic-secret")));
    }

    @Test void fixedPredicatesAndBoundedClassChainNeverEchoMessages() throws Exception {
        String secret = "synthetic-private-path-and-content";
        Throwable cause = new SecurityException("file read denied " + secret);
        Throwable error = new IllegalStateException("resource cache user.home zipfs provider thread not allowed " + secret, cause);
        String result = PythonDiagnosticWorker.diagnostic(error, "context_create");
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
        assertEquals(2, json.path("cause_classes").size());
        assertEquals(6, json.path("predicates").size());
        assertFalse(result.contains(secret));
        for (int i = 0; i < 20; i++) error = new RuntimeException(secret, error);
        json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(PythonDiagnosticWorker.diagnostic(error, secret));
        assertEquals(8, json.path("cause_classes").size());
        assertEquals("unknown", json.path("stage").asText());
        assertFalse(json.toString().contains(secret));
    }

    @Test void scratchImagePredicatesOnlyEmitFixedLabels() throws Exception {
        Throwable error = new UnsatisfiedLinkError("synthetic-private-library locale timezone resource passwd");
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.sun.security.auth.module.UnixSystem", "getUnixInfo", "private-path", 123),
                new StackTraceElement("jdk.nio.zipfs.ZipFileSystemProvider", "newFileSystem", "private-path", 456)});
        String result = PythonDiagnosticWorker.diagnostic(error, "context_create");
        var flags = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result).path("predicates").toString();
        for (String label : new String[] {"MISSING_LIBRARY", "LOCALE", "TIMEZONE", "RESOURCE", "USER_LOOKUP", "ZIPFS_PROVIDER"})
            assertTrue(flags.contains(label));
        assertFalse(result.contains("private-path"));
        assertFalse(result.contains("synthetic-private-library"));
    }
}
