package io.columnar.core;

import io.columnar.api.ColumnarSlice;
import io.columnar.api.Table;
import io.columnar.api.Viewport;

/**
 * Context passed to {@link Operator#compute}. Provides cached, version-aware
 * access to upstream tables. Implementations live in {@code :query}; the
 * default {@link #direct() direct context} simply delegates to
 * {@link Table#read(Viewport)} with no caching.
 */
public interface PullContext {

    /** Read an upstream slice. Implementations may consult a materialization cache. */
    ColumnarSlice read(Table upstream, Viewport viewport);

    /** No-cache implementation that just calls {@link Table#read(Viewport)}. */
    static PullContext direct() {
        return Table::read;
    }
}
