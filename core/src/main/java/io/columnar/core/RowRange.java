package io.columnar.core;

/**
 * Half-open row range {@code [from, to)} into a logical table.
 * {@link #ALL} is the conventional "everything" sentinel.
 */
public record RowRange(long from, long to) {

    public static final RowRange ALL = new RowRange(0L, Long.MAX_VALUE);

    public RowRange {
        if (from < 0) {
            throw new IllegalArgumentException("from must be non-negative: " + from);
        }
        if (to < from) {
            throw new IllegalArgumentException("to (" + to + ") must be >= from (" + from + ")");
        }
    }

    public static RowRange of(long from, long to) {
        return new RowRange(from, to);
    }

    public static RowRange head(long n) {
        return new RowRange(0L, n);
    }

    public boolean isAll() {
        return from == 0L && to == Long.MAX_VALUE;
    }

    /** Number of rows, capped at {@link Long#MAX_VALUE}. */
    public long size() {
        return to - from;
    }

    /** Intersect with another range. Returns an empty range if disjoint. */
    public RowRange intersect(RowRange other) {
        long lo = Math.max(this.from, other.from);
        long hi = Math.min(this.to, other.to);
        if (hi < lo) hi = lo;
        return new RowRange(lo, hi);
    }
}
