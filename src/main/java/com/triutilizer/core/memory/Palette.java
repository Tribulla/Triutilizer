package com.triutilizer.core.memory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FerriteCore-like palette for deduplicating objects (e.g., BlockStates, Biomes).
 * Stores unique instances and returns canonical references for fast equality and memory savings.
 */
public final class Palette<T> {
    private final Map<T, T> map = new ConcurrentHashMap<>();

    public T dedup(T value) {
        return map.computeIfAbsent(value, k -> k);
    }

    public int size() { return map.size(); }

    public Collection<T> values() { return map.values(); }
}
