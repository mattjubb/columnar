package io.columnar.api;

/**
 * Pluggable browser / rich UI for {@link Table} data. Modules like {@code :viz}
 * register an implementation via {@link java.util.ServiceLoader} so callers can
 * invoke {@link Table#show()} without a hard compile-time dependency from
 * {@code :core} onto the visualizer.
 */
@FunctionalInterface
public interface TableVisualizer {

    /**
     * Opens the default browser (or IDE-embedded browser) and renders the table
     * for the given viewport, subscribing to changes for live updates until the
     * user stops the server from the console.
     *
     * @param table    table to materialize and stream
     * @param viewport subset of rows/columns to show
     */
    void show(Table table, Viewport viewport);
}
