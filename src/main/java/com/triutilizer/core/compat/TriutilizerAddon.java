package com.triutilizer.core.compat;

import net.minecraft.server.MinecraftServer;

/**
 * Optional integration hook for other mods. Implementations can adjust the CompatContext
 * (e.g., request single-thread mode) and observe server lifecycle.
 */
public interface TriutilizerAddon {
    String id();

    default void onRegister(CompatContext ctx) {}
    default void onServerAboutToStart(MinecraftServer server, CompatContext ctx) {}
    default void onServerStopping(MinecraftServer server, CompatContext ctx) {}
}
