package io.columnar.core;

/**
 * Handle to a viewport subscription. Callers must {@link #close()} to detach.
 *
 * <p>The effective viewport may be changed via {@link #updateViewport(Viewport)}
 * without unsubscribing — the consumer receives an immediate refresh, and further
 * change notifications use the updated viewport.
 */
public interface Subscription extends AutoCloseable {

    /** The viewport this subscription currently delivers against. */
    Viewport viewport();

    /** Whether this subscription is still active. */
    boolean isActive();

    /**
     * Point this subscription at a new viewport, then deliver one synchronous
     * refresh via the subscription callback. Subsequent upstream bumps also use
     * the new viewport.
     *
     * <p>Does nothing if {@link #close()} has already been called.
     */
    void updateViewport(Viewport viewport);

    @Override
    void close();
}
