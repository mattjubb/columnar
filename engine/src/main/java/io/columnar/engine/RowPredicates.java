package io.columnar.engine;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.HotDoubleChunk;
import io.columnar.core.chunk.HotIntChunk;
import io.columnar.core.chunk.HotLongChunk;
import io.columnar.core.chunk.IntChunk;
import io.columnar.core.chunk.LongChunk;
import io.columnar.core.chunk.StringChunk;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hand-written vectorized predicates. These illustrate the codegen target —
 * tight per-chunk loops over primitive arrays with fast-paths for HOT chunks.
 *
 * <p>The runtime ByteBuddy backend (in {@code :expr}) will eventually generate
 * equivalent classes per expression / column-type signature.
 */
public final class RowPredicates {

    private RowPredicates() {}

    /** {@code col > threshold} for LONG columns. Nulls fail the predicate. */
    public static RowPredicate longGt(String col, long threshold) {
        return new SingleColumnPredicate(col) {
            @Override
            public void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits) {
                Arrays.fill(outBits, 0L);
                ColumnChunk chunk = findChunk(cols, col, chunkIdx);
                long[] valid = chunk.validity().words();
                if (chunk instanceof HotLongChunk hot) {
                    long[] vals = hot.values();
                    for (int i = 0; i < rowCount; i++) {
                        if ((valid[i >>> 6] & (1L << (i & 63))) != 0 && vals[i] > threshold) {
                            outBits[i >>> 6] |= 1L << (i & 63);
                        }
                    }
                } else {
                    LongChunk lc = (LongChunk) chunk;
                    for (int i = 0; i < rowCount; i++) {
                        if ((valid[i >>> 6] & (1L << (i & 63))) != 0 && lc.getLong(i) > threshold) {
                            outBits[i >>> 6] |= 1L << (i & 63);
                        }
                    }
                }
            }
        };
    }

    /** {@code col == value} for LONG columns. Nulls fail. */
    public static RowPredicate longEq(String col, long value) {
        return new SingleColumnPredicate(col) {
            @Override
            public void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits) {
                Arrays.fill(outBits, 0L);
                ColumnChunk chunk = findChunk(cols, col, chunkIdx);
                long[] valid = chunk.validity().words();
                if (chunk instanceof HotLongChunk hot) {
                    long[] vals = hot.values();
                    for (int i = 0; i < rowCount; i++) {
                        if ((valid[i >>> 6] & (1L << (i & 63))) != 0 && vals[i] == value) {
                            outBits[i >>> 6] |= 1L << (i & 63);
                        }
                    }
                } else {
                    LongChunk lc = (LongChunk) chunk;
                    for (int i = 0; i < rowCount; i++) {
                        if ((valid[i >>> 6] & (1L << (i & 63))) != 0 && lc.getLong(i) == value) {
                            outBits[i >>> 6] |= 1L << (i & 63);
                        }
                    }
                }
            }
        };
    }

    /** {@code col > threshold} for DOUBLE columns. Nulls fail. */
    public static RowPredicate doubleGt(String col, double threshold) {
        return new SingleColumnPredicate(col) {
            @Override
            public void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits) {
                Arrays.fill(outBits, 0L);
                ColumnChunk chunk = findChunk(cols, col, chunkIdx);
                long[] valid = chunk.validity().words();
                if (chunk instanceof HotDoubleChunk hot) {
                    double[] vals = hot.values();
                    for (int i = 0; i < rowCount; i++) {
                        if ((valid[i >>> 6] & (1L << (i & 63))) != 0 && vals[i] > threshold) {
                            outBits[i >>> 6] |= 1L << (i & 63);
                        }
                    }
                } else {
                    DoubleChunk dc = (DoubleChunk) chunk;
                    for (int i = 0; i < rowCount; i++) {
                        if ((valid[i >>> 6] & (1L << (i & 63))) != 0 && dc.getDouble(i) > threshold) {
                            outBits[i >>> 6] |= 1L << (i & 63);
                        }
                    }
                }
            }
        };
    }

    /** {@code col == value} for STRING columns. Compares dictionary codes (fast). */
    public static RowPredicate stringEq(String col, String value) {
        return new SingleColumnPredicate(col) {
            @Override
            public void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits) {
                Arrays.fill(outBits, 0L);
                ColumnChunk chunk = findChunk(cols, col, chunkIdx);
                StringChunk sc = (StringChunk) chunk;
                long[] valid = chunk.validity().words();
                // Resolve target code by scanning until we find it. A faster path
                // would access the column's dictionary directly; do that once codegen lands.
                int targetCode = -1;
                for (int i = 0; i < rowCount; i++) {
                    if ((valid[i >>> 6] & (1L << (i & 63))) != 0
                            && value.equals(sc.getString(i))) {
                        targetCode = sc.getCode(i);
                        break;
                    }
                }
                if (targetCode == -1) return;
                for (int i = 0; i < rowCount; i++) {
                    if ((valid[i >>> 6] & (1L << (i & 63))) != 0 && sc.getCode(i) == targetCode) {
                        outBits[i >>> 6] |= 1L << (i & 63);
                    }
                }
            }
        };
    }

    /** Logical AND of two predicates. */
    public static RowPredicate and(RowPredicate a, RowPredicate b) {
        Set<String> req = new LinkedHashSet<>(a.requiredColumns());
        req.addAll(b.requiredColumns());
        Set<String> reqFinal = Set.copyOf(req);
        return new RowPredicate() {
            @Override public Set<String> requiredColumns() { return reqFinal; }
            @Override
            public void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits) {
                a.evalChunk(cols, chunkIdx, rowCount, outBits);
                int wc = (rowCount + 63) >>> 6;
                long[] tmp = new long[wc];
                b.evalChunk(cols, chunkIdx, rowCount, tmp);
                for (int i = 0; i < wc; i++) outBits[i] &= tmp[i];
            }
        };
    }

    /** Logical OR of two predicates. */
    public static RowPredicate or(RowPredicate a, RowPredicate b) {
        Set<String> req = new LinkedHashSet<>(a.requiredColumns());
        req.addAll(b.requiredColumns());
        Set<String> reqFinal = Set.copyOf(req);
        return new RowPredicate() {
            @Override public Set<String> requiredColumns() { return reqFinal; }
            @Override
            public void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits) {
                a.evalChunk(cols, chunkIdx, rowCount, outBits);
                int wc = (rowCount + 63) >>> 6;
                long[] tmp = new long[wc];
                b.evalChunk(cols, chunkIdx, rowCount, tmp);
                for (int i = 0; i < wc; i++) outBits[i] |= tmp[i];
            }
        };
    }

    private static ColumnChunk findChunk(List<Column> cols, String name, int chunkIdx) {
        for (Column c : cols) {
            if (c.name().equals(name)) return c.chunk(chunkIdx);
        }
        throw new IllegalArgumentException("no such column: " + name);
    }

    @SuppressWarnings("unused")
    private static IntChunk asIntChunk(ColumnChunk c) {
        return (IntChunk) c;
    }

    /** Base class for predicates that reference a single column. */
    private abstract static class SingleColumnPredicate implements RowPredicate {
        private final Set<String> required;

        SingleColumnPredicate(String column) {
            this.required = Set.of(column);
        }

        @Override
        public final Set<String> requiredColumns() {
            return required;
        }
    }
}
