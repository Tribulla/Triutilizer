package com.triutilizer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public class EntityUpdateWorkItem implements WorkItem {
    private final ServerLevel level;
    private final Entity entity;
    private final long timestamp;
    
    public EntityUpdateWorkItem(ServerLevel level, Entity entity) {
        this.level = level;
        this.entity = entity;
        this.timestamp = System.currentTimeMillis();
    }
    
    @Override
    public void execute() throws Exception {
        if (!entity.isRemoved()) {
            entity.tick();
        }
    }
    
    @Override
    public String getType() {
        return "entity_update";
    }
    
    @Override
    public String getModId() {
        return entity.getType().toString();
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
        if (!(other instanceof EntityUpdateWorkItem)) return false;
        EntityUpdateWorkItem otherItem = (EntityUpdateWorkItem) other;
        // Can batch if same type and within 32 blocks
        return getModId().equals(other.getModId()) &&
               entity.distanceTo(otherItem.entity) <= 32.0;
    }
}
