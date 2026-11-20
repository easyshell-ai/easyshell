package com.easyshell.server.workflow.code;

import com.easyshell.server.workflow.properties.WorkflowCodeWorkerProperties;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in proof against the dedicated local control plane; no Spring context or database.
 * Uses the production JDK HttpClient transport and deployment-supplied temporary PKCS12 files.
 * Passwords are synthetic test credentials and must never appear in assertion diagnostics.
 */
@EnabledIfEnvironmentVariable(named = "EASYSHELL_CODE_MTLS_PROOF", matches = "true")
class WorkflowCodeWorkerMtlsIntegrationTest {
    private static final String ENDPOINT = "https://localhost:19443";

    @ParameterizedTest
    @ValueSource(strings = {"javascript", "python"})
    @Timeout(60)
    void verifiedHealthAndMtlsExecutionReturnFour(String language) {
        String source = "javascript".equals(language)
                ? "function main(input) { return input.value * 2; }"
                : "def main(input):\n    return input['value'] * 2\n";
        try (WorkflowCodeWorkerClient client = realClient()) {
            // isAvailable checks configuration only. execute independently requires ready,
            // policyVerified=true from the health endpoint over this same mTLS transport.
            assertTrue(client.isAvailable(), "Proof client configuration must be complete");
            var result = client.execute(language, source, Map.of("value", 2), 5000);

            assertEquals(200, result.httpStatus());
            assertEquals("succeeded", result.status());
            assertTrue(result.succeeded());
            assertNull(result.errorCode());
            assertNotNull(result.requestId());
            Number output = assertInstanceOf(Number.class, result.output());
            assertEquals(4.0, output.doubleValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript", "python"})
    @Timeout(60)
    void infiniteLoopReturnsTerminalGuestTimeout(String language) {
        String source = "javascript".equals(language)
                ? "function main(input) { while (true) {} }"
                : "def main(input):\n    while True:\n        pass\n";
        try (WorkflowCodeWorkerClient client = realClient()) {
            assertTrue(client.isAvailable(), "Proof client configuration must be complete");
            var result = client.execute(language, source, Map.of(), 1000);

            assertFalse(result.succeeded());
            assertEquals("failed", result.status());
            assertEquals("TIMEOUT", result.errorCode());
            assertNull(result.output());
            assertNotNull(result.requestId());
        }
    }

    private static WorkflowCodeWorkerClient realClient() {
        WorkflowCodeWorkerProperties properties = new WorkflowCodeWorkerProperties();
        properties.setEnabled(true);
        // Deliberately fixed: enabling this test never authorizes another network target.
        properties.setEndpoint(ENDPOINT);
        properties.setClientKeyStore(absoluteStore("EASYSHELL_CODE_WORKER_CLIENT_KEYSTORE"));
        properties.setTrustStore(absoluteStore("EASYSHELL_CODE_WORKER_TRUSTSTORE"));
        properties.setRequestTimeoutMs(25_000);
        return new WorkflowCodeWorkerClient(properties,
                requiredEnvironment("EASYSHELL_CODE_WORKER_CLIENT_KEYSTORE_PASSWORD"),
                requiredEnvironment("EASYSHELL_CODE_WORKER_TRUSTSTORE_PASSWORD"));
    }

    private static String absoluteStore(String name) {
        String value = requiredEnvironment(name);
        boolean absolute;
        try {
            absolute = Path.of(value).isAbsolute();
        } catch (RuntimeException invalid) {
            throw new AssertionError("Proof PKCS12 path is invalid: " + name);
        }
        assertTrue(absolute, "Proof PKCS12 path must be absolute: " + name);
        return value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "Missing proof setting: " + name);
        return value;
    }
}
