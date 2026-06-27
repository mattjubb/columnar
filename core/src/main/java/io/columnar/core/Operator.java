package io.columnar.core;

import io.columnar.api.ColumnarSlice;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.Viewport;

import java.util.List;

/**
 * Logical and physical plan node. Operators are pull-based: {@link #compute}
 * recursively reads from upstreams via the supplied {@link PullContext}.
 *
 * <p>Operators do not own state directly — caching, dirty tracking, and
 * dependency walking are delegated to the runtime ({@code :query} module),
 * which inspects {@link #upstreams()} and {@link #signature()} for cache keying.
 */
public interface Operator {

    Schema outputSchema();

    /** Direct upstream tables this operator reads from. May be empty for source operators. */
    List<Table> upstreams();

    /**
     * Compact, deterministic signature describing this operator's behavior
     * (operator name + parameters). Used as part of cache keys.
     */
    String signature();

    /**
     * Materialize the requested viewport. Implementations call
     * {@link PullContext#read(Table, Viewport)} to obtain upstream slices.
     */
    ColumnarSlice compute(Viewport viewport, PullContext ctx);
}
