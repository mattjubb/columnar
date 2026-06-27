package io.columnar.api.chunk;

import io.columnar.api.Residency;
import io.columnar.api.Validity;

import java.util.Objects;

/**
 * On-heap dictionary-encoded string chunk. Stores int codes; resolves through
 * a shared {@link StringDictionary} owned by the parent column.
 */
public final class HotStringChunk implements StringChunk {

    private final int[] codes;
    private final int size;
    private final Validity validity;
    private final StringDictionary dictionary;

    public HotStringChunk(int[] codes, int size, Validity validity, StringDictionary dictionary) {
        Objects.requireNonNull(codes, "codes");
        Objects.requireNonNull(validity, "validity");
        Objects.requireNonNull(dictionary, "dictionary");
        if (size < 0 || size > codes.length) {
            throw new IllegalArgumentException("size=" + size + " codes.length=" + codes.length);
        }
        if (validity.size() != size) {
            throw new IllegalArgumentException(
                    "validity.size=" + validity.size() + " != size=" + size);
        }
        this.codes = codes;
        this.size = size;
        this.validity = validity;
        this.dictionary = dictionary;
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
    public int getCode(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        return codes[row];
    }

    @Override
    public String getString(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        if (!validity.isValid(row)) {
            return null;
        }
        return dictionary.resolve(codes[row]);
    }

    public int[] codes() {
        return codes;
    }

    public StringDictionary dictionary() {
        return dictionary;
    }
}
