package com.triutilizer.core.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.triutilizer.core.TriutilizerMod;
import com.triutilizer.core.concurrency.TaskManager;
import com.triutilizer.core.concurrency.Priority;
import com.triutilizer.core.concurrency.CancellableTask;
import com.triutilizer.core.world.ChunkScan;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class TriutilizerRootCommand {
    private TriutilizerRootCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
        Commands.literal("triutilizer")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("bench")
                            .executes(TriutilizerRootCommand::bench)
                            .then(Commands.argument("tasks", IntegerArgumentType.integer(1))
                                .then(Commands.argument("samplesPerTask", IntegerArgumentType.integer(1))
                                    .executes(TriutilizerRootCommand::benchWithArgs))))
            .then(Commands.literal("bench_high")
                .executes(ctx -> benchWithPriority(ctx, Priority.HIGH))
                .then(Commands.argument("tasks", IntegerArgumentType.integer(1))
                    .then(Commands.argument("samplesPerTask", IntegerArgumentType.integer(1))
                        .executes(ctx -> benchWithPriorityArgs(ctx, Priority.HIGH)))))
            .then(Commands.literal("bench_heavy").executes(TriutilizerRootCommand::benchHeavy))
            .then(Commands.literal("cancel_demo").executes(TriutilizerRootCommand::cancelDemo))
            .then(Commands.literal("mem_demo").executes(TriutilizerRootCommand::memDemo))
            .then(Commands.literal("scan").executes(TriutilizerRootCommand::scanDemo))
            .then(Commands.literal("demo_chunked").executes(TriutilizerRootCommand::demoChunked))
            .then(Commands.literal("test").executes(TriutilizerRootCommand::test))
            .then(Commands.literal("stats").executes(TriutilizerRootCommand::stats))
            .then(Commands.literal("debug").executes(TriutilizerRootCommand::debug))
        );
        dispatcher.register(Commands.literal("tri").redirect(root));
    }

    private static int bench(CommandContext<CommandSourceStack> ctx) {
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        int tasks = 8 * cpus;
        int samplesPerTask = 2_500_000; // ~2.5M each, heavier than before
        return runPiBench(ctx, tasks, samplesPerTask, Priority.NORMAL, "bench");
    }

    private static int benchWithArgs(CommandContext<CommandSourceStack> ctx) {
        int tasks = IntegerArgumentType.getInteger(ctx, "tasks");
        int samples = IntegerArgumentType.getInteger(ctx, "samplesPerTask");
        return runPiBench(ctx, tasks, samples, Priority.NORMAL, "bench(custom)");
    }

    private static int benchWithPriority(CommandContext<CommandSourceStack> ctx, Priority prio) {
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        int tasks = 16 * cpus; // heavier default
        int samplesPerTask = 5_000_000;
        return runPiBench(ctx, tasks, samplesPerTask, prio, "bench(high)");
    }

    private static int benchWithPriorityArgs(CommandContext<CommandSourceStack> ctx, Priority prio) {
        int tasks = IntegerArgumentType.getInteger(ctx, "tasks");
        int samples = IntegerArgumentType.getInteger(ctx, "samplesPerTask");
        return runPiBench(ctx, tasks, samples, prio, "bench(high,custom)");
    }

    private static int benchHeavy(CommandContext<CommandSourceStack> ctx) {
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        int tasks = Math.max(32, 32 * cpus);
        int samplesPerTask = 10_000_000; // 10M
        return runPiBench(ctx, tasks, samplesPerTask, Priority.HIGH, "bench(heavy)");
    }

    private static int runPiBench(CommandContext<CommandSourceStack> ctx, int tasks, int samplesPerTask, Priority prio, String label) {
        long start = System.nanoTime();
        List<CompletableFuture<Integer>> futures = new ArrayList<>(tasks);
        for (int i = 0; i < tasks; i++) {
            futures.add(TaskManager.submit(() -> {
                int inside = 0;
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int s = 0; s < samplesPerTask; s++) {
                    double x = r.nextDouble();
                    double y = r.nextDouble();
                    // Extra math to reduce branch prediction and better CPU burn
                    double d = x * x + y * y;
                    if (d <= 1.0) inside++;
                }
                return inside;
            }, prio));
        }
        TaskManager.sequence(futures).whenComplete((list, err) -> com.triutilizer.core.util.MainThread.execute(() -> {
            if (err != null) {
                TriutilizerMod.LOGGER.error("Bench failed", err);
                ctx.getSource().sendFailure(Component.literal("Triutilizer " + label + " failed: " + err.getMessage()));
                return;
            }
            long totalInside = 0;
            for (int v : list) totalInside += v;
            long totalSamples = (long) tasks * samplesPerTask;
            double pi = 4.0 * totalInside / (double) totalSamples;
            long ms = (System.nanoTime() - start) / 1_000_000L;
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "Triutilizer %s: tasks=%d samples=%d pi≈%.6f time=%dms", label, tasks, totalSamples, pi, ms
            )), true);
        }));
        return 1;
    }

    private static int cancelDemo(CommandContext<CommandSourceStack> ctx) {
        // Start a long-running task that we cancel after 1s
        CancellableTask<Long> ct = TaskManager.submitCancellable(() -> {
            long sum = 0;
            for (long i = 0; i < 10_000_000_000L; i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
                sum += i;
                if ((i & ((1<<20)-1)) == 0) Thread.onSpinWait();
            }
            return sum;
        }, Priority.LOW);
        ctx.getSource().sendSuccess(() -> Component.literal("Started cancel_demo; will cancel in 1s"), true);
    // Schedule cancellation after 1s on a worker thread
    TaskManager.run(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            ct.cancel();
        }, Priority.HIGH);
        ct.future().whenComplete((res, err) -> com.triutilizer.core.util.MainThread.execute(() -> {
            if (err != null || ct.isCancelled()) {
                ctx.getSource().sendSuccess(() -> Component.literal("cancel_demo: cancelled"), true);
            } else {
                ctx.getSource().sendSuccess(() -> Component.literal("cancel_demo: finished result=" + res), true);
            }
        }));
        return 1;
    }

    private static int memDemo(CommandContext<CommandSourceStack> ctx) {
        // Build a compact table and index, then parallel scan
        com.triutilizer.core.memory.PackedIntTable table = new com.triutilizer.core.memory.PackedIntTable(200_000);
        com.triutilizer.core.memory.IntKeyIndex index = new com.triutilizer.core.memory.IntKeyIndex(50_000);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 200_000; i++) {
            int a = r.nextInt(50_000);
            int b = r.nextInt();
            table.add(a, b);
            index.add(a);
        }
        java.util.List<Integer> keys = new java.util.ArrayList<>();
        for (int k = 0; k < 5_000; k++) keys.add(k);
        long start = System.nanoTime();
        TaskManager.mapParallel(keys, key -> {
            // For each key, sum B values of its rows
            final int[] acc = {0};
            index.forEachByKey(key, (row, kk) -> acc[0] += table.getB(row));
            return acc[0];
        }).whenComplete((sums, err) -> com.triutilizer.core.util.MainThread.execute(() -> {
            if (err != null) {
                ctx.getSource().sendFailure(Component.literal("mem_demo failed: " + err.getMessage()));
                return;
            }
            long ms = (System.nanoTime() - start) / 1_000_000L;
            long cs = 0;
            for (int v : sums) cs += v;
            final long checksum = cs;
            ctx.getSource().sendSuccess(() -> Component.literal("mem_demo: groups=5000 rows=200k checksum=" + checksum + " time=" + ms + "ms"), true);
        }));
        return 1;
    }

    private static int scanDemo(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            src.sendFailure(Component.literal("Scan failed: " + e.getMessage()));
            return 0;
        }
        var level = src.getLevel();
        var pos = player.blockPosition();
        var chunkPos = new net.minecraft.world.level.ChunkPos(pos);
        src.sendSuccess(() -> Component.literal("Triutilizer scan started (radius=1)..."), true);
        ChunkScan.findBlocks(level, chunkPos, 1, (state, bp) -> state.isAir()).whenComplete((list, err) -> com.triutilizer.core.util.MainThread.execute(() -> {
            if (err != null) {
                src.sendFailure(Component.literal("Scan failed: " + err.getMessage()));
                return;
            }
            src.sendSuccess(() -> Component.literal("Scan complete: found " + list.size() + " air blocks in radius 1."), true);
        }));
        return 1;
    }

    // Demonstrates chunked mapping with heavier per-element work to show real parallel speedup.
    private static int demoChunked(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        int n = 1_000_000; // 1M items
        java.util.ArrayList<Integer> data = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) data.add(i);
        int minChunk = 50_000; // ~20 chunks -> low overhead
        long start = System.nanoTime();
        TaskManager.mapChunked(data, minChunk, chunk -> {
            long acc = 0;
            for (int v : chunk) {
                // Heavier work per element
                double x = Math.sin(v) * Math.cos(v * 0.5) + Math.sqrt(v + 1.0);
                acc += (long) (x * 1000);
            }
            return acc;
        }, Priority.HIGH).whenComplete((results, err) -> com.triutilizer.core.util.MainThread.execute(() -> {
            if (err != null) {
                src.sendFailure(Component.literal("demo_chunked failed: " + err.getMessage()));
                return;
            }
            long sumTmp = 0; for (long r : results) sumTmp += r;
            final long sum = sumTmp;
            final long ms = (System.nanoTime() - start) / 1_000_000L;
            src.sendSuccess(() -> Component.literal("demo_chunked: n=1,000,000 chunks=" + results.size() + " checksum=" + sum + " time=" + ms + "ms"), true);
        }));
        return 1;
    }

    private static int test(CommandContext<CommandSourceStack> ctx) {
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        ctx.getSource().sendSuccess(() -> Component.literal("Triutilizer: available processors = " + cpus), true);
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        TaskManager.Stats s = TaskManager.getStats();
        if (!s.debug) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                "Threads=" + s.threads + " (core=" + s.core + ", max=" + s.max + ")" +
                ", Active=" + s.active + ", Queue=" + s.queueSize + ", Completed=" + s.completedTaskCount + ", Submitted=" + s.submitted + ", Finished=" + s.completed
            ), false);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Threads=").append(s.threads)
                            .append(" (core=").append(s.core).append(", max=").append(s.max).append(")")
              .append(", Active=").append(s.active)
              .append(", Queue=").append(s.queueSize)
              .append(", Completed=").append(s.completedTaskCount)
              .append(", Submitted=").append(s.submitted)
              .append(", Finished=").append(s.completed)
              .append("\nWorkers:\n");
                        for (var w : s.workers) {
                                sb.append(" - ").append(w.name)
                                    .append(" running=").append(w.running)
                                    .append(" runs=").append(w.runs)
                                    .append(" lastStartMs=").append(w.lastStartMs)
                                    .append(" lastDurationMs=").append(w.lastDurationMs)
                                    .append(" runningForMs=").append(w.runningForMs)
                                    .append(" idleForMs=").append(w.idleForMs)
                                    .append(" totalBusyMs=").append(w.totalBusyMs)
                                    .append(" util=").append(w.utilizationPct).append("%")
                                    .append("\n");
                        }
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        }
        return 1;
    }

    private static int debug(CommandContext<CommandSourceStack> ctx) {
        boolean on = !TaskManager.isDebug();
        TaskManager.setDebug(on);
        ctx.getSource().sendSuccess(() -> Component.literal("Triutilizer debug=" + on), true);
        return 1;
    }
}
