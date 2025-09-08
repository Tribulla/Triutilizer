package com.triutilizer.core.concurrency;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellableTask<T> {
    private final CompletableFuture<T> future = new CompletableFuture<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Future<?> running;

    public boolean cancel() {
        cancelled.set(true);
        Future<?> r = running;
        if (r != null) r.cancel(true);
        return true;
    }

    public boolean isCancelled() { return cancelled.get(); }
    public CompletableFuture<T> future() { return future; }
    void setRunning(Future<?> f) { this.running = f; }
}
