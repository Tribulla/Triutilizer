package com.triutilizer.core.memory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Fast lookup cache for expensive data (e.g., block analysis, chunk summaries).
 * Uses a concurrent map and optional loader function.
 */
public final class FastLookupCache<K, V> {
    private final ConcurrentHashMap<K, V> cache = new ConcurrentHashMap<>();
    private final Function<K, V> loader;

    public FastLookupCache(Function<K, V> loader) {
        this.loader = loader;
    }

    public V get(K key) {
        return cache.computeIfAbsent(key, loader);
    }

    public void clear() { cache.clear(); }
    public int size() { return cache.size(); }
}
