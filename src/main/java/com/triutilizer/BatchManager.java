package com.triutilizer;

import java.util.*;
import java.util.concurrent.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BatchManager {
    private static final int DEFAULT_BATCH_SIZE = 32; // Smaller batches for more frequent processing
    private final Map<String, List<WorkItem>> batchBuffers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastBatchTime = new ConcurrentHashMap<>();
    private final long BATCH_TIMEOUT_MS = 10; // Reduced timeout to process more frequently
    private final Map<String, Integer> batchSizes = new ConcurrentHashMap<>(); // Dynamic batch sizes
    
    public static class Batch {
        public final String type;
        public final List<WorkItem> items;
        public final boolean isModBatch; // True for mod-specific batches like Create
        
        public Batch(String type, List<WorkItem> items, boolean isModBatch) {
            this.type = type;
            this.items = items;
            this.isModBatch = isModBatch;
        }
        
        public List<WorkItem> getItems() {
            return items;
        }
    }
    
    private static final Logger LOGGER = LogManager.getLogger();
    private volatile boolean isShuttingDown = false;
    private final Object batchLock = new Object();
    private int errorCount = 0;
    private long lastErrorTime = 0;
    
    public boolean addToBatch(WorkItem item) {
        if (isShuttingDown) return false;
        if (item == null) return false;
        
        try {
            String batchKey = getBatchKey(item);
            if (batchKey == null) return false;
            
            List<WorkItem> buffer = batchBuffers.computeIfAbsent(batchKey, k -> new ArrayList<>());
            
            synchronized (batchLock) {
                if (buffer.size() < getCurrentBatchSize(batchKey)) {
                    buffer.add(item);
                    lastBatchTime.putIfAbsent(batchKey, System.currentTimeMillis());
                    return true;
                }
                return false;
            }
        } catch (Exception e) {
            handleBatchError("Error adding to batch", e);
            return false;
        }
    }
    
    private String getBatchKey(WorkItem item) {
        try {
            if (item == null) return null;
            
            // Check for mod-specific batching first
            String modId = item.getModId();
            String type = item.getType();
            
            if (modId == null || type == null) return null;
            
            switch (modId.toLowerCase()) {
                case "create":
                    return "create_" + type;
                default:
                    return type;
            }
        } catch (Exception e) {
            handleBatchError("Error getting batch key", e);
            return null;
        }
    }
    
    private int getCurrentBatchSize(String batchKey) {
        try {
            return batchSizes.getOrDefault(batchKey, DEFAULT_BATCH_SIZE);
        } catch (Exception e) {
            handleBatchError("Error getting batch size", e);
            return DEFAULT_BATCH_SIZE;
        }
    }
    
    public List<Batch> getReadyBatches() {
        if (isShuttingDown) return Collections.emptyList();
        
        List<Batch> readyBatches = new ArrayList<>();
        long currentTime = System.currentTimeMillis();
        
        synchronized (batchLock) {
            try {
                for (Map.Entry<String, List<WorkItem>> entry : batchBuffers.entrySet()) {
                    String batchKey = entry.getKey();
                    List<WorkItem> buffer = entry.getValue();
                    
                    if (buffer.isEmpty()) continue;
                    
                    // Get last batch time safely
                    Long lastTime = lastBatchTime.get(batchKey);
                    if (lastTime == null) {
                        lastBatchTime.put(batchKey, currentTime);
                        continue;
                    }
                    
                    int optimalBatchSize = getCurrentBatchSize(batchKey);
                    
                    // Check if batch is ready
                    boolean isTimedOut = (currentTime - lastTime) >= BATCH_TIMEOUT_MS;
                    boolean isFull = buffer.size() >= optimalBatchSize;
                    
                    if (isFull || isTimedOut) {
                        try {
                            boolean isModBatch = batchKey.contains("_");
                            List<WorkItem> batchItems = new ArrayList<>(buffer);
                            
                            // Validate batch items
                            batchItems.removeIf(item -> item == null || 
                                                      item.getType() == null || 
                                                      item.getModId() == null);
                            
                            if (!batchItems.isEmpty()) {
                                readyBatches.add(new Batch(batchKey, batchItems, isModBatch));
                                
                                // Adjust batch size based on processing time
                                long processingTime = currentTime - lastTime;
                                adjustBatchSize(batchKey, processingTime, optimalBatchSize);
                                
                                buffer.clear();
                                lastBatchTime.put(batchKey, currentTime);
                            }
                        } catch (Exception e) {
                            handleBatchError("Error processing batch " + batchKey, e);
                            // Skip this batch but continue processing others
                            buffer.clear();
                            lastBatchTime.put(batchKey, currentTime);
                        }
                    }
                }
            } catch (Exception e) {
                handleBatchError("Critical error in batch processing", e);
                // In case of critical error, clear all batches to prevent cascading failures
                clearAllBatches();
            }
        }
        
        return readyBatches;
    }
    
    private void adjustBatchSize(String batchKey, long processingTime, int currentSize) {
        try {
            if (processingTime < BATCH_TIMEOUT_MS / 2 && currentSize < 128) {
                // Processing is fast, increase batch size gradually
                batchSizes.put(batchKey, currentSize + Math.max(1, currentSize / 4));
            } else if (processingTime > BATCH_TIMEOUT_MS && currentSize > 8) {
                // Processing is slow, decrease batch size quickly
                batchSizes.put(batchKey, Math.max(8, currentSize / 2));
            }
        } catch (Exception e) {
            handleBatchError("Error adjusting batch size", e);
        }
    }
    
    private void handleBatchError(String message, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastErrorTime > 60000) { // Reset error count after 1 minute
            errorCount = 0;
        }
        
        errorCount++;
        lastErrorTime = now;
        
        if (errorCount > 100) { // If too many errors, log at error level
            LOGGER.error(message + ": " + e.getMessage(), e);
        } else {
            LOGGER.warn(message + ": " + e.getMessage());
        }
    }
    
    private void clearAllBatches() {
        try {
            batchBuffers.clear();
            lastBatchTime.clear();
            batchSizes.clear();
            LOGGER.info("Cleared all batches due to critical error");
        } catch (Exception e) {
            LOGGER.error("Failed to clear batches: " + e.getMessage(), e);
        }
    }
    
    public void shutdown() {
        batchBuffers.clear();
        lastBatchTime.clear();
    }
}
