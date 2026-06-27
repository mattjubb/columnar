package io.columnar.core;

import io.columnar.api.BaseTable;
import io.columnar.api.BasicSubscription;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.Schema;
import io.columnar.api.Subscription;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Operator-backed virtual table. {@code read(viewport)} computes the operator
 * lazily; consecutive reads of the same viewport short-circuit when none of
 * the upstream tables have advanced.
 *
 * <p>Version derivation: this table's {@link #version()} is the sum of all
 * upstream {@link Table#version() versions}, which is a cheap monotonic
 * proxy that bumps whenever any source bumps.
 *
 * <p>This is the simplest possible derived-table implementation — a single
 * cached slice. The {@code :query} module will replace it with a multi-slot
 * {@code MaterializationCache} keyed by {@code (operator, viewport)} once
 * multiple subscribers are in play.
 */
public final class DerivedTable implements Table {

    private final Operator operator;
    private final PullContext computeContext;
    private final List<Table> upstreams;

    private volatile Viewport cachedViewport;
    private volatile ColumnarSlice cachedSlice;
    private volatile Object2LongMap<Table> cachedVersionVector;

    public DerivedTable(Operator operator) {
        this(operator, PullContext.direct());
    }

    /** @param computeContext Pull context passed to {@link Operator#compute} (may cache upstream reads). */
    public DerivedTable(Operator operator, PullContext computeContext) {
        this.operator = operator;
        this.computeContext = computeContext != null ? computeContext : PullContext.direct();
        this.upstreams = collectAllUpstreams(operator);
    }

    public Operator operator() {
        return operator;
    }

    public List<Table> upstreams() {
        return upstreams;
    }

    public PullContext computeContext() {
        return computeContext;
    }

    @Override
    public Schema schema() {
        return operator.outputSchema();
    }

    @Override
    public long version() {
        long sum = 0L;
        for (Table t : upstreams) sum += t.version();
        return sum;
    }

    @Override
    public long size() {
        // Size is data-dependent (filter changes it). Materialize Viewport.ALL to
        // count, but cache it via the same path so repeated calls are cheap.
        return read(Viewport.ALL).rowCount();
    }

    @Override
    public synchronized ColumnarSlice read(Viewport viewport) {
        Object2LongMap<Table> currentVV = currentVersionVector();
        if (cachedSlice != null
                && viewport.equals(cachedViewport)
                && currentVV.equals(cachedVersionVector)) {
            return cachedSlice;
        }
        ColumnarSlice computed = operator.compute(viewport, computeContext);
        cachedViewport = viewport;
        cachedSlice = computed;
        cachedVersionVector = currentVV;
        return computed;
    }

    /** Force-clear the cache. Useful for invalidation tests / explicit refresh. */
    public synchronized void invalidate() {
        cachedSlice = null;
        cachedViewport = null;
        cachedVersionVector = null;
    }

    private Object2LongMap<Table> currentVersionVector() {
        Object2LongOpenHashMap<Table> vv = new Object2LongOpenHashMap<>(upstreams.size() * 2);
        for (Table t : upstreams) {
            vv.put(t, t.version());
        }
        return vv;
    }

    /** Walk operator tree to collect every distinct upstream {@link Table}. */
    private static List<Table> collectAllUpstreams(Operator op) {
        java.util.LinkedHashSet<Table> ts = new java.util.LinkedHashSet<>();
        collectInto(op, ts);
        return List.copyOf(ts);
    }

    private static void collectInto(Operator op, java.util.LinkedHashSet<Table> out) {
        out.addAll(op.upstreams());
    }

    /** Register an invalidate-on-change listener on every upstream {@link BaseTable}. */
    public void wireListeners() {
        for (Table t : upstreams) {
            if (t instanceof BaseTable bt) {
                bt.addListener(v -> invalidate());
            }
        }
    }

    /**
     * Subscribe to changes to {@code viewport}. The callback fires once
     * immediately with the current materialization of the viewport, then again
     * synchronously every time any upstream {@link BaseTable} mutates.
     *
     * <p>The cache is invalidated before re-reading, so the callback always
     * sees a freshly materialized slice consistent with the new upstream version
     * vector. Sealed upstream tables never bump and therefore never trigger
     * further deliveries — only the initial slice is delivered.
     *
     * <p>Use {@link Subscription#updateViewport(Viewport)} to change what is
     * materialized without resubscribing; that triggers one immediate refresh and
     * applies to subsequent upstream notifications.
     *
     * <p>The callback runs on the thread that performed the mutation upstream.
     * Keep it cheap or dispatch work to an executor. The {@code TickCoordinator}
     * in {@code :query} will eventually offer batched / asynchronous delivery.
     */
    @Override
    public Subscription subscribe(Viewport viewport, Consumer<ColumnarSlice> onChange) {
        BasicSubscription[] holder = new BasicSubscription[1];
        Consumer<Long> listener = version -> {
            BasicSubscription s = holder[0];
            if (s == null || !s.isActive()) return;
            invalidate();
            onChange.accept(read(s.viewport()));
        };

        List<BaseTable> wired = new ArrayList<>();
        for (Table t : upstreams) {
            if (t instanceof BaseTable bt) {
                bt.addListener(listener);
                wired.add(bt);
            }
        }

        BasicSubscription sub = new BasicSubscription(
                viewport,
                newVp -> onChange.accept(read(newVp)),
                () -> {
                    for (BaseTable bt : wired) {
                        bt.removeListener(listener);
                    }
                });
        holder[0] = sub;

        // Initial delivery: fire once with the current slice so subscribers don't
        // need a separate "read then subscribe" prelude.
        onChange.accept(read(viewport));

        return sub;
    }
}
