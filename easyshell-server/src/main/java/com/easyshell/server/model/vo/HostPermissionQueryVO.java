package com.easyshell.server.model.vo;

public record HostPermissionQueryVO(String agentId, long generation, boolean view, boolean execute, boolean terminal) { }
