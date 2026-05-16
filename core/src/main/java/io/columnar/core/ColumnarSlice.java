package io.columnar.core;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

/**
 * Materialized result of a {@code Table.read(viewport)} call. Carries the
 * (possibly projected) schema plus one {@link Column} per field.
 *
 * <p>All columns must share the same per-chunk row counts — chunks are aligned
 * across columns by row position.
 *
 * <p>{@link #version} is the upstream source version vector hash at the time
 * of materialization; consumers use it to detect staleness.
 */
public final class ColumnarSlice {

    private final Schema schema;
    private final List<Column> columns;
    private final long rowCount;
    private final long version;

    public ColumnarSlice(Schema schema, List<Column> columns, long rowCount, long version) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(columns, "columns");
        if (columns.size() != schema.size()) {
            throw new IllegalArgumentException(
                    "columns.size=" + columns.size() + " != schema.size=" + schema.size());
        }
        validateAlignment(schema, columns);
        this.schema = schema;
        this.columns = List.copyOf(columns);
        this.rowCount = rowCount;
        this.version = version;
    }

    private static void validateAlignment(Schema schema, List<Column> columns) {
        for (int c = 0; c < columns.size(); c++) {
            Column col = columns.get(c);
            Schema.Field f = schema.field(c);
            if (!col.name().equals(f.name())) {
                throw new IllegalArgumentException(
                        "column " + c + " name " + col.name() + " != schema name " + f.name());
            }
            if (col.type() != f.type()) {
                throw new IllegalArgumentException(
                        "column " + col.name() + " type " + col.type() + " != schema type " + f.type());
            }
        }
        if (columns.isEmpty()) return;
        int chunkCount = columns.get(0).chunkCount();
        for (int c = 1; c < columns.size(); c++) {
            if (columns.get(c).chunkCount() != chunkCount) {
                throw new IllegalArgumentException(
                        "column " + columns.get(c).name() + " has " + columns.get(c).chunkCount()
                                + " chunks; expected " + chunkCount);
            }
        }
        for (int i = 0; i < chunkCount; i++) {
            int s = columns.get(0).chunk(i).size();
            for (int c = 1; c < columns.size(); c++) {
                if (columns.get(c).chunk(i).size() != s) {
                    throw new IllegalArgumentException(
                            "chunk " + i + " column " + columns.get(c).name()
                                    + " size " + columns.get(c).chunk(i).size()
                                    + " != column " + columns.get(0).name() + " size " + s);
                }
            }
        }
    }

    public static ColumnarSlice empty(Schema schema, long version) {
        java.util.List<Column> empties = new java.util.ArrayList<>(schema.size());
        for (int i = 0; i < schema.size(); i++) {
            Schema.Field f = schema.field(i);
            empties.add(Column.of(f.name(), f.type(), List.of()));
        }
        return new ColumnarSlice(schema, empties, 0L, version);
    }

    public Schema schema() {
        return schema;
    }

    public int columnCount() {
        return columns.size();
    }

    public Column column(int idx) {
        return columns.get(idx);
    }

    public Column column(String name) {
        return columns.get(schema.indexOf(name));
    }

    public List<Column> columns() {
        return columns;
    }

    public int chunkCount() {
        return columns.isEmpty() ? 0 : columns.get(0).chunkCount();
    }

    public long rowCount() {
        return rowCount;
    }

    public long version() {
        return version;
    }

    /** Format this slice as a human-readable text table using sensible defaults. */
    public String toPrettyString() {
        return Pretty.format(this);
    }

    public String toPrettyString(int maxRows, int maxCols, int maxCellWidth) {
        return Pretty.format(this, maxRows, maxCols, maxCellWidth);
    }

    /** Print {@link #toPrettyString()} to {@link System#out}. */
    public void prettyPrint() {
        Pretty.print(this);
    }

    public void prettyPrint(int maxRows) {
        System.out.print(toPrettyString(maxRows, Pretty.DEFAULT_MAX_COLS, Pretty.DEFAULT_MAX_CELL_WIDTH));
    }

    public void prettyPrint(PrintStream out) {
        Pretty.print(this, out);
    }

    @Override
    public String toString() {
        return toPrettyString();
    }
}
