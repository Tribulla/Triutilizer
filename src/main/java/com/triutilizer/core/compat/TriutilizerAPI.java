package com.triutilizer.core.compat;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

public final class TriutilizerAPI {
    private static final List<TriutilizerAddon> ADDONS = new ArrayList<>();
    private static final CompatContext CONTEXT = new CompatContext();
    private static volatile boolean initialized = false;

    private TriutilizerAPI() {}

    public static synchronized void registerAddon(TriutilizerAddon addon) {
        ADDONS.add(addon);
        addon.onRegister(CONTEXT);
    }

    public static List<TriutilizerAddon> addons() { return ADDONS; }
    public static CompatContext context() { return CONTEXT; }

    public static synchronized void initAndAutoDetect() {
        if (initialized) return;
        initialized = true;
        applyAutoDetections();
    }

    private static void applyAutoDetections() {
        // Auto-detection logic can be added here for future mod integrations
    }

    static void onContextChanged() {
        // Reserved for future: could notify subsystems to reconfigure
    }

    public static void onServerAboutToStart(MinecraftServer server) {
        for (var a : ADDONS) a.onServerAboutToStart(server, CONTEXT);
    }

    public static void onServerStopping(MinecraftServer server) {
        for (var a : ADDONS) a.onServerStopping(server, CONTEXT);
    }
}
