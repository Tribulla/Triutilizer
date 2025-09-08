package com.triutilizer.core;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public final class TriutilizerConfig {
    public static ForgeConfigSpec.IntValue threads;
    public static ForgeConfigSpec.BooleanValue respectCompatCaps;

    private TriutilizerConfig() {}

    public static void init() {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        threads = b.comment("Number of worker threads triutilizer uses. Default: availableProcessors-1, minimum 1.")
                .defineInRange("threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 1, 1024);
    respectCompatCaps = b.comment("Whether to respect compatibility thread caps (e.g., other mods that may require single-threaded operation).\n"
        + "If true (default), triutilizer may limit threads to 1 for safety when certain mods are present.\n"
        + "Set to false to ignore these caps and use the configured thread count at your own risk.")
        .define("respectCompatCaps", true);
        ForgeConfigSpec spec = b.build();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, spec);
    }
}
