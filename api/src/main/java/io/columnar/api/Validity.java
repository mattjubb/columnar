package io.columnar.api;

import java.util.Arrays;

/**
 * Arrow-style null bitmap. One bit per row; <b>set bit means valid (non-null)</b>,
 * matching the Apache Arrow convention. Backed by a {@code long[]} of 64-bit words.
 *
 * <p>Validity bitmaps are intentionally simple and mutable while a chunk is being
 * populated, then frozen alongside their owning chunk.
 */
public final class Validity {

    private final long[] words;
    private final int size;
    private int nullCount;

    private Validity(long[] words, int size, int nullCount) {
        this.words = words;
        this.size = size;
        this.nullCount = nullCount;
    }

    /** All-valid bitmap of the given size. */
    public static Validity allValid(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative: " + size);
        }
        int wordCount = (size + 63) >>> 6;
        long[] words = new long[wordCount];
        Arrays.fill(words, -1L); // all bits set
        // Clear any trailing bits past `size`.
        int tail = size & 63;
        if (tail != 0 && wordCount > 0) {
            words[wordCount - 1] = (1L << tail) - 1L;
        }
        return new Validity(words, size, 0);
    }

    /** Bitmap with all rows initially marked null. */
    public static Validity allNull(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative: " + size);
        }
        int wordCount = (size + 63) >>> 6;
        return new Validity(new long[wordCount], size, size);
    }

    /**
     * Wrap a pre-built bitmap. The caller transfers ownership of {@code words}.
     * The null count is computed from the bitmap (bits clear within {@code [0,size)}).
     */
    public static Validity fromWords(long[] words, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative: " + size);
        }
        int needed = (size + 63) >>> 6;
        if (words.length < needed) {
            throw new IllegalArgumentException(
                    "words.length=" + words.length + " < needed=" + needed);
        }
        int set = 0;
        int fullWords = size >>> 6;
        for (int i = 0; i < fullWords; i++) {
            set += Long.bitCount(words[i]);
        }
        int tail = size & 63;
        if (tail != 0) {
            long mask = (1L << tail) - 1L;
            set += Long.bitCount(words[fullWords] & mask);
        }
        int nulls = size - set;
        return new Validity(words, size, nulls);
    }

    public int size() {
        return size;
    }

    public int nullCount() {
        return nullCount;
    }

    public boolean hasNulls() {
        return nullCount > 0;
    }

    public boolean isValid(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        return (words[row >>> 6] & (1L << (row & 63))) != 0;
    }

    public boolean isNull(int row) {
        return !isValid(row);
    }

    /**
     * Mark a row as valid (non-null). Idempotent.
     *
     * @return {@code true} if the bit changed (was null, now valid).
     */
    public boolean setValid(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        int wordIdx = row >>> 6;
        long mask = 1L << (row & 63);
        long word = words[wordIdx];
        if ((word & mask) != 0) {
            return false;
        }
        words[wordIdx] = word | mask;
        nullCount--;
        return true;
    }

    /**
     * Mark a row as null. Idempotent.
     *
     * @return {@code true} if the bit changed (was valid, now null).
     */
    public boolean setNull(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        int wordIdx = row >>> 6;
        long mask = 1L << (row & 63);
        long word = words[wordIdx];
        if ((word & mask) == 0) {
            return false;
        }
        words[wordIdx] = word & ~mask;
        nullCount++;
        return true;
    }

    /** Direct access to the underlying word array. Caller must not mutate length. */
    public long[] words() {
        return words;
    }
}
