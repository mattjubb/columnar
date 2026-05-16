package io.columnar.query;

import io.columnar.core.ColumnarSlice;
import io.columnar.core.Table;
import io.columnar.core.Viewport;
import io.columnar.engine.PullContext;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PullContext} that memoizes {@link Table#read(Viewport)} per (table identity,
 * viewport) until the table's {@link Table#version()} changes.
 */
public final class MaterializationCache implements PullContext {

    private static final class Key {
        private final Table table;
        private final Viewport viewport;

        Key(Table table, Viewport viewport) {
            this.table = table;
            this.viewport = viewport;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.table == table && viewport.equals(k.viewport);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(table) * 31 + viewport.hashCode();
        }
    }

    private static final class Entry {
        final long sourceVersionAtMaterialization;
        final ColumnarSlice slice;

        Entry(long sourceVersionAtMaterialization, ColumnarSlice slice) {
            this.sourceVersionAtMaterialization = sourceVersionAtMaterialization;
            this.slice = slice;
        }
    }

    private final ConcurrentHashMap<Key, Entry> map = new ConcurrentHashMap<>();

    @Override
    public ColumnarSlice read(Table upstream, Viewport viewport) {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(viewport, "viewport");
        Key k = new Key(upstream, viewport);
        Entry hit = map.get(k);
        long verNow = upstream.version();
        if (hit != null && hit.sourceVersionAtMaterialization == verNow) {
            return hit.slice;
        }
        ColumnarSlice computed = upstream.read(viewport);
        Entry fresh = new Entry(verNow, computed);
        map.put(k, fresh);
        return computed;
    }

    public void invalidate(Table table) {
        map.keySet().removeIf(key -> key.table == table);
    }

    /** Drop all memoized upstream reads (e.g., after coarse-grained dirty notification). */
    public void clear() {
        map.clear();
    }

    /** Approximate tracked cache entries — useful for tests and metrics. */
    public int approximateSize() {
        return map.size();
    }
}
