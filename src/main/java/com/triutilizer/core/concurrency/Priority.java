package com.triutilizer.core.concurrency;

public enum Priority {
    LOW(0),
    NORMAL(1),
    HIGH(2),
    CRITICAL(3);

    public final int level;
    Priority(int l) { this.level = l; }
}
