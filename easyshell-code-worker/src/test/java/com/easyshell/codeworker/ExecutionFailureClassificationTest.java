package com.easyshell.codeworker;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionFailureClassificationTest {
    @Test void initializationAndLinkageFailuresNeverBecomeInvalidRequestOrLeakMessages() {
        for (Throwable failure : new Throwable[] {
                new IllegalArgumentException("synthetic-private-option"),
                new IllegalStateException("synthetic-private-resource-path"),
                new SecurityException("synthetic-private-io"),
                new UnsatisfiedLinkError("synthetic-private-library")}) {
            byte[] result = ExecutionCore.executionBoundary(() -> {
                if (failure instanceof Exception exception) throw exception;
                throw (LinkageError) failure;
            });
            assertEquals("{\"status\":\"failed\",\"error\":\"EXECUTION_FAILED\"}",
                    new String(result, StandardCharsets.UTF_8));
            assertFalse(new String(result, StandardCharsets.UTF_8).contains("synthetic-private"));
        }
    }

    @Test void diagnosticCategoriesAreFixedForInitializationFailures() {
        assertEquals("CONFIGURATION_ERROR", PythonDiagnosticWorker.category(new IllegalArgumentException("secret")));
        assertEquals("STATE_ERROR", PythonDiagnosticWorker.category(new IllegalStateException("secret")));
        assertEquals("SECURITY_EXCEPTION", PythonDiagnosticWorker.category(new SecurityException("secret")));
        assertEquals("LINKAGE_ERROR", PythonDiagnosticWorker.category(new UnsatisfiedLinkError("secret")));
    }
}
