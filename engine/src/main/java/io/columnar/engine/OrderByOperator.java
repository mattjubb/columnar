package io.columnar.engine;

import io.columnar.core.Column;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.DataType;
import io.columnar.core.RowRange;
import io.columnar.core.Schema;
import io.columnar.core.Slicing;
import io.columnar.core.Table;
import io.columnar.core.Viewport;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.LongChunk;
import io.columnar.core.store.ColumnStore;
import io.columnar.core.store.ColumnStores;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Single-key sort pushing the viewport row range into the upstream projection. Implements a stable
 * sort over LONG or DOUBLE keys; ascending/descending via constructor flag. Applies {@link Viewport}
 * column subset/limit after reordering.
 */
public final class OrderByOperator implements Operator {

    private final Operator upstream;
    private final String sortColumn;
    private final boolean ascending;

    public OrderByOperator(Operator upstream, String sortColumn, boolean ascending) {
        this.upstream = upstream;
        this.sortColumn = Objects.requireNonNull(sortColumn);
        this.ascending = ascending;
        DataType tp = upstream.outputSchema().field(sortColumn).type();
        if (tp != DataType.LONG && tp != DataType.DOUBLE) {
            throw new UnsupportedOperationException(
                    "OrderBy MVP supports LONG/DOUBLE keys only — got " + tp);
        }
    }

    public String sortColumn() {
        return sortColumn;
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
        return "OrderBy["
                + sortColumn + (ascending ? "+" : "-") + ']'
                + "(" + upstream.signature() + ")";
    }

    @Override
    public ColumnarSlice compute(Viewport viewport, PullContext ctx) {
        Schema sch = upstream.outputSchema();

        LinkedHashSet<String> need = new LinkedHashSet<>();
        if (viewport.columns().isPresent()) {
            need.addAll(viewport.columns().get());
        } else {
            for (Schema.Field f : sch.fields()) {
                need.add(f.name());
            }
        }
        need.add(sortColumn);

        RowRange rows = viewport.rows();
        Viewport upViewport = Viewport.builder().rows(rows).columns(need).build();
        ColumnarSlice in = upstream.compute(upViewport, ctx);

        if (in.rowCount() == 0) {
            return ColumnarSlice.empty(sch, in.version());
        }

        int n = (int) in.rowCount();
        if (n != in.rowCount()) {
            throw new UnsupportedOperationException("OrderBy MVP limited to int row counts: " + in.rowCount());
        }

        Column keyCol = in.column(sortColumn);
        DataType kt = keyCol.type();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }

        Comparator<Integer> cmpBase =
                switch (kt) {
                    case LONG -> Comparator.comparingLong(i -> readLong(keyCol, i));
                    case DOUBLE -> Comparator.comparingDouble(i -> readDouble(keyCol, i));
                    default -> throw new IllegalStateException();
                };
        if (!ascending) {
            cmpBase = cmpBase.reversed();
        }
        Comparator<Integer> stable = cmpBase.thenComparingInt(i -> i);
        Arrays.sort(order, stable);

        ColumnarSlice sorted = gatherPermutation(in, order);
        return applyViewport(sorted, viewport);
    }

    private static long readLong(Column column, long globalRow) {
        long pos = 0;
        for (var ck : column.chunks()) {
            if (globalRow < pos + ck.size()) {
                int local = (int) (globalRow - pos);
                if (ck.validity().isNull(local)) {
                    throw new IllegalArgumentException(
                            "null sort key unsupported in OrderBy MVP for column=" + column.name());
                }
                return ((LongChunk) ck).getLong(local);
            }
            pos += ck.size();
        }
        throw new IndexOutOfBoundsException(Long.toString(globalRow));
    }

    private static double readDouble(Column column, long globalRow) {
        long pos = 0;
        for (var ck : column.chunks()) {
            if (globalRow < pos + ck.size()) {
                int local = (int) (globalRow - pos);
                if (ck.validity().isNull(local)) {
                    throw new IllegalArgumentException(
                            "null sort key unsupported for column=" + column.name());
                }
                return ((DoubleChunk) ck).getDouble(local);
            }
            pos += ck.size();
        }
        throw new IndexOutOfBoundsException(Long.toString(globalRow));
    }

    private ColumnarSlice gatherPermutation(ColumnarSlice in, Integer[] permutation) {
        Schema sch = in.schema();
        LinkedHashMap<String, ColumnStore> builders = new LinkedHashMap<>();
        for (Schema.Field f : sch.fields()) {
            builders.put(f.name(), ColumnStores.create(f));
        }

        for (Integer idx : permutation) {
            long global = idx.longValue();
            for (Schema.Field field : sch.fields()) {
                HashJoinOperator.appendCell(builders.get(field.name()), in.column(field.name()), global);
            }
        }

        List<Column> outCols = new ArrayList<>(builders.size());
        for (ColumnStore cs : builders.values()) {
            cs.sealActive();
            outCols.add(cs.snapshot());
        }
        long rc = permutation.length;
        return new ColumnarSlice(sch, outCols, rc, in.version());
    }

    /** Apply row/column/limit trims after reordering stage. */

    /** Viewport narrowing consistent with Pivot/Join operators. */
    private static ColumnarSlice applyViewport(ColumnarSlice full, Viewport viewport) {
        long from = viewport.rows().from();
        long to = Math.min(viewport.rows().to(), full.rowCount());
        if (to < from) to = from;

        List<Column> sliced =
                (from == 0 && to >= full.rowCount()) ? full.columns() : Slicing.slice(full.columns(), from, to);

        Schema eff = full.schema();
        if (viewport.columns().isPresent()) {
            List<String> subset = new ArrayList<>(viewport.columns().get());
            sliced = Slicing.project(sliced, subset);
            eff = full.schema().select(subset);
        }

        long rowCount = sliced.isEmpty() ? 0L : sliced.get(0).size();
        if (viewport.hasLimit()) {
            long limit = viewport.limit().getAsLong();
            if (rowCount > limit) {
                sliced = Slicing.slice(sliced, 0, limit);
                rowCount = limit;
            }
        }
        return new ColumnarSlice(eff, sliced, rowCount, full.version());
    }
}
