package com.easyshell.server.workflow.gate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDispatchIntentTest {
    @Test
    void snapshotsTargetsAndDoesNotExposeMutableList() {
        var target = new DispatchIntentHmac.Target("agent", 1);
        var targets = new ArrayList<>(List.of(target));
        var intent = new WorkflowDispatchApprovalBindingService.Intent("run", "node", 1, 2,
                "script", targets, 30);

        targets.clear();
        assertThat(intent.targets()).containsExactly(target);
        assertThatThrownBy(() -> intent.targets().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingTargetsWithoutExposingScript() {
        assertThatThrownBy(() -> new WorkflowDispatchApprovalBindingService.Intent("run", "node", 1, 2,
                "secret command", null, 30))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret command");
        var targets = new ArrayList<DispatchIntentHmac.Target>();
        targets.add(null);
        assertThatThrownBy(() -> new WorkflowDispatchApprovalBindingService.Intent("run", "node", 1, 2,
                "secret command", targets, 30))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret command");
    }
}
