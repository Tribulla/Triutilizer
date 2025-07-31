package com.triutilizer;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.triutilizer.WorkItem;

public class WorkerThread extends Thread {
    private static final Logger LOGGER = LogManager.getLogger();
    private final int workerId;
    private final BlockingQueue<WorkItem> taskQueue;
    private final BlockingQueue<List<WorkItem>> batchQueue;
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private volatile boolean isManager = false;
    private volatile boolean running = true;
    private volatile long lastHeartbeat = System.currentTimeMillis();

    public WorkerThread(int id) {
        super("Worker-" + id);
        this.workerId = id;
        this.taskQueue = new LinkedBlockingQueue<>();
        this.batchQueue = new LinkedBlockingQueue<>();
    }
    
    public int getWorkerId() {
        return workerId;
    }
    
    public boolean isManager() {
        return isManager;
    }
    
    public void setManager(boolean value) {
        this.isManager = value;
        LOGGER.info("Worker {} {} manager role", workerId, value ? "promoted to" : "demoted from");
    }
    
    public boolean isStalled() {
        // Only consider a thread stalled if it hasn't updated heartbeat in STALL_TIMEOUT
        // AND it has work to do
        return System.currentTimeMillis() - lastHeartbeat > Constants.STALL_TIMEOUT 
            && (queueSize.get() > 0 || !taskQueue.isEmpty() || !batchQueue.isEmpty());
    }
    
    public boolean isHealthy() {
        // A thread is healthy if:
        // 1. It's not interrupted
        // 2. Either it's not stalled OR it has no work (idle is okay)
        return !isInterrupted() && (!isStalled() || queueSize.get() == 0);
    }
    
    public int getLoad() {
        return queueSize.get();
    }

    public BlockingQueue<WorkItem> getTaskQueue() {
        return taskQueue;
    }

    public BlockingQueue<List<WorkItem>> getBatchQueue() {
        return batchQueue;
    }
    
    public boolean assignTask(WorkItem task) {
        try {
            // Use offer with timeout instead of put
            if (taskQueue.offer(task, 100, TimeUnit.MILLISECONDS)) {
                queueSize.incrementAndGet();
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Failed to assign task to worker " + workerId, e);
            return false;
        }
    }
    
    public boolean assignBatch(List<WorkItem> batch) {
        try {
            // Use offer with timeout instead of put
            if (batchQueue.offer(batch, 100, TimeUnit.MILLISECONDS)) {
                queueSize.addAndGet(batch.size());
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Failed to assign batch to worker " + workerId, e);
            return false;
        }
    }
    
    @Override
    public void run() {
        Thread.currentThread().setName("TriUtilizer-Worker-" + workerId);
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        
        LOGGER.info("Worker thread {} starting", workerId);
        
        int consecutiveErrors = 0;
        long lastErrorTime = 0;
        
        while (running && !isInterrupted() && consecutiveErrors < 10) {
            try {
                boolean didWork = false;
                
                // Try to take work with a timeout
                WorkItem task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task != null) {
                    try {
                        processItem(task);
                        queueSize.decrementAndGet();
                        didWork = true;
                        consecutiveErrors = 0; // Reset error count on success
                    } catch (Exception e) {
                        LOGGER.error("Error processing task in worker {}: {}", workerId, e.getMessage());
                        if (System.currentTimeMillis() - lastErrorTime < 1000) {
                            consecutiveErrors++;
                        } else {
                            consecutiveErrors = 1;
                        }
                        lastErrorTime = System.currentTimeMillis();
                        
                        // Put the task back in queue if retryable
                        if (consecutiveErrors < 3) {
                            taskQueue.offer(task);
                        }
                    }
                }
                
                // Check batch queue if no individual task
                if (!didWork && consecutiveErrors < 5) { // Only process batches if not having too many errors
                    List<WorkItem> batch = batchQueue.poll();
                    if (batch != null && !batch.isEmpty()) {
                        try {
                            processBatchGroup(batch);
                            queueSize.addAndGet(-batch.size());
                            didWork = true;
                            consecutiveErrors = 0; // Reset error count on success
                        } catch (Exception e) {
                            LOGGER.error("Error processing batch in worker {}: {}", workerId, e.getMessage());
                            if (System.currentTimeMillis() - lastErrorTime < 1000) {
                                consecutiveErrors++;
                            } else {
                                consecutiveErrors = 1;
                            }
                            lastErrorTime = System.currentTimeMillis();
                            
                            // Put the batch back in queue if retryable
                            if (consecutiveErrors < 3) {
                                batchQueue.offer(batch);
                            }
                        }
                    }
                }
                
                // Update heartbeat
                lastHeartbeat = System.currentTimeMillis();
                
                // Sleep based on work status and error count
                if (!didWork) {
                    Thread.sleep(Math.min(50 * (consecutiveErrors + 1), 1000)); // Increased sleep on errors
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                LOGGER.error("Critical error in worker thread {}: {}", workerId, t.getMessage(), t);
                consecutiveErrors++;
                try {
                    Thread.sleep(Math.min(200 * consecutiveErrors, 2000)); // Longer sleep on critical errors
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // Log worker shutdown reason
        if (consecutiveErrors >= 10) {
            LOGGER.error("Worker thread {} shutting down due to too many consecutive errors", workerId);
        } else {
            LOGGER.info("Worker thread {} shutting down normally", workerId);
        }
    }

    private void processBatchGroup(List<WorkItem> items) {
        if (items.isEmpty()) return;

        int retries = 0;
        boolean success = false;

        while (!success && retries < 3 && !isInterrupted()) {
            try {
                for (WorkItem item : items) {
                    item.execute();
                }
                success = true;
            } catch (Exception e) {
                retries++;
                LOGGER.error("Error processing batch on worker {} (attempt {}/{}): {}", 
                    workerId, retries, 3, e.getMessage());
                
                if (retries >= 3) {
                    LOGGER.error("Giving up on batch after {} retries on worker {}", 3, workerId);
                } else {
                    try {
                        Thread.sleep(Math.min(100 * retries, 1000)); // Exponential backoff capped at 1 second
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void processItem(WorkItem task) {
        if (task == null) return;

        int retries = 0;
        boolean success = false;

        while (!success && retries < 3 && !isInterrupted()) {
            try {
                task.execute();
                success = true;
            } catch (Exception e) {
                retries++;
                LOGGER.error("Error processing task on worker {} (attempt {}/{}): {}", 
                    workerId, retries, 3, e.getMessage());
                
                if (retries >= 3) {
                    LOGGER.error("Giving up on task after {} retries on worker {}", 3, workerId);
                    break;
                }
                try {
                    Thread.sleep(Math.min(100 * retries, 1000)); // Exponential backoff capped at 1 second
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public boolean isOverwhelmed() {
        return queueSize.get() > Constants.MAX_QUEUE_SIZE * 0.9;
    }

    public boolean isUnderutilized() {
        return queueSize.get() < Constants.MAX_QUEUE_SIZE * 0.1;
    }
}
