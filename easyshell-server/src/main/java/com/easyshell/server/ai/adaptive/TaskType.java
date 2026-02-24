package com.easyshell.server.ai.adaptive;

/**
 * Task type classification for adaptive prompt and tool selection.
 */
public enum TaskType {
    /** Query / information gathering — lightweight prompt + read-only tools */
    QUERY,
    /** Command execution — safety-enhanced prompt + all tools */
    EXECUTE,
    /** Troubleshooting — diagnostic prompt + full toolset */
    TROUBLESHOOT,
    /** Deployment / configuration — process-oriented prompt + SOP injection */
    DEPLOY,
    /** Monitoring / analysis — analysis prompt + monitoring tools */
    MONITOR,
    /** General conversation — base prompt + basic tools */
    GENERAL
}
