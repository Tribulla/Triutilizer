package com.triutilizer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.triutilizer.Constants.*;
import com.triutilizer.WorkerThread;
import com.triutilizer.WorkItem;
import com.triutilizer.BatchManager;

public class WorkerManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final boolean debugMode = Boolean.getBoolean("triutilizer.debug");
    private final List<WorkerThread> workers;
    private final AtomicReference<WorkerThread> primaryManager;
    private final AtomicInteger managerCount = new AtomicInteger(1);
    private final BatchManager batchManager;
    private final HealthMonitor healthMonitor;
    
    public WorkerManager(int workerCount) {
        this.workers = new CopyOnWriteArrayList<>();
        this.batchManager = new BatchManager();
        this.healthMonitor = new HealthMonitor();
        
        // Ensure reasonable worker count
        workerCount = Math.max(2, Math.min(workerCount, 16)); // At least 2, at most 16 workers
        
        // Initialize workers
        for (int i = 0; i < workerCount; i++) {
            WorkerThread worker = new WorkerThread(i);
            workers.add(worker);
            // Start each worker thread
            worker.start();
        }
        
        // Set initial manager
        primaryManager = new AtomicReference<>(workers.get(0));
        workers.get(0).setManager(true);
        
        LOGGER.info("Started {} worker threads", workerCount);
        
        // Start health monitoring with a brief delay
        healthMonitor.setDaemon(true); // Make it a daemon thread
        healthMonitor.start();
    }
    
    private class HealthMonitor extends Thread {
        private volatile boolean running = true;
        private final long HEALTH_CHECK_INTERVAL = 2000; // Health check every 2 seconds
        private final long LOAD_CHECK_INTERVAL = 10000; // Load balance every 10 seconds
        private long lastLoadCheck = 0;
        private int failedChecks = 0;
        private long lastRecoveryAttempt = 0;
        
        @Override
        public void run() {
            setName("TriUtilizer-HealthMonitor");
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY); // Use lowest priority
            
            // Initial delay to let workers initialize
            try {
                Thread.sleep(5000); // Longer initial delay
            } catch (InterruptedException e) {
                return;
            }
            
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    long startTime = System.currentTimeMillis();
                    
                    // Check if recovery is needed
                    if (failedChecks >= 3 && System.currentTimeMillis() - lastRecoveryAttempt > 30000) {
                        attemptRecovery();
                        lastRecoveryAttempt = System.currentTimeMillis();
                        failedChecks = 0;
                        continue; // Skip normal checks during recovery
                    }
                    
                    // Normal health checks
                    try {
                        checkWorkerHealth();
                        if (startTime - lastLoadCheck >= LOAD_CHECK_INTERVAL) {
                            checkManagerHealth();
                            WorkerManager.this.balanceLoad();
                            checkSystemLoad();
                            lastLoadCheck = startTime;
                        }
                        failedChecks = 0; // Reset on successful checks
                    } catch (Exception e) {
                        LOGGER.error("Error during health checks: {}", e.getMessage());
                        failedChecks++;
                    }
                    
                    // Adaptive sleep based on system health
                    long sleepTime = HEALTH_CHECK_INTERVAL * (1 + failedChecks);
                    Thread.sleep(Math.min(sleepTime, 10000)); // Cap at 10 seconds
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    LOGGER.error("Critical error in health monitor: {}", t.getMessage(), t);
                    failedChecks++;
                    try {
                        Thread.sleep(5000); // Longer sleep after critical error
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        private void attemptRecovery() {
            LOGGER.info("Attempting worker pool recovery");
            
            synchronized (workers) {
                // Stop all workers
                for (WorkerThread worker : workers) {
                    try {
                        worker.interrupt();
                        worker.join(1000);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to stop worker {}: {}", worker.getWorkerId(), e.getMessage());
                    }
                }
                
                // Clear worker list
                workers.clear();
                
                // Create new workers
                int desiredCount = Runtime.getRuntime().availableProcessors() - 1;
                desiredCount = Math.max(2, Math.min(desiredCount, 8)); // Between 2 and 8 workers
                
                for (int i = 0; i < desiredCount; i++) {
                    try {
                        WorkerThread worker = new WorkerThread(i);
                        workers.add(worker);
                        worker.start();
                    } catch (Exception e) {
                        LOGGER.error("Failed to create worker {}: {}", i, e.getMessage());
                    }
                }
                
                // Set new primary manager
                if (!workers.isEmpty()) {
                    WorkerThread newManager = workers.get(0);
                    newManager.setManager(true);
                    primaryManager.set(newManager);
                }
            }
            
            LOGGER.info("Worker pool recovery completed with {} workers", workers.size());
        }
        
        private void checkManagerHealth() {
            WorkerThread manager = primaryManager.get();
            if (!manager.isHealthy()) {
                LOGGER.warn("Manager {} failed, initiating failover", manager.getId());
                initiateFailover();
            }
        }
        
        private void checkWorkerHealth() {
            for (WorkerThread worker : workers) {
                if (!worker.isManager() && !worker.isHealthy()) {
                    LOGGER.warn("Worker {} failed, redistributing work", worker.getId());
                    redistributeWork(worker);
                }
            }
        }
        
        private void checkSystemLoad() {
            WorkerThread manager = primaryManager.get();
            if (manager.isOverwhelmed()) {
                promoteWorker();
            } else if (managerCount.get() > 1 && !manager.isUnderutilized()) {
                demoteExtraManagers();
            }
        }
    }
    
    private void initiateFailover() {
        // Find the healthiest worker to promote
        WorkerThread oldManager = primaryManager.get();
        WorkerThread newManager = workers.stream()
            .filter(w -> !w.isManager() && w.isHealthy())
            .min(Comparator.comparingInt(WorkerThread::getLoad))
            .orElseThrow(() -> new RuntimeException("No healthy workers available for failover"));
            
        // Promote new manager
        newManager.setManager(true);
        primaryManager.set(newManager);
        
        // Cleanup old manager
        oldManager.setManager(false);
        redistributeWork(oldManager);
    }
    
    public void start() {
        for (WorkerThread worker : workers) {
            worker.start();
        }
        LOGGER.info("Started {} worker threads", workers.size());
    }

    public void stop() {
        healthMonitor.running = false;
        healthMonitor.interrupt();
        
        synchronized (workers) {
            for (WorkerThread worker : workers) {
                try {
                    worker.interrupt();
                    worker.join(2000); // Give more time for cleanup
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.warn("Interrupted while stopping worker {}", worker.getWorkerId());
                } catch (Exception e) {
                    LOGGER.error("Error stopping worker {}: {}", worker.getWorkerId(), e.getMessage());
                }
            }
            workers.clear();
        }
        
        try {
            healthMonitor.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Worker manager shutdown complete");
    }

    public List<WorkerThread> getWorkers() {
        return new ArrayList<>(workers); // Return a copy for thread safety
    }

    public int getManagerCount() {
        return managerCount.get();
    }
    
    public boolean isHealthy() {
        if (workers.isEmpty()) return false;
        
        // Check if we have at least one healthy manager
        boolean hasHealthyManager = workers.stream()
            .filter(WorkerThread::isManager)
            .anyMatch(WorkerThread::isHealthy);
            
        // Check if we have enough healthy workers
        long healthyWorkers = workers.stream()
            .filter(w -> !w.isManager() && w.isHealthy())
            .count();
            
        return hasHealthyManager && healthyWorkers >= 1;
    }

    private void balanceLoad() {
        // Get average load across workers
        double avgLoad = workers.stream()
            .mapToInt(WorkerThread::getLoad)
            .average()
            .orElse(0.0);
            
        if (avgLoad == 0.0) return; // No work to balance
            
        // Find overloaded and underloaded workers
        List<WorkerThread> overloaded = workers.stream()
            .filter(w -> w.isHealthy() && w.getLoad() > avgLoad * 1.5) // 50% above average
            .collect(Collectors.toList());
            
        List<WorkerThread> underloaded = workers.stream()
            .filter(w -> w.isHealthy() && w.getLoad() < avgLoad * 0.5) // 50% below average
            .collect(Collectors.toList());
            
        // Balance load by moving tasks
        for (WorkerThread src : overloaded) {
            for (WorkerThread dst : underloaded) {
                if (src.getLoad() <= avgLoad * 1.2) break; // Stop if source is now balanced
                
                // Move some tasks from src to dst
                while (src.getLoad() > avgLoad * 1.2 && dst.getLoad() < avgLoad) {
                    WorkItem task = src.getTaskQueue().poll();
                    if (task != null) {
                        dst.assignTask(task);
                        if (debugMode) {
                            LOGGER.debug("Moved task from worker {} to worker {}", 
                                src.getWorkerId(), dst.getWorkerId());
                        }
                    } else {
                        break;
                    }
                }
                
                // Move some batches if still imbalanced
                while (src.getLoad() > avgLoad * 1.2 && dst.getLoad() < avgLoad) {
                    List<WorkItem> batch = src.getBatchQueue().poll();
                    if (batch != null) {
                        dst.assignBatch(batch);
                        if (debugMode) {
                            LOGGER.debug("Moved batch of {} items from worker {} to worker {}", 
                                batch.size(), src.getWorkerId(), dst.getWorkerId());
                        }
                    } else {
                        break;
                    }
                }
            }
        }
    }

    private void promoteWorker() {
        Optional<WorkerThread> candidate = workers.stream()
            .filter(w -> !w.isManager() && w.isHealthy() && w.getLoad() < 100)
            .min(Comparator.comparingInt(WorkerThread::getLoad));
            
        candidate.ifPresent(worker -> {
            worker.setManager(true);
            managerCount.incrementAndGet();
            LOGGER.info("Promoted worker {} to assistant manager", worker.getWorkerId());
        });
    }
    
    private void demoteExtraManagers() {
        List<WorkerThread> managers = workers.stream()
            .filter(w -> w.isManager() && w != primaryManager.get())
            .sorted(Comparator.comparingInt(WorkerThread::getLoad))
            .collect(Collectors.toList());
            
        if (!managers.isEmpty()) {
            WorkerThread toDemote = managers.get(0);
            toDemote.setManager(false);
            managerCount.decrementAndGet();
            LOGGER.info("Demoted assistant manager {}", toDemote.getWorkerId());
        }
    }
    
    private void redistributeWork(WorkerThread failedWorker) {
        List<List<WorkItem>> remainingWork = new ArrayList<>();
        failedWorker.getTaskQueue().drainTo(new ArrayList<>());
        failedWorker.getBatchQueue().drainTo(remainingWork);
        
        if (remainingWork.isEmpty()) return;
        
        // Distribute work to other healthy workers
        List<WorkerThread> healthyWorkers = workers.stream()
            .filter(w -> w.isHealthy() && w != failedWorker)
            .collect(Collectors.toList());
            
        if (healthyWorkers.isEmpty()) {
            LOGGER.error("No healthy workers available to redistribute work!");
            return;
        }
        
        int workerIndex = 0;
        for (List<WorkItem> batch : remainingWork) {
            healthyWorkers.get(workerIndex).assignBatch(batch);
            workerIndex = (workerIndex + 1) % healthyWorkers.size();
        }
    }
}
