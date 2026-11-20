package com.easyshell.server.workflow.approval;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowApprovalBindingArchTest {
    @Test
    void onlySuspensionServicesUseBindingAndOnlyBindingCreatesAndSuspends() throws IOException {
        Set<String> bindingReferences = new TreeSet<>();
        Set<String> insertionReferences = new TreeSet<>();
        Set<String> updateReferences = new TreeSet<>();
        Set<String> directApprovalWrites = new TreeSet<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                String name = file.getFileName().toString();
                if (source.contains("WorkflowApprovalAttemptBinding")) bindingReferences.add(name);
                if (source.contains("createInTransaction(")) insertionReferences.add(name);
                if (source.contains("approvalRepository.save(")) directApprovalWrites.add(name);
                if (source.contains("suspendApprovalAttempt(") || source.contains("suspendDispatchAttempt("))
                    updateReferences.add(name);
            }
        }
        assertThat(bindingReferences).containsExactlyInAnyOrder("WorkflowApprovalAttemptBinding.java",
                "WorkflowApprovalSuspensionService.java", "WorkflowDispatchApprovalSuspensionService.java");
        assertThat(insertionReferences).containsExactlyInAnyOrder("WorkflowApprovalStore.java", "WorkflowApprovalAttemptBinding.java");
        assertThat(updateReferences).containsExactlyInAnyOrder("WorkflowNodeRunRepository.java", "WorkflowApprovalAttemptBinding.java");
        assertThat(directApprovalWrites).containsExactly("WorkflowApprovalStore.java");
    }
}
