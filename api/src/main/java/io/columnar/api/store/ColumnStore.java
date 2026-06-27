package io.columnar.api.store;

import io.columnar.api.Column;
import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;

import java.util.List;

/**
 * Append-only backing for a single source-table column. Sealed chunks are
 * immutable and shared; the currently-appending "active" chunk is sealed
 * either when full or when a snapshot is requested.
 *
 * <p>Per-type subclasses ({@link LongColumnStore}, etc.) expose typed
 * fast-path append methods that avoid boxing.
 *
 * <p>Not thread-safe. The owning {@code BaseTable} serializes mutations.
 */
public abstract class ColumnStore {

    protected final String name;
    protected final DataType type;
    protected final int chunkCapacity;

    protected ColumnStore(String name, DataType type, int chunkCapacity) {
        this.name = name;
        this.type = type;
        if (chunkCapacity <= 0) {
            throw new IllegalArgumentException("chunkCapacity must be positive: " + chunkCapacity);
        }
        this.chunkCapacity = chunkCapacity;
    }

    public final String name() {
        return name;
    }

    public final DataType type() {
        return type;
    }

    public final int chunkCapacity() {
        return chunkCapacity;
    }

    /** Total rows across sealed chunks plus the active chunk. */
    public abstract long size();

    public abstract int sealedChunkCount();

    public abstract int activeSize();

    /** Append a null. Increments size; nulls active chunk's validity bit. */
    public abstract void appendNull();

    /**
     * Append a value, boxing as needed. Per-type subclasses expose primitive
     * fast paths (e.g. {@link LongColumnStore#appendLong(long)}).
     */
    public abstract void append(Object value);

    /** Force the active chunk (if non-empty) to seal. */
    public abstract void sealActive();

    /**
     * Materialize the current state as an immutable {@link Column}. The active
     * chunk (if any) is captured by snapshotting its current prefix; subsequent
     * appends do not mutate the returned snapshot.
     */
    public abstract Column snapshot();

    /** Get just the sealed chunks (cheap; no copy). */
    public abstract List<? extends ColumnChunk> sealedChunks();
}
