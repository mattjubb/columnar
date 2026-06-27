package io.columnar.query;

import io.columnar.api.Table;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reverse edges from a source {@link Table} to downstream consumers that should be
 * notified when the source version advances. This is a lightweight adjacency list;
 * it does not walk operator DAGs itself — callers register explicit listeners.
 */
public final class DependencyGraph {

    private final ConcurrentHashMap<IdentityKey, CopyOnWriteArrayList<Runnable>> edges = new ConcurrentHashMap<>();

    /**
     * Register {@code onDirty} to run whenever {@code source} is marked dirty.
     * Returns a handle that removes the registration when closed.
     */
    public AutoCloseable register(Table source, Runnable onDirty) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(onDirty, "onDirty");
        IdentityKey key = new IdentityKey(source);
        edges.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        CopyOnWriteArrayList<Runnable> list = edges.get(key);
        synchronized (list) {
            if (!list.contains(onDirty)) {
                list.add(onDirty);
            }
        }
        return () -> {
            CopyOnWriteArrayList<Runnable> edgeList = edges.get(key);
            if (edgeList != null) {
                edgeList.remove(onDirty);
            }
        };
    }

    /** Invoke every listener registered for {@code source}. */
    public void fire(Table source) {
        IdentityKey key = new IdentityKey(source);
        List<Runnable> list = edges.get(key);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Runnable r : list) {
            r.run();
        }
    }

    private static final class IdentityKey {
        private final Table table;

        IdentityKey(Table table) {
            this.table = table;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof IdentityKey ik && table == ik.table;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(table);
        }
    }
}
