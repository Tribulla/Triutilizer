package com.triutilizer;

import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.PushReaction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
/**
 * WorkItem that handles block destruction and updates neighboring blocks
 */
public class BlockDestroyedWorkItem implements WorkItem {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Set<BlockPos> processedPositions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int MAX_RETRIES = 3;
    
    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState oldState;
    private final long timestamp;
    private int retryCount = 0;

    public BlockDestroyedWorkItem(ServerLevel level, BlockPos pos, BlockState oldState) {
        this.level = level;
        this.pos = pos;
        this.oldState = oldState;
        this.timestamp = System.currentTimeMillis();
    }
    
    @Override
    public void execute() throws Exception {
        if (level == null) return;
        
        // Prevent recursive updates
        if (!processedPositions.add(pos)) {
            return;
        }
        
        try {
            // Mark this position for block updates
            safeBlockUpdate(pos);
            
            // Process neighbors in a safe manner
            for (Direction dir : Direction.values()) {
                try {
                    BlockPos neighborPos = pos.relative(dir);
                    processNeighbor(neighborPos, dir);
                } catch (Exception e) {
                    logError("Error processing neighbor " + dir, e);
                }
            }
            
            // Check for support issues
            checkBlockSupport();
            
        } catch (Exception e) {
            handleExecutionError(e);
        } finally {
            // Clean up after a delay to prevent too-quick re-processing
            scheduleCleanup(pos);
        }
    }
    
    private void safeBlockUpdate(BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            level.blockUpdated(pos, state.getBlock());
        } catch (Exception e) {
            logError("Error in block update at " + pos, e);
        }
    }
    
    private void processNeighbor(BlockPos neighborPos, Direction dir) {
        if (!level.isLoaded(neighborPos)) return;
        
        try {
            BlockState neighborState = level.getBlockState(neighborPos);
            
            // Basic neighbor update
            level.neighborChanged(neighborPos, neighborState.getBlock(), pos);
            
            // Force block update with lower flags to reduce cascading
            level.sendBlockUpdated(neighborPos, neighborState, neighborState, 2);
            
            // Process secondary neighbors with rate limiting
            processSecondaryNeighbors(neighborPos, dir, neighborState);
            
            // Update redstone selectively
            if (shouldUpdateRedstone(neighborState)) {
                level.updateNeighborsAt(neighborPos, neighborState.getBlock());
            }
        } catch (Exception e) {
            logError("Error processing neighbor at " + neighborPos, e);
        }
    }
    
    private void processSecondaryNeighbors(BlockPos neighborPos, Direction primaryDir, BlockState neighborState) {
        for (Direction dir2 : Direction.values()) {
            if (dir2 == primaryDir.getOpposite()) continue;
            
            BlockPos pos2 = neighborPos.relative(dir2);
            if (!level.isLoaded(pos2)) continue;
            
            try {
                BlockState state2 = level.getBlockState(pos2);
                if (shouldUpdateSecondary(state2)) {
                    level.neighborChanged(pos2, state2.getBlock(), neighborPos);
                }
            } catch (Exception e) {
                logError("Error in secondary update at " + pos2, e);
            }
        }
    }
    
    private boolean shouldUpdateRedstone(BlockState state) {
        return state.isRedstoneConductor(level, pos) || 
               state.hasAnalogOutputSignal() ||
               state.isSignalSource();
    }
    
    private boolean shouldUpdateSecondary(BlockState state) {
        return !state.isAir() && (
            state.hasAnalogOutputSignal() ||
            state.isSignalSource() ||
            state.getPistonPushReaction() != PushReaction.IGNORE
        );
    }
    
    private void checkBlockSupport() {
        for (Direction dir : Direction.values()) {
            BlockPos checkPos = pos.relative(dir);
            if (!level.isLoaded(checkPos)) continue;
            
            try {
                BlockState checkState = level.getBlockState(checkPos);
                if (!checkState.isAir() && !checkState.canSurvive(level, checkPos)) {
                    // Schedule with increasing delays based on retry count
                    int delay = Math.min(2 + retryCount, 10);
                    level.scheduleTick(checkPos, checkState.getBlock(), delay);
                }
            } catch (Exception e) {
                logError("Error checking support at " + checkPos, e);
            }
        }
    }
    
    private void handleExecutionError(Exception e) throws Exception {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
            LOGGER.error("Failed to process block update after {} retries at {}: {}", 
                        MAX_RETRIES, pos, e.getMessage());
            throw e;
        } else {
            LOGGER.warn("Retrying block update at {} (attempt {}/{}): {}", 
                       pos, retryCount, MAX_RETRIES, e.getMessage());
        }
    }
    
    private void scheduleCleanup(BlockPos pos) {
        if (level.getServer() != null) {
            level.getServer().submit(() -> {
                processedPositions.remove(pos);
                return null;
            });
        }
    }
    
    private void logError(String message, Exception e) {
        if (retryCount == 0) {
            LOGGER.warn(message + ": " + e.getMessage());
        } else {
            LOGGER.error(message + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String getType() {
        return "block_destroyed";
    }

    @Override
    public String getModId() {
        return oldState.getBlock().toString();
    }

    @Override
    public ServerLevel getLevel() {
        return level;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean canBatch(WorkItem other) {
        if (!(other instanceof BlockDestroyedWorkItem)) return false;
        BlockDestroyedWorkItem otherItem = (BlockDestroyedWorkItem) other;
        return pos.distManhattan(otherItem.pos) <= 16;
    }
}
