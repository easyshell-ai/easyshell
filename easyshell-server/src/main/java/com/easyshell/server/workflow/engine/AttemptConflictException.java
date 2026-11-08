package com.easyshell.server.workflow.engine;

/**
 * attempt 抢占冲突（MF-10 / §3.6）：uk_node_run 挡住本次 INSERT，说明同 JVM
 * （或收养后的旧执行者）已有同一 attempt 在跑。本线程放弃并终止 run——
 * 若无 node_run 行，下波 findReady 会再次看到 pending → 再次同样 attempt →
 * 再次 INSERT 失败 → 无限热循环，无退避无日志。
 */
public class AttemptConflictException extends RuntimeException {

    private final String runId;
    private final String nodeKey;
    private final int attempt;

    public AttemptConflictException(String runId, String nodeKey, int attempt) {
        super("attempt 冲突: run=" + runId + " node=" + nodeKey + " attempt=" + attempt);
        this.runId = runId;
        this.nodeKey = nodeKey;
        this.attempt = attempt;
    }

    public String getRunId() {
        return runId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public int getAttempt() {
        return attempt;
    }
}
