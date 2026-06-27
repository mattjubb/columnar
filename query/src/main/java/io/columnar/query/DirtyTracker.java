package io.columnar.query;

import io.columnar.api.Table;

import java.util.Objects;

/**
 * Thin facade over {@link DependencyGraph} for marking downstream nodes dirty when
 * source tables mutate. Pair with {@link MaterializationCache#clear()} on refresh.
 */
public final class DirtyTracker {

    private final DependencyGraph graph;

    public DirtyTracker(DependencyGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    public DirtyTracker() {
        this(new DependencyGraph());
    }

    public DependencyGraph graph() {
        return graph;
    }

    public AutoCloseable watch(Table source, Runnable onDirty) {
        return graph.register(source, onDirty);
    }

    public void markDirty(Table source) {
        graph.fire(source);
    }
}
