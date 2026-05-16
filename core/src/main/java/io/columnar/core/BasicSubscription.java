package io.columnar.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Holds a mutable viewport plus a synchronous refresher and a run-once disposer.
 */
public final class BasicSubscription implements Subscription {

    private volatile Viewport viewport;
    private final Consumer<Viewport> refresher;
    private final Runnable disposer;
    private final AtomicBoolean active = new AtomicBoolean(true);

    public BasicSubscription(Viewport viewport, Consumer<Viewport> refresher, Runnable disposer) {
        this.viewport = viewport;
        this.refresher = refresher;
        this.disposer = disposer;
    }

    @Override
    public Viewport viewport() {
        return viewport;
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public void updateViewport(Viewport viewport) {
        if (!active.get()) return;
        this.viewport = viewport;
        refresher.accept(viewport);
    }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            disposer.run();
        }
    }
}
