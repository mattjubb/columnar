package io.columnar.core;

import java.util.List;

/**
 * Logical column = name + type + ordered list of {@link ColumnChunk}s.
 * Read-only view; appendable column stores live in {@code io.columnar.core.store}
 * and produce {@link Column} snapshots via {@code snapshot()}.
 */
public interface Column {

    String name();

    DataType type();

    /** Total row count across all chunks. */
    long size();

    int chunkCount();

    ColumnChunk chunk(int idx);

    List<ColumnChunk> chunks();

    /** Render this column as a single-column text table. */
    default String toPrettyString() {
        return Pretty.formatColumn(this);
    }

    /** Print this column as a single-column text table to {@link System#out}. */
    default void prettyPrint() {
        Pretty.print(this);
    }

    /** Build a basic immutable {@link Column} from a name + chunks. */
    static Column of(String name, DataType type, List<? extends ColumnChunk> chunks) {
        return new BasicColumn(name, type, List.copyOf(chunks));
    }

    final class BasicColumn implements Column {
        private final String name;
        private final DataType type;
        private final List<ColumnChunk> chunks;
        private final long size;

        BasicColumn(String name, DataType type, List<ColumnChunk> chunks) {
            this.name = name;
            this.type = type;
            this.chunks = chunks;
            long s = 0;
            for (ColumnChunk c : chunks) {
                if (c.type() != type) {
                    throw new IllegalArgumentException(
                            "chunk type " + c.type() + " != column type " + type
                                    + " for column " + name);
                }
                s += c.size();
            }
            this.size = s;
        }

        @Override public String name() { return name; }
        @Override public DataType type() { return type; }
        @Override public long size() { return size; }
        @Override public int chunkCount() { return chunks.size(); }
        @Override public ColumnChunk chunk(int idx) { return chunks.get(idx); }
        @Override public List<ColumnChunk> chunks() { return chunks; }
    }
}
