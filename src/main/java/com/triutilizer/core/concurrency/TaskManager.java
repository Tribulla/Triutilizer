package com.triutilizer.core.concurrency;

import com.triutilizer.core.TriutilizerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongConsumer;

/**
 * Central worker pool for CPU-bound tasks. Do not access Minecraft game state off-thread.
 */
public final class TaskManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("triutilizer");
    private static volatile ThreadPoolExecutor EXECUTOR;
    private static final java.util.concurrent.ConcurrentHashMap<String, WorkerInfo> WORKERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong SUBMITTED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong COMPLETED = new java.util.concurrent.atomic.AtomicLong();
    private static volatile boolean DEBUG = false;

    private TaskManager() {}

    public static synchronized void init() {
        if (EXECUTOR != null && !EXECUTOR.isShutdown()) return;
    int available = Math.max(1, Runtime.getRuntime().availableProcessors());
    int configuredViaSysProp = Math.max(1, Integer.getInteger("triutilizer.threads", available - 1));
    int cfgVal = TriutilizerConfig.threads != null ? TriutilizerConfig.threads.get() : configuredViaSysProp;
    int threads = Math.max(1, cfgVal);
    // Compat caps are no longer used for global single-threading; tasks must avoid off-thread world access instead.
    ThreadFactory tf = new NamedThreadFactory("triutilizer-worker");
    BlockingQueue<Runnable> q = new PriorityBlockingQueue<>(1024, (a, b) -> {
        int pa = (a instanceof PrioritizedRunnable pr) ? pr.priority.level : Priority.NORMAL.level;
        int pb = (b instanceof PrioritizedRunnable pr) ? pr.priority.level : Priority.NORMAL.level;
        int c = Integer.compare(pb, pa); // higher priority first
        if (c != 0) return c;
        long sa = (a instanceof PrioritizedRunnable pr) ? pr.seq : Long.MAX_VALUE;
        long sb = (b instanceof PrioritizedRunnable pr) ? pr.seq : Long.MAX_VALUE;
        return Long.compare(sa, sb);
    });
    EXECUTOR = new ThreadPoolExecutor(
        threads,
        threads,
        30L, TimeUnit.SECONDS,
        q,
        tf,
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
    // Optional: prestart threads so names appear immediately and stats begin tracking
    EXECUTOR.prestartAllCoreThreads();
    LOGGER.info("TaskManager initialized with {} threads (available CPUs: {})", threads, available);
    }

    public static <T> CompletableFuture<T> submit(Callable<T> callable) {
        return submit(callable, Priority.NORMAL);
    }

    public static <T> CompletableFuture<T> submit(Callable<T> callable, Priority priority) {
        ensure();
        CompletableFuture<T> cf = new CompletableFuture<>();
    SUBMITTED.incrementAndGet();
    EXECUTOR.submit(wrap(callable, cf, priority, null));
        return cf;
    }

    public static <T> CancellableTask<T> submitCancellable(Callable<T> callable, Priority priority) {
        ensure();
        CancellableTask<T> ct = new CancellableTask<>();
    SUBMITTED.incrementAndGet();
    Future<?> f = EXECUTOR.submit(wrap(callable, ct.future(), priority, ct));
        ct.setRunning(f);
        return ct;
    }

    public static CompletableFuture<Void> run(Runnable runnable) {
        return submit(() -> { runnable.run(); return null;}, Priority.NORMAL);
    }

    public static CompletableFuture<Void> run(Runnable runnable, Priority priority) {
        return submit(() -> { runnable.run(); return null;}, priority).thenApply(v -> null);
    }

    public static <T, R> CompletableFuture<List<R>> mapParallel(Collection<T> input, Function<T, R> fn) {
        ensure();
        List<CompletableFuture<R>> futures = new ArrayList<>(input.size());
        for (T t : input) {
            futures.add(submit(() -> fn.apply(t)));
        }
        return sequence(futures);
    }

    public static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        ensure();
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return all.thenApply(v -> {
            List<T> out = new ArrayList<>(futures.size());
            for (CompletableFuture<T> f : futures) {
                out.add(f.join());
            }
            return out;
        });
    }

    public static synchronized void shutdown() {
        if (EXECUTOR != null) {
            EXECUTOR.shutdown();
            EXECUTOR = null;
        }
    }

    // Allow compat layer to reduce the number of worker threads at runtime (cannot increase above initial core size).
    public static synchronized void setThreadCap(int maxThreads) {
        if (maxThreads < 1) maxThreads = 1;
        if (EXECUTOR == null) return; // will be applied on next init
        int currentCore = EXECUTOR.getCorePoolSize();
        int currentMax = EXECUTOR.getMaximumPoolSize();
        int newSize = Math.max(1, Math.min(Math.min(currentCore, currentMax), maxThreads));
        if (newSize < currentCore) {
            EXECUTOR.setCorePoolSize(newSize);
        }
        if (newSize < currentMax) {
            EXECUTOR.setMaximumPoolSize(newSize);
        }
    }

    private static void ensure() {
        if (EXECUTOR == null) init();
    }

    // Low-overhead parallel numeric range: splits into at most 'p' tasks where p is the core pool size.
    public static CompletableFuture<Void> forRange(long startInclusive, long endExclusive, LongConsumer body) {
        return forRange(startInclusive, endExclusive, body, Priority.NORMAL);
    }

    public static CompletableFuture<Void> forRange(long startInclusive, long endExclusive, LongConsumer body, Priority priority) {
        ensure();
        final long len = Math.max(0L, endExclusive - startInclusive);
        if (len == 0) return CompletableFuture.completedFuture(null);
        final int parts = Math.max(1, Math.min(EXECUTOR.getCorePoolSize(), (int)Math.min(Integer.MAX_VALUE, len)));
        java.util.ArrayList<CompletableFuture<Void>> fs = new java.util.ArrayList<>(parts);
        for (int part = 0; part < parts; part++) {
            final int idx = part;
            fs.add(submit(() -> {
                for (long i = startInclusive + idx; i < endExclusive; i += parts) {
                    body.accept(i);
                }
                return null;
            }, priority));
        }
        return CompletableFuture.allOf(fs.toArray(new CompletableFuture[0]));
    }

    // Chunked mapping to reduce per-element future overhead. Processes N chunks in parallel and returns one result per chunk.
    public static <T, R> CompletableFuture<List<R>> mapChunked(List<T> input, int minChunkSize, Function<List<T>, R> fn) {
        return mapChunked(input, minChunkSize, fn, Priority.NORMAL);
    }

    public static <T, R> CompletableFuture<List<R>> mapChunked(List<T> input, int minChunkSize, Function<List<T>, R> fn, Priority priority) {
        ensure();
        final int size = input.size();
        if (size == 0) return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        final int parts = Math.max(1, EXECUTOR.getCorePoolSize());
        int chunkSize = Math.max(1, Math.max(minChunkSize, (size + parts - 1) / parts));
        java.util.ArrayList<CompletableFuture<R>> fs = new java.util.ArrayList<>();
        for (int start = 0; start < size; start += chunkSize) {
            int end = Math.min(size, start + chunkSize);
            final List<T> view = input.subList(start, end);
            fs.add(submit(() -> fn.apply(view), priority));
        }
        return sequence(fs);
    }

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static abstract class PrioritizedRunnable implements Runnable {
        final Priority priority;
        final long seq;
        PrioritizedRunnable(Priority p) { this.priority = p; this.seq = SEQUENCE.incrementAndGet(); }
    }

    private static <T> Runnable wrap(Callable<T> callable, CompletableFuture<T> cf, Priority p, CancellableTask<T> ct) {
        return new PrioritizedRunnable(p) {
            @Override public void run() {
                final Thread t = Thread.currentThread();
                final String tn = t.getName();
                final WorkerInfo wi = WORKERS.computeIfAbsent(tn, WorkerInfo::new);
                wi.markStart();
                if (ct != null && ct.isCancelled()) { cf.cancel(true); return; }
                try {
                    cf.complete(callable.call());
                } catch (Throwable ex) {
                    cf.completeExceptionally(ex);
                }
                COMPLETED.incrementAndGet();
                wi.markEnd();
            }
        };
    }

    public static Stats getStats() {
        ensure();
        Stats s = new Stats();
        s.threads = EXECUTOR.getPoolSize();
        s.core = EXECUTOR.getCorePoolSize();
        s.max = EXECUTOR.getMaximumPoolSize();
        s.active = EXECUTOR.getActiveCount();
        s.queueSize = EXECUTOR.getQueue().size();
        s.completedTaskCount = EXECUTOR.getCompletedTaskCount();
        s.submitted = SUBMITTED.get();
        s.completed = COMPLETED.get();
        s.debug = DEBUG;
    // Compat info removed: no global cap applied now.
        java.util.ArrayList<WorkerSnapshot> ws = new java.util.ArrayList<>(WORKERS.size());
        for (WorkerInfo wi : WORKERS.values()) {
            ws.add(wi.snapshot());
        }
        s.workers = ws;
        return s;
    }

    public static void setDebug(boolean on) { DEBUG = on; }
    public static boolean isDebug() { return DEBUG; }

    public static final class Stats {
        public int threads;
    public int core;
    public int max;
        public int active;
        public int queueSize;
        public long completedTaskCount;
        public long submitted;
        public long completed;
        public boolean debug;
        public java.util.List<WorkerSnapshot> workers;
    }

    public static final class WorkerSnapshot {
        public String name;
        public boolean running;
        public long runs;
        public long lastDurationMs;
        public long lastStartMs;
    public long runningForMs;
    public long idleForMs;
    public long totalBusyMs;
    public int utilizationPct;
    }

    private static final class WorkerInfo {
        final String name;
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicLong runs = new java.util.concurrent.atomic.AtomicLong();
    volatile Thread thread;
    volatile long lastStartNs;
    volatile long lastDurationNs;
    volatile long lastEndNs;
    volatile long busyAccumNs;
    final long createdNs = System.nanoTime();

        WorkerInfo(String name) { this.name = name; }

        void markStart() {
            running.set(true);
            runs.incrementAndGet();
            lastStartNs = System.nanoTime();
        }

        void markEnd() {
            long now = System.nanoTime();
            lastDurationNs = now - lastStartNs;
            lastEndNs = now;
            busyAccumNs += lastDurationNs;
            running.set(false);
        }

        WorkerSnapshot snapshot() {
            WorkerSnapshot s = new WorkerSnapshot();
            s.name = name;
            boolean isRunning = running.get();
            // If we have a thread ref, also consider RUNNABLE state as running (best-effort)
            Thread th = thread;
            if (th != null) {
                Thread.State st = th.getState();
                if (st == Thread.State.RUNNABLE) isRunning = true;
            }
            s.running = isRunning;
            s.runs = runs.get();
            long now = System.nanoTime();
            s.lastDurationMs = lastDurationNs / 1_000_000L;
            s.lastStartMs = lastStartNs / 1_000_000L;
            s.runningForMs = isRunning ? (now - lastStartNs) / 1_000_000L : 0L;
            s.idleForMs = isRunning ? 0L : (lastEndNs == 0L ? (now - createdNs) / 1_000_000L : (now - lastEndNs) / 1_000_000L);
            long busy = busyAccumNs + (isRunning ? (now - lastStartNs) : 0L);
            s.totalBusyMs = busy / 1_000_000L;
            long elapsed = Math.max(1L, now - createdNs);
            s.utilizationPct = (int)Math.min(100, Math.round((busy * 100.0) / elapsed));
            return s;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final ThreadFactory backing = Executors.defaultThreadFactory();
        private final String base;
        private final AtomicInteger count = new AtomicInteger();

        private NamedThreadFactory(String base) { this.base = Objects.requireNonNull(base); }

        @Override public Thread newThread(Runnable r) {
            Thread t = backing.newThread(r);
            t.setName(base + "-" + count.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            // Register thread for stats
            WorkerInfo wi = WORKERS.computeIfAbsent(t.getName(), WorkerInfo::new);
            wi.thread = t;
            return t;
        }
    }
}
