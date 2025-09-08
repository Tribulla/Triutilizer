package com.triutilizer.core.memory;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

/** A small packed int table (structure-of-arrays) for CPU-friendly scanning. */
public final class PackedIntTable {
    private final IntList colA;
    private final IntList colB;

    public PackedIntTable(int expected) {
        this.colA = new IntArrayList(expected);
        this.colB = new IntArrayList(expected);
    }

    public int add(int a, int b) {
        int row = colA.size();
        colA.add(a);
        colB.add(b);
        return row;
    }

    public int size() { return colA.size(); }

    public int getA(int row) { return colA.getInt(row); }
    public int getB(int row) { return colB.getInt(row); }
}
