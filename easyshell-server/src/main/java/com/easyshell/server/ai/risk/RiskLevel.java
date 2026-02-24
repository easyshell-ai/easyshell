package com.easyshell.server.ai.risk;

public enum RiskLevel {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    BANNED(3);

    private final int level;

    RiskLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
