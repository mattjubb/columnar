package io.columnar.api;

import io.columnar.api.store.BooleanColumnStore;
import io.columnar.api.store.ColumnStore;
import io.columnar.api.store.DateArrayColumnStore;
import io.columnar.api.store.DoubleArrayColumnStore;
import io.columnar.api.store.DoubleColumnStore;
import io.columnar.api.store.FloatColumnStore;
import io.columnar.api.store.InstantColumnStore;
import io.columnar.api.store.IntArrayColumnStore;
import io.columnar.api.store.IntColumnStore;
import io.columnar.api.store.LongColumnStore;
import io.columnar.api.store.StringArrayColumnStore;
import io.columnar.api.store.StringColumnStore;

import java.time.Instant;
import java.util.List;

/**
 * Cursor over a row currently being built. Bound to one column index at a time.
 *
 * <p>Used by both static-table builders and live-table appenders. Typed
 * setters ({@link #setLong}, {@link #setDouble}, ...) avoid boxing on the hot path.
 *
 * <p>Workflow:
 * <pre>{@code
 * RowAppender row = appender.row();
 * row.setLong(0, 42L);
 * row.setDouble(1, 3.14);
 * row.setString(2, "hello");
 * row.commit();
 * }</pre>
 */
public final class RowAppender {

    private final List<ColumnStore> stores;
    private final boolean[] columnSet;
    private final Runnable onCommit;

    RowAppender(List<ColumnStore> stores, Runnable onCommit) {
        this.stores = stores;
        this.columnSet = new boolean[stores.size()];
        this.onCommit = onCommit;
    }

    private void mark(int col) {
        if (columnSet[col]) {
            throw new IllegalStateException("column " + col + " already set on this row");
        }
        columnSet[col] = true;
    }

    public RowAppender setInt(int col, int value) {
        mark(col);
        ((IntColumnStore) stores.get(col)).appendInt(value);
        return this;
    }

    public RowAppender setLong(int col, long value) {
        mark(col);
        ((LongColumnStore) stores.get(col)).appendLong(value);
        return this;
    }

    public RowAppender setFloat(int col, float value) {
        mark(col);
        ((FloatColumnStore) stores.get(col)).appendFloat(value);
        return this;
    }

    public RowAppender setDouble(int col, double value) {
        mark(col);
        ((DoubleColumnStore) stores.get(col)).appendDouble(value);
        return this;
    }

    /** Append a fixed-length {@code double[]} row for a {@link DataType#DOUBLE_ARRAY} column. */
    public RowAppender setDoubleArray(int col, double[] values) {
        mark(col);
        ((DoubleArrayColumnStore) stores.get(col)).appendDoubleArray(values);
        return this;
    }

    /** Append a fixed-length {@code int[]} row for a {@link DataType#INT_ARRAY} column. */
    public RowAppender setIntArray(int col, int[] values) {
        mark(col);
        ((IntArrayColumnStore) stores.get(col)).appendIntArray(values);
        return this;
    }

    /** Append a fixed-length {@code String[]} row for a {@link DataType#STRING_ARRAY} column. */
    public RowAppender setStringArray(int col, String[] values) {
        mark(col);
        ((StringArrayColumnStore) stores.get(col)).appendStringArray(values);
        return this;
    }

    /** Append a fixed-length epoch-nano {@code long[]} row for a {@link DataType#DATE_ARRAY} column. */
    public RowAppender setDateArray(int col, long[] epochNanos) {
        mark(col);
        ((DateArrayColumnStore) stores.get(col)).appendEpochNanoArray(epochNanos);
        return this;
    }

    /** Append a fixed-length {@code Instant[]} row for a {@link DataType#DATE_ARRAY} column. */
    public RowAppender setDateArray(int col, Instant[] values) {
        mark(col);
        ((DateArrayColumnStore) stores.get(col)).appendInstantArray(values);
        return this;
    }

    public RowAppender setBoolean(int col, boolean value) {
        mark(col);
        ((BooleanColumnStore) stores.get(col)).appendBoolean(value);
        return this;
    }

    public RowAppender setString(int col, String value) {
        mark(col);
        ((StringColumnStore) stores.get(col)).appendString(value);
        return this;
    }

    public RowAppender setInstant(int col, Instant value) {
        mark(col);
        ((InstantColumnStore) stores.get(col)).appendInstant(value);
        return this;
    }

    public RowAppender setEpochNano(int col, long epochNano) {
        mark(col);
        ((InstantColumnStore) stores.get(col)).appendEpochNano(epochNano);
        return this;
    }

    public RowAppender setNull(int col) {
        mark(col);
        stores.get(col).appendNull();
        return this;
    }

    /** Generic boxing setter — for cases where the type is not known at compile time. */
    public RowAppender set(int col, Object value) {
        mark(col);
        stores.get(col).append(value);
        return this;
    }

    /** Commit the row. Any unset columns are filled with null. */
    public void commit() {
        for (int i = 0; i < columnSet.length; i++) {
            if (!columnSet[i]) {
                stores.get(i).appendNull();
            }
        }
        onCommit.run();
    }
}
