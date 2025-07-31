package com.triutilizer;

public class Constants {
    // Queue and batch sizes
    public static final int MAX_QUEUE_SIZE = 250000;
    public static final int BATCH_SIZE = 64;
    
    // Timeouts and limits
    public static final long TASK_TIMEOUT_MS = 2000;
    public static final int MAX_TASKS_PER_TICK = Integer.MAX_VALUE;
    public static final long HEALTH_CHECK_INTERVAL = 5000; // 5 seconds
    public static final long STALL_TIMEOUT = 5000; // 5 seconds
    public static final long TICK_TIME_BUDGET_MS = 50;
    public static final int MAX_RETRIES = 10;
    
    // Queue size limits per type
    public static final int MAX_ENTITY_QUEUE = 10000;
    public static final int MAX_BLOCK_ENTITY_QUEUE = 6000;
    public static final int MAX_CHUNK_QUEUE = 4000;
}
