package com.triutilizer;

import net.minecraft.server.level.ServerLevel;

public interface WorkItem {
    /**
     * Execute the work item's task.
     * @throws Exception if there is an error during execution
     */
    void execute() throws Exception;
    
    /**
     * Get the type of work item
     */
    String getType();
    
    /**
     * Get the mod ID associated with this work item
     */
    String getModId();
    
    /**
     * Get the level this work item operates on
     */
    ServerLevel getLevel();
    
    /**
     * Get the timestamp when this work item was created
     */
    long getTimestamp();

    /**
     * Check if this work item can be batched with another work item
     * @param other the other work item to check
     * @return true if the items can be batched together
     */
    default boolean canBatch(WorkItem other) {
        return this.getModId().equals(other.getModId()) && this.getType().equals(other.getType());
    }
}
