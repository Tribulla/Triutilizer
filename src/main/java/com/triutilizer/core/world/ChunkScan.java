package com.triutilizer.core.world;

import com.triutilizer.core.concurrency.TaskManager;
import com.triutilizer.core.memory.Palette;
import com.triutilizer.core.memory.FastLookupCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Utilities for scanning chunks without touching game state off-thread. */
public final class ChunkScan {
    private ChunkScan() {}

    public interface BlockPredicate { boolean test(BlockState state, BlockPos pos); }

    public static CompletableFuture<List<BlockPos>> findBlocks(ServerLevel level, ChunkPos center, int radius, BlockPredicate predicate) {
        Palette<BlockState> palette = new Palette<>();
        FastLookupCache<BlockPos, BlockState> cache = new FastLookupCache<>(level::getBlockState);
        // Gather immutable snapshot of positions to check on main thread
        List<BlockPos> positions = new ArrayList<>();
        int cx = center.x;
        int cz = center.z;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x0 = (cx + dx) << 4;
                int z0 = (cz + dz) << 4;
                for (int x = x0; x < x0 + 16; x++) {
                    for (int z = z0; z < z0 + 16; z++) {
                        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                            positions.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
        }
        // Off-thread CPU filtering: call predicate only with state value captured on main thread first
        // Capture states on main thread to avoid chunk access off-thread
        List<BlockState> states = new ArrayList<>(positions.size());
        for (BlockPos p : positions) {
            BlockState raw = cache.get(p);
            BlockState deduped = palette.dedup(raw);
            states.add(deduped);
        }
        return TaskManager.mapParallel(states, st -> st)
                .thenApply(parStates -> {
                    List<BlockPos> out = new ArrayList<>();
                    for (int i = 0; i < parStates.size(); i++) {
                        BlockState s = parStates.get(i);
                        BlockPos p = positions.get(i);
                        if (predicate.test(s, p)) out.add(p);
                    }
                    return out;
                });
    }
}
