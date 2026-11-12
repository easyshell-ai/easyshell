package com.easyshell.server.workflow.gate;

import com.easyshell.server.repository.WorkflowDispatchApprovalBindingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** Evidence persistence only: this service neither authorizes nor dispatches a task. */
@Service
public class WorkflowDispatchApprovalBindingService {
    private final WorkflowDispatchApprovalBindingRepository repository;
    private final ObjectMapper mapper;

    public WorkflowDispatchApprovalBindingService(WorkflowDispatchApprovalBindingRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public record Intent(String runId, String nodeRunId, int attempt, long userId, String script,
                         List<DispatchIntentHmac.Target> targets, int timeoutSec) {
        public Intent {
            // Freeze the target set before hashing and serializing it. A caller may otherwise
            // mutate its list between those operations or while an intent is awaiting use.
            if (targets == null || targets.stream().anyMatch(t -> t == null)) {
                throw new IllegalArgumentException("Missing dispatch targets");
            }
            targets = List.copyOf(targets);
        }
    }

    private record Evidence(String scriptHmac, String intentHmac, String targets) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public void create(long approvalId, Intent intent) {
        if (approvalId <= 0) throw new IllegalArgumentException("Invalid approval id");
        Evidence evidence = evidence(intent);
        if (repository.create(approvalId, intent.runId(), intent.nodeRunId(), intent.attempt(), intent.userId(),
                evidence.scriptHmac(), evidence.intentHmac(), evidence.targets(), intent.timeoutSec()) != 1) {
            throw new IllegalStateException("Cannot create dispatch approval binding");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(long approvalId, Intent intent, String taskId) {
        if (approvalId <= 0 || taskId == null || taskId.isBlank() || taskId.length() > 64) {
            throw new IllegalArgumentException("Invalid dispatch binding consumption");
        }
        Evidence evidence = evidence(intent);
        if (repository.consume(approvalId, intent.runId(), intent.nodeRunId(), intent.attempt(), intent.userId(),
                evidence.scriptHmac(), evidence.intentHmac(), evidence.targets(), intent.timeoutSec(), taskId) != 1) {
            throw new IllegalStateException("Dispatch approval binding is invalid or consumed");
        }
    }

    private Evidence evidence(Intent intent) {
        if (intent == null) throw new IllegalArgumentException("Missing dispatch intent");
        DispatchIntentHmac hmac = DispatchIntentHmac.fromEnvironment();
        String scriptHmac = hmac.scriptHmac(intent.script());
        String intentHmac = hmac.intentHmac(intent.runId(), intent.nodeRunId(), intent.attempt(), intent.userId(),
                scriptHmac, intent.targets(), intent.timeoutSec());
        try {
            String targets = mapper.writeValueAsString(intent.targets().stream()
                    .sorted(Comparator.comparing(DispatchIntentHmac.Target::agentId)).toList());
            return new Evidence(scriptHmac, intentHmac, targets);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode dispatch targets", e);
        }
    }
}
