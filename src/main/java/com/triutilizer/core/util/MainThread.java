package com.triutilizer.core.util;

import net.minecraft.server.MinecraftServer;

public final class MainThread {
    private static volatile MinecraftServer SERVER;

    private MainThread() {}

    public static void bind(MinecraftServer server) { SERVER = server; }

    public static void clear() { SERVER = null; }

    public static void execute(Runnable r) {
        MinecraftServer s = SERVER;
        if (s == null) {
            r.run();
        } else {
            s.execute(r);
        }
    }
}
