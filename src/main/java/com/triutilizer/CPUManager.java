package com.triutilizer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.triutilizer.WorkerThread;
import com.triutilizer.WorkItem;
import com.triutilizer.BatchManager;
import com.triutilizer.WorkerManager;
import com.triutilizer.BlockUpdateWorkItem;
import com.triutilizer.EntityUpdateWorkItem;

public class CPUManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean debugMode = true;
    private static CPUManager INSTANCE;

    private static final int BATCH_SIZE = 64; // Aligned with Create mod's typical batch sizes

    private final WorkerManager workerManager;
    private final BatchManager batchManager;
    private volatile boolean isRunning = false;
    
    public static synchronized CPUManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CPUManager();
        }
        return INSTANCE;
    }

    public CPUManager() {
        int processorCount = Runtime.getRuntime().availableProcessors();
        this.workerManager = new WorkerManager(processorCount);
        this.batchManager = new BatchManager();
    }

    public void initialize(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        if (!isRunning) {
            isRunning = true;
            LOGGER.info("Initializing CPU Manager with {} threads", Runtime.getRuntime().availableProcessors());
            MinecraftForge.EVENT_BUS.register(this);
            start();
        }
    }

    @SuppressWarnings("unchecked")
    public void registerCommands(com.mojang.brigadier.CommandDispatcher<?> dispatcher) {
        CommandDispatcher<CommandSourceStack> commandDispatcher = (CommandDispatcher<CommandSourceStack>) dispatcher;
        
        commandDispatcher.register(Commands.literal("cpu")
            .then(Commands.literal("status")
                .executes(this::showStatus))
            .then(Commands.literal("workers")
                .executes(this::showWorkers))
            .then(Commands.literal("queues")
                .executes(this::showQueues))
        );
        
        LOGGER.info("Registered CPU Manager commands");
    }

    private int showStatus(CommandContext<CommandSourceStack> context) {
        StringBuilder status = new StringBuilder("=== CPU Manager Status ===\n");
        status.append("Status: ").append(isRunning ? "Running" : "Stopped").append("\n");
        status.append("Total Workers: ").append(workerManager.getWorkers().size()).append("\n");
        status.append("Active Managers: ").append(workerManager.getManagerCount()).append("\n");
        status.append("Batch Size: ").append(BATCH_SIZE).append("\n");
        
        context.getSource().sendSuccess(() -> Component.literal(status.toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private int showWorkers(CommandContext<CommandSourceStack> context) {
        StringBuilder workers = new StringBuilder("=== Worker Status ===\n");
        for (WorkerThread worker : workerManager.getWorkers()) {
            workers.append(String.format("Worker %d: %s%n", 
                worker.getWorkerId(),
                worker.isManager() ? "Manager" : "Worker"))
                .append(String.format("  Load: %d%n", worker.getLoad()))
                .append(String.format("  Healthy: %s%n", worker.isHealthy()))
                .append("\n");
        }
        
        context.getSource().sendSuccess(() -> Component.literal(workers.toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private int showQueues(CommandContext<CommandSourceStack> context) {
        StringBuilder queues = new StringBuilder("=== Queue Status ===\n");
        
        for (WorkerThread worker : workerManager.getWorkers()) {
            BlockingQueue<WorkItem> taskQueue = worker.getTaskQueue();
            BlockingQueue<List<WorkItem>> batchQueue = worker.getBatchQueue();
            
            queues.append(String.format("Worker %d:%n", worker.getWorkerId()))
                  .append(String.format("  Tasks: %d (capacity: %d)%n", 
                         taskQueue.size(), taskQueue.remainingCapacity() + taskQueue.size()))
                  .append(String.format("  Batches: %d (capacity: %d)%n", 
                         batchQueue.size(), batchQueue.remainingCapacity() + batchQueue.size()))
                  .append(String.format("  Total Load: %d items%n", worker.getLoad()))
                  .append(String.format("  Health: %s%n", 
                         worker.isHealthy() ? "Healthy" : "Unhealthy - " + 
                         (worker.isStalled() ? "Stalled" : "Interrupted")))
                  .append("\n");
        }
        
        context.getSource().sendSuccess(() -> Component.literal(queues.toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    public void start() {
        if (!isRunning) {
            isRunning = true;
            LOGGER.info("Starting CPU Manager with {} threads", Runtime.getRuntime().availableProcessors());
            workerManager.start();
        }
    }

    public void stop() {
        if (isRunning) {
            isRunning = false;
            LOGGER.info("Stopping CPU Manager");
            workerManager.stop();
        }
    }

    public void shutdown() {
        stop();
        batchManager.shutdown();
        LOGGER.info("CPU Manager shutdown complete");
    }

    private enum ManagerState {
        ACTIVE,
        PROMOTING_WORKER,
        DEMOTING_MANAGER
    }
    
    public boolean submitTask(WorkItem task) {
        if (!isRunning) {
            LOGGER.warn("Attempted to submit task while CPU Manager is not running");
            return false;
        }
        
        if (task == null) {
            LOGGER.warn("Attempted to submit null task");
            return false;
        }

        // Try to add to an existing batch if possible
        boolean addedToBatch = false;
        List<BatchManager.Batch> readyBatches = batchManager.getReadyBatches();
        for (BatchManager.Batch batch : readyBatches) {
            if (canAddToBatch(task, batch)) {
                if (batchManager.addToBatch(task)) {
                    addedToBatch = true;
                    break;
                }
            }
        }

        // If couldn't add to batch, try submitting as individual task
        if (!addedToBatch) {
            int retryCount = 0;
            while (retryCount < 3) { // Try up to 3 times with different workers
                WorkerThread worker = selectBestWorker(task);
                if (worker.assignTask(task)) {
                    if (debugMode) {
                        LOGGER.debug("Assigned task to worker {}", worker.getWorkerId());
                    }
                    return true;
                }
                retryCount++;
                try {
                    Thread.sleep(50); // Brief pause before retry
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            LOGGER.warn("Failed to assign task after {} attempts", retryCount);
            return false;
        }
        
        return addedToBatch;
    }
    
    private boolean canAddToBatch(WorkItem task, BatchManager.Batch batch) {
        if (batch == null || batch.getItems().isEmpty()) return false;
        WorkItem firstItem = batch.getItems().get(0);
        return task.canBatch(firstItem);
    }
    
    private WorkerThread selectBestWorker(WorkItem task) {
        return workerManager.getWorkers().stream()
            .filter(WorkerThread::isHealthy)
            .min(Comparator.comparingInt(WorkerThread::getLoad))
            .orElseGet(() -> workerManager.getWorkers().get(0)); // Fallback to first worker if none are healthy
    }
    
    // Event handler for server tick - process batches
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (!isRunning || event.phase != TickEvent.Phase.END) return;
        
        // Process world tasks first
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                processWorldTasks(level);
            }
        }
        
        // Then process any ready batches
        List<BatchManager.Batch> readyBatches = batchManager.getReadyBatches();
        for (BatchManager.Batch batch : readyBatches) {
            List<WorkItem> items = batch.getItems();
            if (!items.isEmpty()) {
                // Try to assign batch to workers with retries
                int retryCount = 0;
                boolean assigned = false;
                
                while (!assigned && retryCount < 3) {
                    WorkerThread worker = selectBestWorker(items.get(0));
                    if (worker.assignBatch(items)) {
                        assigned = true;
                        if (debugMode) {
                            LOGGER.debug("Assigned batch of {} items to worker {}", 
                                items.size(), worker.getWorkerId());
                        }
                    } else {
                        retryCount++;
                        try {
                            Thread.sleep(50); // Brief pause before retry
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                
                if (!assigned) {
                    LOGGER.warn("Failed to assign batch after {} attempts", retryCount);
                    // Try submitting items individually as a fallback
                    for (WorkItem item : items) {
                        submitTask(item);
                    }
                }
            }
        }
    }
    
    private void processWorldTasks(ServerLevel level) {
        // Process entities in the level first since they're easier to access
        for (Entity entity : level.getAllEntities()) {
            if (!entity.isRemoved() && !entity.isSpectator()) {
                submitTask(new EntityUpdateWorkItem(level, entity));
            }
        }
        
        // Process block entities in loaded chunks
        // We use a basic approach that works with the limited API access
        BlockPos spawn = level.getSharedSpawnPos();
        int spawnChunkX = spawn.getX() >> 4;
        int spawnChunkZ = spawn.getZ() >> 4;
        int viewDistance = 8; // Default view distance if we can't get it from server
        
        MinecraftServer mcServer = level.getServer();
        if (mcServer != null) {
            viewDistance = mcServer.getPlayerList().getViewDistance();
        }
        
        // Process chunks around spawn
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                int chunkX = spawnChunkX + dx;
                int chunkZ = spawnChunkZ + dz;
                
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk instanceof LevelChunk levelChunk) {
                    // Process block entities
                    for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
                        if (blockEntity.isRemoved()) continue;
                        
                        BlockState state = blockEntity.getBlockState();
                        if (state.getBlock().isRandomlyTicking(state) || blockEntity.getType().isValid(state)) {
                            submitTask(new BlockUpdateWorkItem(level, blockEntity));
                        }
                    }

                    // Process chunks sections to check for blocks that need updates
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        if (!levelChunk.getSection(levelChunk.getSectionIndex(y)).hasOnlyAir()) {
                            for (int dx2 = 0; dx2 < 16; dx2++) {
                                for (int dz2 = 0; dz2 < 16; dz2++) {
                                    BlockPos pos = new BlockPos((chunkX << 4) + dx2, y, (chunkZ << 4) + dz2);
                                    BlockState state = level.getBlockState(pos);
                                    
                                    if (!state.isAir()) {
                                        // Check for blocks that need support
                                        if (!state.canSurvive(level, pos)) {
                                            submitTask(new BlockDestroyedWorkItem(level, pos, state));
                                        }
                                        
                                        // Check for blocks that need updates (redstone, etc)
                                        if (state.isRedstoneConductor(level, pos) || 
                                            state.hasAnalogOutputSignal() ||
                                            state.getPistonPushReaction() != net.minecraft.world.level.material.PushReaction.IGNORE) {
                                            submitTask(new BlockUpdateWorkItem(level, pos));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
