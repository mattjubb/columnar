package io.columnar.query;

import org.jctools.queues.MpscGrowableArrayQueue;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Batches runnable work queued from mutation paths and optionally drains them on a fixed cadence.
 * Backed by a JCTools MPSC queue for cheap multi-producer ingestion.
 */
public final class TickCoordinator implements AutoCloseable {

    private final MpscGrowableArrayQueue<Runnable> queue =
            new MpscGrowableArrayQueue<>(8192);

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public TickCoordinator() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "columnar-TickCoordinator");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    /** Enqueue work from mutation hot-paths — non-blocking unless the queue is full. */
    public void enqueue(Runnable work) {
        Objects.requireNonNull(work, "work");
        if (closed.get()) {
            throw new IllegalStateException("TickCoordinator closed");
        }
        if (!queue.offer(work)) {
            throw new IllegalStateException("TickCoordinator backlog overflow — increase capacity or consume faster");
        }
    }

    /**
     * Drain all pending work synchronously using {@code executor}. Each runnable is submitted;
     * the method returns immediately (async fan-out).
     */
    public void drainAsyncOn(Executor executor) {
        Runnable r;
        while ((r = queue.poll()) != null) {
            executor.execute(r);
        }
    }

    /** Drain strictly on the calling thread — useful for tests and synchronous refresh. */
    public void drainBlocking() {
        Runnable r;
        while ((r = queue.poll()) != null) {
            r.run();
        }
    }

    /** JCTools-style drain consuming up to {@code limit} entries on the invoking thread. */
    public void drainBlocking(int limit) {
        queue.drain(r -> r.run(), limit);
    }

    /** Schedule periodic draining on this coordinator's single scheduler thread. */
    public ScheduledFuture<?> scheduleDrain(long periodMillis, Executor callbackExecutor) {
        return scheduler.scheduleAtFixedRate(
                () -> drainAsyncOn(callbackExecutor),
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS);
    }

    /** Raw queue depth — diagnostic / back-pressure signal. */
    public int approximateBacklog() {
        return queue.size();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdown();
        }
    }
}
