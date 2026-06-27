package io.columnar.core.store;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;
import io.columnar.core.Validity;
import io.columnar.core.ValidityBuilder;
import io.columnar.core.chunk.HotIntArrayChunk;
import io.columnar.core.chunk.IntArrayChunk;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Arrays;
import java.util.List;

/** Append-only store for fixed-length {@link DataType#INT_ARRAY} columns. */
public final class IntArrayColumnStore extends ColumnStore {

    private final int elementsPerRow;
    private final ObjectArrayList<IntArrayChunk> sealed = new ObjectArrayList<>();
    private int[] activeValues;
    private int activeRows;
    private ValidityBuilder activeValidity;

    public IntArrayColumnStore(String name, int elementsPerRow, int chunkCapacity) {
        super(name, DataType.INT_ARRAY, chunkCapacity);
        if (elementsPerRow <= 0) {
            throw new IllegalArgumentException("elementsPerRow: " + elementsPerRow);
        }
        this.elementsPerRow = elementsPerRow;
        this.activeValues = new int[chunkCapacity * elementsPerRow];
        this.activeRows = 0;
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public IntArrayColumnStore(String name, int elementsPerRow) {
        this(name, elementsPerRow, ColumnChunk.DEFAULT_CAPACITY);
    }

    public int elementsPerRow() {
        return elementsPerRow;
    }

    public void appendIntArray(int[] row) {
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

    @Override
    public void appendNull() {
        if (activeRows >= chunkCapacity) {
            sealActive();
        }
        int off = activeRows * elementsPerRow;
        Arrays.fill(activeValues, off, off + elementsPerRow, 0);
        activeRows++;
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) {
            appendNull();
        } else if (value instanceof int[] arr) {
            appendIntArray(arr);
        } else {
            throw new IllegalArgumentException(
                    "cannot append " + value.getClass().getName() + " to INT_ARRAY column " + name);
        }
    }

    @Override
    public void sealActive() {
        if (activeRows == 0) {
            return;
        }
        int[] packed = Arrays.copyOf(activeValues, activeRows * elementsPerRow);
        sealed.add(new HotIntArrayChunk(packed, activeRows, elementsPerRow, activeValidity.toValidity()));
        activeValues = new int[chunkCapacity * elementsPerRow];
        activeRows = 0;
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (IntArrayChunk c : sealed) total += c.size();
        return total + activeRows;
    }

    @Override
    public int sealedChunkCount() { return sealed.size(); }

    @Override
    public int activeSize() { return activeRows; }

    @Override
    public List<IntArrayChunk> sealedChunks() { return sealed; }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (activeRows > 0) {
            int[] copy = Arrays.copyOf(activeValues, activeRows * elementsPerRow);
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotIntArrayChunk(copy, activeRows, elementsPerRow, vSnap));
        }
        return Column.of(name, DataType.INT_ARRAY, all);
    }
}
