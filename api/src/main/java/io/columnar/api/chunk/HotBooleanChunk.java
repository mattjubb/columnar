package io.columnar.api.chunk;

import io.columnar.api.Residency;
import io.columnar.api.Validity;

import java.util.Objects;

/** Bit-packed boolean chunk: one {@code long} per 64 rows. */
public final class HotBooleanChunk implements BooleanChunk {

    private final long[] words;
    private final int size;
    private final Validity validity;

    public HotBooleanChunk(long[] words, int size, Validity validity) {
        Objects.requireNonNull(words, "words");
        Objects.requireNonNull(validity, "validity");
        if (size < 0) {
            throw new IllegalArgumentException("size=" + size);
        }
        int needed = (size + 63) >>> 6;
        if (words.length < needed) {
            throw new IllegalArgumentException(
                    "words.length=" + words.length + " < needed=" + needed);
        }
        if (validity.size() != size) {
            throw new IllegalArgumentException(
                    "validity.size=" + validity.size() + " != size=" + size);
        }
        this.words = words;
        this.size = size;
        this.validity = validity;
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
    public boolean getBoolean(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        return (words[row >>> 6] & (1L << (row & 63))) != 0;
    }

    public long[] words() {
        return words;
    }
}
