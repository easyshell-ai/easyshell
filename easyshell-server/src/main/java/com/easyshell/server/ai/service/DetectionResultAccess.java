package com.easyshell.server.ai.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.repository.*;
import com.easyshell.server.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Detection output is usable only with exact script, owner and generation evidence. */
@Service
@RequiredArgsConstructor
public class DetectionResultAccess {
    private final TaskRepository tasks;
    private final TaskTargetSnapshotRepository snapshots;
    private final JobRepository jobs;
    private final WorkflowNodeRunRepository workflowNodes;
    private final TaskAccessQueryRepository taskAccess;
    private final HostIdentityService identities;
    private final HostAclService acl;
    private final UserRepository users;

    @Transactional(propagation=Propagation.MANDATORY)
    public void requireParse(Long actor,String taskId,String agentId) {
        var user=users.findById(actor).orElseThrow(DetectionResultAccess::denied);
        if (!Integer.valueOf(1).equals(user.getStatus())) throw denied();
        var task=tasks.findById(taskId).orElseThrow(DetectionResultAccess::denied);
        if (!Objects.equals(actor,task.getCreatedBy())
                || !("user".equals(task.getSource()) || "ai_detect".equals(task.getSource()))
                || !HostIntelligenceService.isBuiltinDetectionScript(task.getScriptContent())
                || !Integer.valueOf(1).equals(task.getTotalCount())
                || !workflowNodes.findByTaskId(taskId).isEmpty()
                || !taskAccess.provenanceRunIds(taskId).isEmpty()) throw denied();
        var snapshot=snapshots.findComplete(taskId).orElseThrow(DetectionResultAccess::denied);
        if (snapshot.targets().size()!=1) throw denied();
        var target=snapshot.targets().get(0);
        var identity=identities.lock(agentId);
        if (!agentId.equals(target.agentId()) || !identity.active() || identity.generation()!=target.generation()
                || !acl.allowedForGeneration(actor,agentId,target.generation(),HostAclService.Permission.EXECUTE)
                || !acl.allowedForGeneration(actor,agentId,target.generation(),HostAclService.Permission.VIEW)) throw denied();
        var results=jobs.findByTaskId(taskId);
        if (results.size()!=1 || !agentId.equals(results.get(0).getAgentId())) throw denied();
    }

    private static BusinessException denied() {return new BusinessException(403,"Detection task provenance or host permission invalid");}
}
