package io.columnar.api.store;

import io.columnar.api.Column;
import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;
import io.columnar.api.Validity;
import io.columnar.api.ValidityBuilder;
import io.columnar.api.chunk.DoubleArrayChunk;
import io.columnar.api.chunk.HotDoubleArrayChunk;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Arrays;
import java.util.List;

/** Append-only store for fixed-length {@link DataType#DOUBLE_ARRAY} columns. */
public final class DoubleArrayColumnStore extends ColumnStore {

    private final int elementsPerRow;
    private final ObjectArrayList<DoubleArrayChunk> sealed = new ObjectArrayList<>();
    private double[] activeValues;
    private int activeRows;
    private ValidityBuilder activeValidity;

    public DoubleArrayColumnStore(String name, int elementsPerRow, int chunkCapacity) {
        super(name, DataType.DOUBLE_ARRAY, chunkCapacity);
        if (elementsPerRow <= 0) {
            throw new IllegalArgumentException("elementsPerRow: " + elementsPerRow);
        }
        this.elementsPerRow = elementsPerRow;
        this.activeValues = new double[chunkCapacity * elementsPerRow];
        this.activeRows = 0;
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public DoubleArrayColumnStore(String name, int elementsPerRow) {
        this(name, elementsPerRow, ColumnChunk.DEFAULT_CAPACITY);
    }

    public int elementsPerRow() {
        return elementsPerRow;
    }

    public void appendDoubleArray(double[] row) {
        if (row == null) {
            appendNull();
            return;
        }
        if (row.length != elementsPerRow) {
            throw new IllegalArgumentException(
                    "row length "
                            + row.length
                            + " != elementsPerRow "
                            + elementsPerRow
                            + " for column "
                            + name);
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
        Arrays.fill(activeValues, off, off + elementsPerRow, 0.0);
        activeRows++;
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) {
            appendNull();
        } else if (value instanceof double[] arr) {
            appendDoubleArray(arr);
        } else {
            throw new IllegalArgumentException(
                    "cannot append "
                            + value.getClass().getName()
                            + " to DOUBLE_ARRAY column "
                            + name);
        }
    }

    @Override
    public void sealActive() {
        if (activeRows == 0) {
            return;
        }
        double[] packed = Arrays.copyOf(activeValues, activeRows * elementsPerRow);
        sealed.add(
                new HotDoubleArrayChunk(
                        packed, activeRows, elementsPerRow, activeValidity.toValidity()));
        activeValues = new double[chunkCapacity * elementsPerRow];
        activeRows = 0;
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (DoubleArrayChunk c : sealed) {
            total += c.size();
        }
        return total + activeRows;
    }

    @Override
    public int sealedChunkCount() {
        return sealed.size();
    }

    @Override
    public int activeSize() {
        return activeRows;
    }

    @Override
    public List<DoubleArrayChunk> sealedChunks() {
        return sealed;
    }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (activeRows > 0) {
            double[] copy = Arrays.copyOf(activeValues, activeRows * elementsPerRow);
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotDoubleArrayChunk(copy, activeRows, elementsPerRow, vSnap));
        }
        return Column.of(name, DataType.DOUBLE_ARRAY, all);
    }
}
