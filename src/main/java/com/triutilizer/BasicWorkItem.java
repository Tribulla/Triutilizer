package com.triutilizer;

import net.minecraft.server.level.ServerLevel;

public class BasicWorkItem implements WorkItem {
    private final String type;
    private final Runnable task;
    private final long timestamp;
    private final ServerLevel level;
    private final String modId;

    public BasicWorkItem(String type, Runnable task, ServerLevel level) {
        this(type, task, level, "minecraft");
    }

    public BasicWorkItem(String type, Runnable task, ServerLevel level, String modId) {
        this.type = type;
        this.task = task;
        this.timestamp = System.currentTimeMillis();
        this.level = level;
        this.modId = modId;
    }

    @Override
    public void execute() throws Exception {
        task.run();
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getModId() {
        return modId;
    }

    @Override
    public ServerLevel getLevel() {
        return level;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }
}
