package io.columnar.api;

import io.columnar.api.store.ColumnStore;
import io.columnar.api.store.ColumnStores;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * The framework's single source-table implementation. A {@code BaseTable} starts
 * mutable: {@link #appendRow(Object...) row-append} and {@link #row() typed-row}
 * mutations bump {@link #version()} and notify registered listeners. Calling
 * {@link #seal()} makes the table permanently read-only — further mutation
 * attempts throw, the version stops advancing, and downstream caches that key
 * off {@code version()} naturally short-circuit.
 *
 * <p>Two common construction conventions:
 * <ul>
 *   <li>{@link Table#builder(Schema)} — populate then {@code build()}, which
 *       returns a sealed table whose {@link #version()} is normalized to zero
 *       after construction. Use this for reference data and tests.</li>
 *   <li>{@link Table#create(Schema)} — leave open; append over time. Use this
 *       for live ticking streams.</li>
 * </ul>
 *
 * <p>Thread-safety: a {@link ReentrantReadWriteLock} guards mutations and
 * snapshots. Multiple concurrent appenders are supported (writers serialize)
 * but the typical layout is single-writer / many-reader.
 *
 * <p>Listeners (registered via {@link #addListener(Consumer)}) are notified on
 * every version bump with the new version. Used by {@code DerivedTable} to
 * mark downstream operators dirty. Registering a listener on a sealed table is
 * a no-op functionally — sealed tables never bump and never fire.
 */
public final class BaseTable implements Table {

    private final Schema schema;
    private final List<ColumnStore> stores;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Consumer<Long>> listeners = new ArrayList<>();
    private volatile long version;
    private volatile long size;
    private volatile boolean sealed;

    private BaseTable(Schema schema, List<ColumnStore> stores) {
        this.schema = schema;
        this.stores = stores;
    }

    /** Create a fresh open (unsealed) table backed by per-column stores. */
    public static BaseTable create(Schema schema) {
        List<ColumnStore> stores = new ArrayList<>(schema.size());
        for (int i = 0; i < schema.size(); i++) {
            stores.add(ColumnStores.create(schema.field(i)));
        }
        return new BaseTable(schema, stores);
    }

    /** Start a builder that {@code build()}s into a sealed {@link BaseTable}. */
    public static Builder builder(Schema schema) {
        return new Builder(create(schema));
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public long size() {
        return size;
    }

    public boolean isSealed() {
        return sealed;
    }

    /**
     * Permanently freeze this table. After {@code seal()}, mutation attempts
     * throw {@link IllegalStateException}, the version stops advancing, and
     * downstream caches that key off {@link #version()} stay valid forever.
     * Idempotent — repeated calls are no-ops.
     */
    public void seal() {
        lock.writeLock().lock();
        try {
            if (sealed) {
                return;
            }
            for (ColumnStore s : stores) {
                s.sealActive();
            }
            sealed = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Freeze like {@link #seal()}, then reset {@link #version} to {@code 0}.
     *
     * <p>Used only by {@link Builder#build()} for immutable reference snapshots: each row append
     * during population temporarily bumped {@code version}, but exported reads must see a sealed
     * table whose version stays at the invariant {@code 0} ("never bumps again").
     */
    private void sealFromBuilderFreezeVersion() {
        lock.writeLock().lock();
        try {
            if (sealed) {
                return;
            }
            for (ColumnStore s : stores) {
                s.sealActive();
            }
            sealed = true;
            version = 0L;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Append a single row using boxed values. Length must match the schema. */
    public void appendRow(Object... values) {
        if (values.length != schema.size()) {
            throw new IllegalArgumentException(
                    "row has " + values.length + " values; schema has " + schema.size());
        }
        lock.writeLock().lock();
        try {
            ensureMutable();
            for (int c = 0; c < values.length; c++) {
                stores.get(c).append(values[c]);
            }
            size++;
            version++;
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
    }

    /**
     * Open a typed row appender. Use the typed {@code setX} setters to avoid
     * boxing on the hot path. The write lock is held until {@code commit()}.
     */
    public RowAppender row() {
        lock.writeLock().lock();
        try {
            ensureMutable();
        } catch (RuntimeException e) {
            lock.writeLock().unlock();
            throw e;
        }
        return new RowAppender(stores, () -> {
            try {
                size++;
                version++;
            } finally {
                lock.writeLock().unlock();
            }
            notifyListeners();
        });
    }

    private void ensureMutable() {
        if (sealed) {
            throw new IllegalStateException("table is sealed and cannot be mutated");
        }
    }

    private void notifyListeners() {
        long v = version;
        for (Consumer<Long> l : listeners) {
            l.accept(v);
        }
    }

    /**
     * Register a listener invoked on every version bump (after the bump
     * completes). Registering on a sealed table is allowed but the listener
     * will never fire.
     */
    public void addListener(Consumer<Long> listener) {
        lock.writeLock().lock();
        try {
            listeners.add(listener);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeListener(Consumer<Long> listener) {
        lock.writeLock().lock();
        try {
            listeners.remove(listener);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public ColumnarSlice read(Viewport viewport) {
        long versionAt;
        long sizeAt;
        List<Column> snapshot;
        lock.readLock().lock();
        try {
            versionAt = version;
            sizeAt = size;
            snapshot = new ArrayList<>(stores.size());
            for (ColumnStore s : stores) {
                snapshot.add(s.snapshot());
            }
        } finally {
            lock.readLock().unlock();
        }

        long from = viewport.rows().from();
        long to = Math.min(viewport.rows().to(), sizeAt);
        if (to < from) to = from;
        List<Column> sliced = Slicing.slice(snapshot, from, to);

        Schema effectiveSchema = schema;
        if (viewport.columns().isPresent()) {
            List<String> picked = new ArrayList<>(viewport.columns().get());
            sliced = Slicing.project(sliced, picked);
            effectiveSchema = schema.select(picked);
        }
        long rowCount = sliced.isEmpty() ? 0L : sliced.get(0).size();
        if (viewport.hasLimit()) {
            long limit = viewport.limit().getAsLong();
            if (rowCount > limit) {
                sliced = Slicing.slice(sliced, 0, limit);
                rowCount = limit;
            }
        }
        return new ColumnarSlice(effectiveSchema, sliced, rowCount, versionAt);
    }

    /**
     * Subscribe to changes for {@code viewport}. The callback fires once
     * immediately with the current contents, then again synchronously on every
     * subsequent mutation. Sealed tables fire only the initial delivery.
     *
     * <p>Use {@link Subscription#updateViewport(Viewport)} to change rows, columns,
     * or limits without resubscribing; that triggers an immediate re-read.
     */
    @Override
    public Subscription subscribe(Viewport viewport, Consumer<ColumnarSlice> onChange) {
        BasicSubscription[] holder = new BasicSubscription[1];
        Consumer<Long> listener = v -> {
            BasicSubscription s = holder[0];
            if (s != null && s.isActive()) {
                onChange.accept(read(s.viewport()));
            }
        };
        BasicSubscription sub = new BasicSubscription(
                viewport,
                newVp -> onChange.accept(read(newVp)),
                () -> removeListener(listener));
        holder[0] = sub;
        addListener(listener);
        onChange.accept(read(viewport));
        return sub;
    }

    /**
     * Fluent builder that auto-seals on {@link #build()}. Useful for reference
     * data and tests.
     */
    public static final class Builder {

        private final BaseTable table;

        private Builder(BaseTable table) {
            this.table = table;
        }

        /** Append a row using boxed values. Length must match the schema. */
        public Builder appendRow(Object... values) {
            table.appendRow(values);
            return this;
        }

        /** Open a typed row appender for primitive-fast-path setting. */
        public RowAppender row() {
            return table.row();
        }

        /**
         * Finalize and return a sealed, read-only table. Population appends bumped the internal row
         * counter temporarily; {@link BaseTable#version()} is normalized to zero so snapshots match static
         * reference semantics ({@link #read()} slices report version zero forever).
         */
        public BaseTable build() {
            table.sealFromBuilderFreezeVersion();
            return table;
        }
    }
}
