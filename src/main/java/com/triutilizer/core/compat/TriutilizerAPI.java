package com.triutilizer.core.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

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
        // Valkyrien Skies is present. We won't globally cap to a single thread anymore.
        // Triutilizer's tasks must still avoid accessing game state off-thread; keep heavy world interactions on main thread.
        if (isLoaded("valkyrienskies") || isLoaded("valkyrien_skies") || isLoaded("valkyrien") || isLoaded("vs2")) {
            LoggerFactory.getLogger("triutilizer").info("Compat: Valkyrien Skies detected; keeping multicore enabled and avoiding off-thread world access");
        }
    }

    private static boolean isLoaded(String modid) {
        try { return ModList.get().isLoaded(modid); }
        catch (Throwable t) { return false; }
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
