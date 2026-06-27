package io.columnar.core.chunk;

import io.columnar.core.Residency;
import io.columnar.core.Validity;

import java.util.Objects;

/** On-heap row-major {@code String[]} chunk ({@link StringArrayChunk}). */
public final class HotStringArrayChunk implements StringArrayChunk {

    private final String[] values;
    private final int size;
    private final int elementsPerRow;
    private final Validity validity;

    public HotStringArrayChunk(String[] values, int size, int elementsPerRow, Validity validity) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(validity, "validity");
        if (elementsPerRow <= 0) {
            throw new IllegalArgumentException("elementsPerRow: " + elementsPerRow);
        }
        if (size < 0) {
            throw new IllegalArgumentException("size: " + size);
        }
        if (values.length < (long) size * elementsPerRow) {
            throw new IllegalArgumentException(
                    "values.length="
                            + values.length
                            + " < size*elementsPerRow="
                            + ((long) size * elementsPerRow));
        }
        if (validity.size() != size) {
            throw new IllegalArgumentException(
                    "validity.size=" + validity.size() + " != size=" + size);
        }
        this.values = values;
        this.size = size;
        this.elementsPerRow = elementsPerRow;
        this.validity = validity;
    }

    public static HotStringArrayChunk of(String[] rowMajorValues, int elementsPerRow) {
        int rows = rowMajorValues.length / elementsPerRow;
        if (rows * elementsPerRow != rowMajorValues.length) {
            throw new IllegalArgumentException(
                    "rowMajorValues.length must be a multiple of elementsPerRow");
        }
        return new HotStringArrayChunk(
                rowMajorValues, rows, elementsPerRow, Validity.allValid(rows));
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public int elementsPerRow() {
        return elementsPerRow;
    }

    @Override
    public Residency residency() {
        return Residency.HOT;
    }

    @Override
    public Validity validity() {
        return validity;
    }

    @Override
    public String getString(int row, int element) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        if (element < 0 || element >= elementsPerRow) {
            throw new IndexOutOfBoundsException(
                    "element=" + element + " elementsPerRow=" + elementsPerRow);
        }
        return values[row * elementsPerRow + element];
    }

    @Override
    public String[] values() {
        return values;
    }
}
