package com.triutilizer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraftforge.common.extensions.IForgeBlockEntity;

public class BlockUpdateWorkItem implements WorkItem {
    private final ServerLevel level;
    private final BlockEntity blockEntity;
    private final BlockPos pos;
    private final long timestamp;
    private final boolean isBlockEntity;
    
    public BlockUpdateWorkItem(ServerLevel level, BlockEntity blockEntity) {
        this.level = level;
        this.blockEntity = blockEntity;
        this.pos = blockEntity.getBlockPos();
        this.timestamp = System.currentTimeMillis();
        this.isBlockEntity = true;
    }
    
    public BlockUpdateWorkItem(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.blockEntity = null;
        this.pos = pos;
        this.timestamp = System.currentTimeMillis();
        this.isBlockEntity = false;
    }
    
    @Override
    public void execute() throws Exception {
        if (level == null) return;
        
        if (isBlockEntity) {
            // Handle block entity updates
            if (!blockEntity.isRemoved()) {
                BlockState state = blockEntity.getBlockState();
                
                // Handle ITickable block entities (older style)
                if (blockEntity instanceof IForgeBlockEntity) {
                    ((IForgeBlockEntity) blockEntity).onLoad();
                }
                
                // Handle modern ticking block entities
                if (blockEntity instanceof BlockEntityTicker) {
                    @SuppressWarnings("unchecked")
                    BlockEntityTicker<BlockEntity> ticker = (BlockEntityTicker<BlockEntity>) blockEntity;
                    ticker.tick(level, pos, state, blockEntity);
                }
            }
        } else {
            // Handle regular block updates
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                // Update the block itself
                state.tick(level, pos, level.random);
                
                // Update redstone and comparators
                level.updateNeighborsAt(pos, state.getBlock());
                
                // Update neighbors
                for (Direction dir : Direction.values()) {
                    BlockPos neighborPos = pos.relative(dir);
                    level.neighborChanged(neighborPos, state.getBlock(), pos);
                    level.updateNeighborsAtExceptFromFacing(neighborPos, state.getBlock(), dir.getOpposite());
                }
            }
        }
    }
    
    @Override
    public String getType() {
        return "block_update";
    }
    
    @Override
    public String getModId() {
        if (isBlockEntity) {
            return blockEntity.getType().toString();
        }
        return level.getBlockState(pos).getBlock().toString();
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
        if (!(other instanceof BlockUpdateWorkItem)) return false;
        BlockUpdateWorkItem otherItem = (BlockUpdateWorkItem) other;
        
        // Get positions for comparison
        BlockPos thisPos = isBlockEntity ? blockEntity.getBlockPos() : pos;
        BlockPos otherPos = otherItem.isBlockEntity ? otherItem.blockEntity.getBlockPos() : otherItem.pos;
        
        // Can batch if same mod and within 16 blocks
        return getModId().equals(other.getModId()) &&
               thisPos.distManhattan(otherPos) <= 16;
    }
}
