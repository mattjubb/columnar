package io.columnar.api;

import java.util.Arrays;

/**
 * Append-only counterpart to {@link Validity}. Used by source-side column
 * stores to grow a null bitmap one row at a time without quadratic copies.
 *
 * <p>Pre-allocates a {@code long[]} sized for the chunk capacity. Bits start
 * cleared (rows are appended explicitly via {@link #appendValid()} or
 * {@link #appendNull()}).
 */
public final class ValidityBuilder {

    private long[] words;
    private int size;
    private int nullCount;

    public ValidityBuilder(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must be non-negative: " + initialCapacity);
        }
        int wc = (initialCapacity + 63) >>> 6;
        this.words = new long[Math.max(wc, 1)];
    }

    public int size() {
        return size;
    }

    public int nullCount() {
        return nullCount;
    }

    public void appendValid() {
        ensureCapacity(size + 1);
        words[size >>> 6] |= 1L << (size & 63);
        size++;
    }

    public void appendNull() {
        ensureCapacity(size + 1);
        // bit already 0
        size++;
        nullCount++;
    }

    private void ensureCapacity(int requiredBits) {
        int requiredWords = (requiredBits + 63) >>> 6;
        if (requiredWords > words.length) {
            int newLen = Math.max(requiredWords, words.length * 2);
            words = Arrays.copyOf(words, newLen);
        }
    }

    /**
     * Snapshot the current state into an immutable {@link Validity} of length
     * {@link #size()}. The builder remains usable; subsequent appends do not
     * mutate the returned snapshot (the snapshot copies its words).
     */
    public Validity toValidity() {
        int wc = (size + 63) >>> 6;
        long[] copy = Arrays.copyOf(words, wc);
        // Clear any tail bits past size in the last word (defensive).
        int tail = size & 63;
        if (tail != 0 && wc > 0) {
            long mask = (1L << tail) - 1L;
            copy[wc - 1] &= mask;
        }
        return Validity.fromWords(copy, size);
    }

    /** Reset to empty (after sealing into a chunk). */
    public void reset(int newCapacity) {
        int wc = (newCapacity + 63) >>> 6;
        if (wc > words.length) {
            words = new long[wc];
        } else {
            Arrays.fill(words, 0L);
        }
        size = 0;
        nullCount = 0;
    }
}
