package com.triutilizer;

import net.minecraftforge.common.ForgeConfigSpec;

public class OptimizerConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue THREAD_POOL_SIZE;
    public static final ForgeConfigSpec.IntValue MAX_CONCURRENT_CHUNKS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_PARALLEL_PROCESSING;
    public static final ForgeConfigSpec.IntValue TARGET_CPU_USAGE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DYNAMIC_TICK_RATE;
    public static final ForgeConfigSpec.IntValue ENTITY_PROCESSING_THRESHOLD;

    static {
        BUILDER.push("Tri Utilizer Settings");

        THREAD_POOL_SIZE = BUILDER
            .comment("Number of threads to use for parallel processing (0 = automatic based on CPU cores)")
            .defineInRange("threadPoolSize", 0, 0, 32);

        MAX_CONCURRENT_CHUNKS = BUILDER
            .comment("Maximum number of chunks to process concurrently")
            .defineInRange("maxConcurrentChunks", 4, 1, 16);

        ENABLE_PARALLEL_PROCESSING = BUILDER
            .comment("Enable parallel processing for compatible tasks")
            .define("enableParallelProcessing", true);

        TARGET_CPU_USAGE = BUILDER
            .comment("Target CPU usage percentage (will try to utilize up to this amount)")
            .defineInRange("targetCpuUsage", 85, 30, 95);

        ENABLE_DYNAMIC_TICK_RATE = BUILDER
            .comment("Dynamically adjust tick rate based on server load")
            .define("enableDynamicTickRate", true);

        ENTITY_PROCESSING_THRESHOLD = BUILDER
            .comment("Number of entities in a chunk before parallel processing is triggered")
            .defineInRange("entityProcessingThreshold", 20, 10, 100);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
