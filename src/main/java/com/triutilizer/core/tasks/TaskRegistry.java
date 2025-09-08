package com.triutilizer.core.tasks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class TaskRegistry {
    private static final Map<String, Supplier<Runnable>> REGISTRY = new ConcurrentHashMap<>();

    private TaskRegistry() {}

    public static void register(String id, Supplier<Runnable> supplier) {
        REGISTRY.put(id.toLowerCase(), supplier);
    }

    public static Runnable create(String id) {
        Supplier<Runnable> s = REGISTRY.get(id.toLowerCase());
        return s == null ? null : s.get();
    }
}
