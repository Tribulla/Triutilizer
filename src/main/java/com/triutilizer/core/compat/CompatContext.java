package com.triutilizer.core.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Holds compatibility flags inferred from environment. */
public final class CompatContext {
    private static final Logger LOGGER = LoggerFactory.getLogger("triutilizer");
    private final List<String> reasons = new ArrayList<>();
    private final AtomicInteger threadCap = new AtomicInteger(0);

    public void requestSingleThread(String reason) { setThreadCap(1, reason); }

    public void setThreadCap(int maxThreads, String reason) {
        if (maxThreads < 1) maxThreads = 1;
        int prev = threadCap.getAndAccumulate(maxThreads, Math::max);
        if (maxThreads > prev) {
            reasons.add(reason);
            LOGGER.info("Compat: applying thread cap {} due to {}", maxThreads, reason);
        }
    }

    public int effectiveThreadCap() { return threadCap.get(); }
    public List<String> reasons() { return reasons; }
}
