package io.columnar.engine;

import io.columnar.core.Column;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.Schema;
import io.columnar.core.Slicing;
import io.columnar.core.Table;
import io.columnar.core.Viewport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Filter operator. Evaluates a {@link RowPredicate} per chunk and emits a
 * compacted slice containing only passing rows.
 *
 * <p>Schema is unchanged from the upstream operator. The filter is applied
 * to the upstream slice corresponding to the requested viewport's row range
 * (no filter pushdown into the source yet — that's a future optimization).
 *
 * <p>Column-pushdown correctness: when the consumer requests a viewport with
 * a column subset, the filter unions that subset with its
 * {@link RowPredicate#requiredColumns() predicate columns} so the predicate
 * can still evaluate. Predicate-only columns are dropped from the output
 * before returning.
 */
public final class FilterOperator implements Operator {

    private final Operator upstream;
    private final RowPredicate predicate;
    private final String predicateName;

    public FilterOperator(Operator upstream, RowPredicate predicate, String predicateName) {
        this.upstream = upstream;
        this.predicate = predicate;
        this.predicateName = predicateName;
    }

    @Override
    public Schema outputSchema() {
        return upstream.outputSchema();
    }

    @Override
    public List<Table> upstreams() {
        return upstream.upstreams();
    }

    @Override
    public String signature() {
        return "Filter[" + predicateName + "](" + upstream.signature() + ")";
    }

    @Override
    public ColumnarSlice compute(Viewport viewport, PullContext ctx) {
        // Compute the column set we need from upstream: the consumer's requested
        // columns (if any) UNION the predicate's required columns.
        Set<String> upstreamCols = null;
        if (viewport.columns().isPresent()) {
            upstreamCols = new LinkedHashSet<>(viewport.columns().get());
            upstreamCols.addAll(predicate.requiredColumns());
        }
        Viewport upViewport = Viewport.builder()
                .rows(viewport.rows())
                .columns(upstreamCols)
                .build();

        ColumnarSlice in = upstream.compute(upViewport, ctx);
        if (in.rowCount() == 0) {
            // Still need to project down if we widened the column set above.
            return projectIfRequested(in, viewport);
        }

        List<Column> srcCols = in.columns();
        int chunkCount = in.chunkCount();

        long[][] masks = new long[chunkCount][];
        int[] popCounts = new int[chunkCount];
        long passing = 0;
        for (int ci = 0; ci < chunkCount; ci++) {
            int rows = srcCols.get(0).chunk(ci).size();
            long[] bits = new long[(rows + 63) >>> 6];
            predicate.evalChunk(srcCols, ci, rows, bits);
            int pop = Selection.popCount(bits, rows);
            masks[ci] = bits;
            popCounts[ci] = pop;
            passing += pop;
            if (viewport.hasLimit() && passing >= viewport.limit().getAsLong()) {
                long overflow = passing - viewport.limit().getAsLong();
                if (overflow > 0) {
                    trimTail(bits, rows, (int) overflow);
                    popCounts[ci] = pop - (int) overflow;
                    passing = viewport.limit().getAsLong();
                }
                for (int j = ci + 1; j < chunkCount; j++) {
                    int rj = srcCols.get(0).chunk(j).size();
                    masks[j] = new long[(rj + 63) >>> 6];
                    popCounts[j] = 0;
                }
                break;
            }
        }

        List<Column> out = new ArrayList<>(srcCols.size());
        for (Column c : srcCols) {
            out.add(Selection.apply(c, masks, popCounts));
        }
        ColumnarSlice filtered = new ColumnarSlice(in.schema(), out, passing, in.version());
        return projectIfRequested(filtered, viewport);
    }

    /**
     * If the consumer's viewport requested a specific column subset, drop any
     * extra columns we pulled in for the predicate.
     */
    private ColumnarSlice projectIfRequested(ColumnarSlice slice, Viewport requested) {
        if (requested.columns().isEmpty()) {
            return slice;
        }
        List<String> wanted = new ArrayList<>(requested.columns().get());
        if (wanted.size() == slice.columnCount()
                && wanted.equals(slice.schema().names())) {
            return slice;
        }
        List<Column> picked = Slicing.project(slice.columns(), wanted);
        Schema projected = slice.schema().select(wanted);
        return new ColumnarSlice(projected, picked, slice.rowCount(), slice.version());
    }

    /** Clear the highest {@code drop} set bits in {@code bits} (rowCount-bounded). */
    private static void trimTail(long[] bits, int rowCount, int drop) {
        for (int i = rowCount - 1; i >= 0 && drop > 0; i--) {
            int wIdx = i >>> 6;
            long mask = 1L << (i & 63);
            if ((bits[wIdx] & mask) != 0) {
                bits[wIdx] &= ~mask;
                drop--;
            }
        }
    }
}
