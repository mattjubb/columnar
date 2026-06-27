package io.columnar.api.chunk;

import io.columnar.api.Residency;
import io.columnar.api.Validity;

import java.util.Objects;

public final class HotDoubleChunk implements DoubleChunk {

    private final double[] values;
    private final int size;
    private final Validity validity;

    public HotDoubleChunk(double[] values, int size, Validity validity) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(validity, "validity");
        if (size < 0 || size > values.length) {
            throw new IllegalArgumentException("size=" + size + " values.length=" + values.length);
        }
        if (validity.size() != size) {
            throw new IllegalArgumentException(
                    "validity.size=" + validity.size() + " != size=" + size);
        }
        this.values = values;
        this.size = size;
        this.validity = validity;
    }

    public static HotDoubleChunk of(double[] values) {
        return new HotDoubleChunk(values, values.length, Validity.allValid(values.length));
    }

    @Override
    public int size() {
        return size;
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
    public double getDouble(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        return values[row];
    }

    public double[] values() {
        return values;
    }
}
