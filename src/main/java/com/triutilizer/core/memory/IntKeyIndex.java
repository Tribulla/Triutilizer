package com.triutilizer.core.memory;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

/**
 * A compact, cache-friendly index from int key -> row ids.
 * - Uses primitive collections to reduce overhead and improve locality.
 * - Separate structure-of-arrays layout for IDs.
 */
public final class IntKeyIndex {
    private final Int2IntOpenHashMap head; // key -> head row (or -1)
    private final IntList next;            // row -> next row id (or -1)
    private final IntList rowKey;          // row -> key

    public IntKeyIndex(int expected) {
        this.head = new Int2IntOpenHashMap(expected);
        this.head.defaultReturnValue(-1);
        this.next = new IntArrayList(expected);
        this.rowKey = new IntArrayList(expected);
    }

    public int size() { return rowKey.size(); }

    public int add(int key) {
        int row = rowKey.size();
        rowKey.add(key);
        int h = head.getOrDefault(key, -1);
        next.add(h);
        head.put(key, row);
        return row;
    }

    public void forEachByKey(int key, RowConsumer consumer) {
        for (int r = head.getOrDefault(key, -1); r != -1; r = next.getInt(r)) {
            consumer.accept(r, rowKey.getInt(r));
        }
    }

    public interface RowConsumer { void accept(int rowId, int key); }
}
