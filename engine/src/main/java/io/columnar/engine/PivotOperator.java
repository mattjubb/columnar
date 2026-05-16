package io.columnar.engine;

import io.columnar.core.Column;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.DataType;
import io.columnar.core.Schema;
import io.columnar.core.Slicing;
import io.columnar.core.Table;
import io.columnar.core.Viewport;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.HotDoubleChunk;
import io.columnar.core.chunk.HotStringChunk;
import io.columnar.core.chunk.StringChunk;
import io.columnar.core.store.DoubleColumnStore;
import io.columnar.core.store.StringColumnStore;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pivot: turn distinct values of a "pivot" column into output columns,
 * aggregating a value column per (row-key, pivot-key) cell.
 *
 * <p>This MVP supports:
 * <ul>
 *   <li>A single STRING row-key column.</li>
 *   <li>A single STRING pivot column with a <b>fixed</b> set of expected pivot keys.
 *       Pivot keys not in the fixed list are silently dropped from the input.</li>
 *   <li>A single DOUBLE value column with {@code sum} aggregation.</li>
 * </ul>
 *
 * <p>Output schema: {@code [rowKey: STRING, pivotKey1: DOUBLE, pivotKey2: DOUBLE, ...]}.
 *
 * <p>Performance: when chunks are {@link HotStringChunk} (which they are for
 * source tables built via {@link StringColumnStore}), the inner loop operates
 * on primitive int dictionary codes — no string equality, no boxing.
 *
 * <p>Future work (called out in the plan): dynamic pivot keys (schema growth),
 * non-string row keys, multiple aggregations, and incremental maintenance over
 * live source updates.
 */
public final class PivotOperator implements Operator {

    private final Operator upstream;
    private final String rowKeyColumn;
    private final String pivotColumn;
    private final String valueColumn;
    private final List<String> pivotKeys;
    private final Schema outputSchema;

    public PivotOperator(Operator upstream,
                         String rowKeyColumn,
                         String pivotColumn,
                         String valueColumn,
                         List<String> pivotKeys) {
        this.upstream = upstream;
        this.rowKeyColumn = rowKeyColumn;
        this.pivotColumn = pivotColumn;
        this.valueColumn = valueColumn;
        this.pivotKeys = List.copyOf(pivotKeys);

        Schema upSchema = upstream.outputSchema();
        ensureType(upSchema, rowKeyColumn, DataType.STRING);
        ensureType(upSchema, pivotColumn, DataType.STRING);
        ensureType(upSchema, valueColumn, DataType.DOUBLE);

        Schema.Builder b = Schema.builder().add(rowKeyColumn, DataType.STRING);
        Set<String> dedupe = new LinkedHashSet<>();
        for (String pk : this.pivotKeys) {
            if (!dedupe.add(pk)) {
                throw new IllegalArgumentException("duplicate pivot key: " + pk);
            }
            b.add(pk, DataType.DOUBLE);
        }
        this.outputSchema = b.build();
    }

    private static void ensureType(Schema schema, String col, DataType expected) {
        Schema.Field f = schema.field(col);
        if (f.type() != expected) {
            throw new IllegalArgumentException(
                    "column " + col + " has type " + f.type() + ", expected " + expected);
        }
    }

    @Override
    public Schema outputSchema() {
        return outputSchema;
    }

    @Override
    public List<Table> upstreams() {
        return upstream.upstreams();
    }

    @Override
    public String signature() {
        return "Pivot[row=" + rowKeyColumn
                + ",pivot=" + pivotColumn + ",value=sum(" + valueColumn + ")"
                + ",keys=" + pivotKeys.size() + "]("
                + upstream.signature() + ")";
    }

