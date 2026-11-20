package com.easyshell.server.workflow.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.workflow.node.CodeNodeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Code-sample API boundary.
 *
 * <p>Code samples must not run in the server JVM: a guest can allocate memory that is not
 * covered by the existing execution limits and take down the server. Deployment-owned
 * mTLS worker configuration is required before reading the body; actual execution also
 * requires the worker's verified policy health. No database administrator setting can
 * enable local execution.</p>
 */
@Service
public class WorkflowCodeSampleService {
    static final String RESOURCE_ISOLATION_UNAVAILABLE = "CODE_SAMPLE_RESOURCE_ISOLATION_UNAVAILABLE";
    private final CodeNodeExecutor executor;
    private final ObjectMapper mapper;
    private com.easyshell.server.workflow.code.WorkflowCodeWorkerClient worker;

    public WorkflowCodeSampleService(CodeNodeExecutor executor, ObjectMapper mapper, SensitiveDataFilter filter) {
        this.executor = executor;
        this.mapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setWorker(com.easyshell.server.workflow.code.WorkflowCodeWorkerClient worker) { this.worker = worker; }

    public Object execute(InputStream body) {
        // Keep deployment configuration admission before any body parsing; this boundary
        // never executes a local guest, including when the remote service is unavailable.
        if (worker == null || !worker.isAvailable()) throw new BusinessException(503, RESOURCE_ISOLATION_UNAVAILABLE);
        try {
            byte[] bytes = body.readNBytes(65_537);
            if (bytes.length > 65_536) throw new BusinessException(413, "CODE_SAMPLE_INPUT_TOO_LARGE");
            var json = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            json.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                    .maxNestingDepth(64).build());
            var tree = json.readTree(bytes);
            if (tree == null || !tree.isObject() || !tree.path("language").isTextual()
                    || !tree.path("code").isTextual() || !tree.path("input").isObject()
                    || tree.size() < 3 || tree.size() > 4) throw new BusinessException(400, "CODE_SAMPLE_INVALID");
            var fields = tree.fieldNames();
            while (fields.hasNext()) if (!java.util.Set.of("language", "code", "input", "timeoutSec").contains(fields.next()))
                throw new BusinessException(400, "CODE_SAMPLE_INVALID");
            int timeout = 5;
            if (tree.has("timeoutSec")) {
                if (!tree.path("timeoutSec").isIntegralNumber() || !tree.path("timeoutSec").canConvertToInt())
                    throw new BusinessException(400, "CODE_SAMPLE_INVALID");
                timeout = tree.path("timeoutSec").intValue();
            }
            if (timeout < 1 || timeout > 5) throw new BusinessException(400, "CODE_SAMPLE_INVALID");
            var node = new com.easyshell.server.workflow.model.WorkflowNode();
            node.setType("code");
            node.setConfig(java.util.Map.of("language", tree.path("language").textValue(),
                    "code", tree.path("code").textValue(), "timeoutSec", timeout));
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> input = json.convertValue(tree.path("input"), java.util.Map.class);
            return executor.executeSample(node, input);
        } catch (BusinessException error) {
            throw error;
        } catch (com.easyshell.server.workflow.engine.NodeFailedException error) {
            throw new BusinessException("CODE_RUNTIME_UNAVAILABLE".equals(error.getCode()) ? 503 : 422, error.getCode());
        } catch (Exception invalid) {
            throw new BusinessException(400, "CODE_SAMPLE_INVALID");
        }
    }
}
