package io.columnar.core.store;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;
import io.columnar.core.Validity;
import io.columnar.core.ValidityBuilder;
import io.columnar.core.chunk.DateArrayChunk;
import io.columnar.core.chunk.HotDateArrayChunk;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Append-only store for fixed-length {@link DataType#DATE_ARRAY} columns (epoch nanos per element). */
public final class DateArrayColumnStore extends ColumnStore {

    private final int elementsPerRow;
    private final ObjectArrayList<DateArrayChunk> sealed = new ObjectArrayList<>();
    private long[] activeValues;
    private int activeRows;
    private ValidityBuilder activeValidity;

    public DateArrayColumnStore(String name, int elementsPerRow, int chunkCapacity) {
        super(name, DataType.DATE_ARRAY, chunkCapacity);
        if (elementsPerRow <= 0) {
            throw new IllegalArgumentException("elementsPerRow: " + elementsPerRow);
        }
        this.elementsPerRow = elementsPerRow;
        this.activeValues = new long[chunkCapacity * elementsPerRow];
        this.activeRows = 0;
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public DateArrayColumnStore(String name, int elementsPerRow) {
        this(name, elementsPerRow, ColumnChunk.DEFAULT_CAPACITY);
    }

    public int elementsPerRow() {
        return elementsPerRow;
    }

    public void appendEpochNanoArray(long[] row) {
        if (row == null) {
            appendNull();
            return;
        }
        if (row.length != elementsPerRow) {
            throw new IllegalArgumentException(
                    "row length " + row.length + " != elementsPerRow " + elementsPerRow
                            + " for column " + name);
        }
        if (activeRows >= chunkCapacity) {
            sealActive();
        }
        int off = activeRows * elementsPerRow;
        System.arraycopy(row, 0, activeValues, off, elementsPerRow);
        activeRows++;
        activeValidity.appendValid();
    }

    public void appendInstantArray(Instant[] row) {
        if (row == null) {
            appendNull();
            return;
        }
        long[] nanos = new long[row.length];
        for (int i = 0; i < row.length; i++) {
            nanos[i] = row[i].getEpochSecond() * 1_000_000_000L + row[i].getNano();
        }
        appendEpochNanoArray(nanos);
    }

    @Override
    public void appendNull() {
        if (activeRows >= chunkCapacity) {
            sealActive();
        }
        int off = activeRows * elementsPerRow;
        Arrays.fill(activeValues, off, off + elementsPerRow, 0L);
        activeRows++;
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) {
            appendNull();
        } else if (value instanceof long[] arr) {
            appendEpochNanoArray(arr);
        } else if (value instanceof Instant[] arr) {
            appendInstantArray(arr);
        } else {
            throw new IllegalArgumentException(
                    "cannot append " + value.getClass().getName() + " to DATE_ARRAY column " + name);
        }
    }

    @Override
    public void sealActive() {
        if (activeRows == 0) {
            return;
        }
        long[] packed = Arrays.copyOf(activeValues, activeRows * elementsPerRow);
        sealed.add(new HotDateArrayChunk(packed, activeRows, elementsPerRow, activeValidity.toValidity()));
        activeValues = new long[chunkCapacity * elementsPerRow];
        activeRows = 0;
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (DateArrayChunk c : sealed) total += c.size();
        return total + activeRows;
    }

    @Override
    public int sealedChunkCount() { return sealed.size(); }

    @Override
    public int activeSize() { return activeRows; }

    @Override
    public List<DateArrayChunk> sealedChunks() { return sealed; }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (activeRows > 0) {
            long[] copy = Arrays.copyOf(activeValues, activeRows * elementsPerRow);
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotDateArrayChunk(copy, activeRows, elementsPerRow, vSnap));
        }
        return Column.of(name, DataType.DATE_ARRAY, all);
    }
}
