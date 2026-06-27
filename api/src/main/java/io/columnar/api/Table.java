package io.columnar.api;

import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * The framework's central abstraction. A {@code Table} has a {@link Schema}, a
 * monotonic {@link #version()} that bumps on every mutation, and supports lazy
 * viewport-driven reads via {@link #read(Viewport)}.
 *
 * <p>Two concrete kinds back this interface:
 * <ul>
 *   <li>{@link BaseTable} — the unified source table. Starts mutable; calling
 *       {@link BaseTable#seal()} freezes it permanently. {@link #builder(Schema)}
 *       returns one in sealed form, {@link #create(Schema)} returns one open
 *       for live appends.</li>
 *   <li>{@code DerivedTable} (in {@code :engine}) — operator-backed; virtual
 *       until pulled.</li>
 * </ul>
 *
 * <p>{@link Table} is intentionally <em>not</em> sealed so concrete kinds can live
 * in different modules (e.g. derived tables in {@code :engine}, sources in {@code :core}).
 *
 */
public interface Table {

    /** Create a fresh open (unsealed) source table. */
    static BaseTable create(Schema schema) {
        return BaseTable.create(schema);
    }

    /** Start a builder that {@code build()}s into a sealed source table. */
    static BaseTable.Builder builder(Schema schema) {
        return BaseTable.builder(schema);
    }

    Schema schema();

    /**
     * Monotonic version. For static tables this is always 0. For live tables
     * it bumps on every mutation. For derived tables it is a deterministic
     * hash of the upstream version vector plus operator config.
     */
    long version();

    /** Total row count of the materialized table at its current version. */
    long size();

    /**
     * Materialize the requested viewport. Triggers lazy recompute if dirty.
     */
    ColumnarSlice read(Viewport viewport);

    /** Convenience: read everything. Equivalent to {@code read(Viewport.ALL)}. */
    default ColumnarSlice read() {
        return read(Viewport.ALL);
    }

    /**
     * Subscribe to changes for the given viewport. The callback fires when the
     * viewport's data may have changed (subject to the chosen subscribe mode).
     *
     * <p>Default implementation throws; concrete implementations override.
     */
    default Subscription subscribe(Viewport viewport, Consumer<ColumnarSlice> onChange) {
        throw new UnsupportedOperationException(
                "subscribe is not supported by " + getClass().getSimpleName());
    }

    /**
     * Render the head of this table (up to {@link Pretty#DEFAULT_MAX_ROWS}
     * rows) as a human-readable text table.
     */
    default String toPrettyString() {
        return toPrettyString(Pretty.DEFAULT_MAX_ROWS);
    }

    default String toPrettyString(int maxRows) {
        Viewport vp = maxRows >= size() ? Viewport.ALL : Viewport.builder().limit(maxRows).build();
        return read(vp).toPrettyString(maxRows, Pretty.DEFAULT_MAX_COLS, Pretty.DEFAULT_MAX_CELL_WIDTH);
    }

    /** Print {@link #toPrettyString()} to {@link System#out}. */
    default void prettyPrint() {
        System.out.print(toPrettyString());
    }

    default void prettyPrint(int maxRows) {
        System.out.print(toPrettyString(maxRows));
    }

    /**
     * Open a browser-based viewer for this table (viewport = all rows/columns,
     * subject to the visualizer's own row cap for performance).
     *
     * @throws UnsupportedOperationException if no {@link TableVisualizer} is on
     *                                        the module path / classpath (add
     *                                        the {@code :viz} module).
     */
    default void show() {
        show(Viewport.ALL);
    }

    /**
     * Like {@link #show()} but honours the given {@link Viewport} for both the
     * initial render and ongoing subscription updates.
     */
    default void show(Viewport viewport) {
        for (TableVisualizer v : ServiceLoader.load(TableVisualizer.class)) {
            v.show(this, viewport);
            return;
        }
        throw new UnsupportedOperationException(
                "No TableVisualizer registered (ServiceLoader). "
                        + "Add the :viz module / io.columnar.viz JAR to the runtime.");
    }
}
