package io.cortexj.platform.java25;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Virtual-thread-per-task I/O executor with bounded admission.
 *
 * <p>Audit 2026-10-10: the bare {@code newVirtualThreadPerTaskExecutor()}
 * admitted an unbounded flood (every queued task became a thread and an
 * in-flight blocking I/O), diverging from the bounded pool contract the
 * Java 8/11/17 platforms honor. A fair semaphore caps in-flight tasks at
 * {@code max(8, 2*NCPU)} (override with {@code cortexj.io.width});
 * submitters beyond the width park instead of queueing on the heap -
 * natural backpressure instead of an unbounded virtual-thread stampede.
 * Safe against nesting because current ioExecutor consumers (HF metadata
 * resolution, provider executeAsync) never submit back into it while
 * waiting.
 */
final class BoundedVirtualThreadExecutor extends AbstractExecutorService {

    private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;

    BoundedVirtualThreadExecutor(int width) {
        this.permits = new Semaphore(Math.max(1, width), true);
    }

    static int configuredWidth() {
        int cores = Runtime.getRuntime().availableProcessors();
        int def = Math.max(8, 2 * cores);
        String property = System.getProperty("cortexj.io.width");
        if (property == null || property.trim().isEmpty()) {
            return def;
        }
        try {
            long parsed = Long.parseLong(property.trim());
            return parsed > 0 && parsed <= 65536 ? (int) parsed : def;
        } catch (NumberFormatException invalid) {
            return def;
        }
    }

    @Override
    public void execute(Runnable command) {
        permits.acquireUninterruptibly();
        boolean admitted = false;
        try {
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    permits.release();
                }
            });
            admitted = true;
        } finally {
            if (!admitted) {
                permits.release();
            }
        }
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