    @Override
    public ColumnarSlice compute(Viewport viewport, PullContext ctx) {
        // Pivot must scan all input data to build groups. Pull the three needed
        // columns over the full row range from upstream.
        Set<String> needed = new LinkedHashSet<>();
        needed.add(rowKeyColumn);
        needed.add(pivotColumn);
        needed.add(valueColumn);
        Viewport upVp = Viewport.builder().columns(needed).build();
        ColumnarSlice in = upstream.compute(upVp, ctx);

        Column rowKeyCol = in.column(rowKeyColumn);
        Column pivotCol = in.column(pivotColumn);
        Column valueCol = in.column(valueColumn);

        // Pivot-key resolution: we look up each fixed pivot key in the source
        // column's dictionary so the inner loop only sees int codes.
        Int2IntOpenHashMap pivotCodeToIdx = new Int2IntOpenHashMap(pivotKeys.size() * 2);
        pivotCodeToIdx.defaultReturnValue(-1);
        for (int p = 0; p < pivotKeys.size(); p++) {
            int code = lookupCode(pivotCol, pivotKeys.get(p));
            if (code >= 0) {
                pivotCodeToIdx.put(code, p);
            }
            // If the pivot key isn't in the dictionary at all, that column will
            // remain all-zero — which is the correct sum for "no contributions".
        }

        // Row-key state: dictionary-code → ordinal, parallel to a list of sum arrays.
        Int2IntOpenHashMap rowCodeToIdx = new Int2IntOpenHashMap();
        rowCodeToIdx.defaultReturnValue(-1);
        ObjectArrayList<String> rowKeyValues = new ObjectArrayList<>();
        ObjectArrayList<double[]> sums = new ObjectArrayList<>();
        int pivotCount = pivotKeys.size();

        int chunks = rowKeyCol.chunkCount();
        for (int ci = 0; ci < chunks; ci++) {
            StringChunk rkChunk = (StringChunk) rowKeyCol.chunk(ci);
            StringChunk pvChunk = (StringChunk) pivotCol.chunk(ci);
            DoubleChunk vlChunk = (DoubleChunk) valueCol.chunk(ci);

            long[] rkValid = rkChunk.validity().words();
            long[] pvValid = pvChunk.validity().words();
            long[] vlValid = vlChunk.validity().words();

            int rows = rkChunk.size();

            // HOT fast path: pull the raw int[] / double[] arrays out for tight loops.
            int[] rkCodes = (rkChunk instanceof HotStringChunk h1) ? h1.codes() : null;
            int[] pvCodes = (pvChunk instanceof HotStringChunk h2) ? h2.codes() : null;
            double[] vals = (vlChunk instanceof HotDoubleChunk h3) ? h3.values() : null;

            for (int i = 0; i < rows; i++) {
                // Skip nulls (any of the three columns null on this row).
                if ((rkValid[i >>> 6] & (1L << (i & 63))) == 0) continue;
                if ((pvValid[i >>> 6] & (1L << (i & 63))) == 0) continue;
                if ((vlValid[i >>> 6] & (1L << (i & 63))) == 0) continue;

                int pvCode = pvCodes != null ? pvCodes[i] : pvChunk.getCode(i);
                int pIdx = pivotCodeToIdx.get(pvCode);
                if (pIdx < 0) continue; // not in fixed key set

                int rkCode = rkCodes != null ? rkCodes[i] : rkChunk.getCode(i);
                int rIdx = rowCodeToIdx.get(rkCode);
                if (rIdx < 0) {
                    rIdx = rowKeyValues.size();
                    rowCodeToIdx.put(rkCode, rIdx);
                    rowKeyValues.add(rkChunk.getString(i));
                    sums.add(new double[pivotCount]);
                }

                double v = vals != null ? vals[i] : vlChunk.getDouble(i);
                sums.get(rIdx)[pIdx] += v;
            }
        }

        long totalRows = rowKeyValues.size();

        // Build output columns.
        StringColumnStore rkStore = new StringColumnStore(rowKeyColumn);
        for (int i = 0; i < rowKeyValues.size(); i++) {
            rkStore.appendString(rowKeyValues.get(i));
        }
        rkStore.sealActive();

        List<Column> outCols = new ArrayList<>(pivotCount + 1);
        outCols.add(rkStore.snapshot());
        for (int p = 0; p < pivotCount; p++) {
            DoubleColumnStore dStore = new DoubleColumnStore(pivotKeys.get(p));
            for (int i = 0; i < sums.size(); i++) {
                dStore.appendDouble(sums.get(i)[p]);
            }
            dStore.sealActive();
            outCols.add(dStore.snapshot());
        }

        ColumnarSlice full = new ColumnarSlice(outputSchema, outCols, totalRows, in.version());
        return applyOutputViewport(full, viewport);
    }

    /** Apply the consumer's row range / column subset / limit to the materialized pivot. */
    private ColumnarSlice applyOutputViewport(ColumnarSlice full, Viewport viewport) {
        long from = viewport.rows().from();
        long to = Math.min(viewport.rows().to(), full.rowCount());
        if (to < from) to = from;

        List<Column> sliced = (from == 0 && to >= full.rowCount())
                ? full.columns()
                : Slicing.slice(full.columns(), from, to);

        Schema effectiveSchema = full.schema();
        if (viewport.columns().isPresent()) {
            List<String> picked = new ArrayList<>(viewport.columns().get());
            sliced = Slicing.project(sliced, picked);
            effectiveSchema = full.schema().select(picked);
        }

        long rowCount = sliced.isEmpty() ? 0L : sliced.get(0).size();
        if (viewport.hasLimit()) {
            long limit = viewport.limit().getAsLong();
            if (rowCount > limit) {
                sliced = Slicing.slice(sliced, 0, limit);
                rowCount = limit;
            }
        }
        return new ColumnarSlice(effectiveSchema, sliced, rowCount, full.version());
    }

    /**
     * Resolve the dictionary code for {@code value}. Uses the column's shared
     * dictionary directly when available (HOT-chunk fast path); otherwise
     * falls back to a chunk scan. Returns -1 if absent.
     */
    private static int lookupCode(Column stringCol, String value) {
        for (int ci = 0; ci < stringCol.chunkCount(); ci++) {
            StringChunk sc = (StringChunk) stringCol.chunk(ci);
            if (sc instanceof HotStringChunk hsc) {
                // Dictionary is shared across all chunks of this column — one lookup is enough.
                return hsc.dictionary().codeOf(value);
            }
            int rows = sc.size();
            for (int i = 0; i < rows; i++) {
                if (sc.validity().isValid(i) && value.equals(sc.getString(i))) {
                    return sc.getCode(i);
                }
            }
        }
        return -1;
    }
}
