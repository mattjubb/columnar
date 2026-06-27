package io.columnar.core;

import io.columnar.api.Column;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Slicing;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.StringChunk;
import io.columnar.api.store.DoubleColumnStore;
import io.columnar.api.store.LongColumnStore;
import io.columnar.api.store.StringColumnStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * GROUP BY STRING with COUNT(...) and SUM(DOUBLE) measures. Aggregation scratch arrays are resized
 * as new groups arrive; finalized arrays are tightened to the populated prefix on each rollup.
 */
public final class HashAggregateOperator implements Operator {

    public enum AggKind {
        COUNT,
        SUM_DOUBLE
    }

    public record AggMeasure(AggKind kind, String sumInputColumnNullable, String outputColumn) {}

    private final Operator upstream;
    private final String groupColumn;
    private final List<AggMeasure> measures;
    private final Schema outputSchema;

    private volatile long cachedVer = Long.MIN_VALUE;
    private List<String> groupKeys = List.of();

    private long[][] cntScratch;
    private double[][] sumScratch;

    public HashAggregateOperator(Operator upstream, String groupColumn, List<AggMeasure> measures) {
        this.upstream = upstream;
        this.groupColumn = Objects.requireNonNull(groupColumn);
        this.measures = List.copyOf(measures);
        if (this.measures.isEmpty()) {
            throw new IllegalArgumentException("at least one aggregation measure is required");
        }

        Schema in = upstream.outputSchema();
        if (in.field(groupColumn).type() != DataType.STRING) {
            throw new IllegalArgumentException("GROUP column must be STRING, got " + in.field(groupColumn).type());
        }

        Schema.Builder o = Schema.builder().add(groupColumn, DataType.STRING);
        int j = 0;
        for (AggMeasure m : measures) {
            j++;
            switch (m.kind()) {
                case COUNT ->
                        o.add(
                                Objects.requireNonNull(m.outputColumn(), "COUNT alias #" + j),
                                DataType.LONG);
                case SUM_DOUBLE -> {
                    Objects.requireNonNull(m.outputColumn(), "SUM alias #" + j);
                    String srcCol =
                            Objects.requireNonNull(m.sumInputColumnNullable(), "SUM input #" + j);
                    if (in.field(srcCol).type() != DataType.DOUBLE) {
                        throw new IllegalArgumentException("SUM expects DOUBLE at " + srcCol);
                    }
                    o.add(m.outputColumn(), DataType.DOUBLE);
                }
                default -> throw new IllegalArgumentException(m.kind().name());
            }
        }

        int k = measures.size();
        cntScratch = new long[k][];
        sumScratch = new double[k][];
        for (int i = 0; i < k; i++) {
            AggMeasure mv = measures.get(i);
            if (mv.kind() == AggKind.COUNT || mv.kind() == AggKind.SUM_DOUBLE) {
                cntScratch[i] = new long[8192]; // reused for SUM to count numeric rows contributing
                if (mv.kind() == AggKind.SUM_DOUBLE) {
                    sumScratch[i] = new double[8192];
                }
            }
        }

        this.outputSchema = o.build();
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
        StringBuilder sb = new StringBuilder("HashAgg[group=").append(groupColumn).append(' ');
        int i = 0;
        for (AggMeasure mv : measures) {
            sb.append(i++).append(':').append(mv.kind()).append('→').append(mv.outputColumn());
            if (mv.kind() == AggKind.SUM_DOUBLE) sb.append('{').append(mv.sumInputColumnNullable()).append('}');
            sb.append(' ');
        }
        sb.append("](").append(upstream.signature()).append(')');
        return sb.toString();
    }

    @Override
    public synchronized ColumnarSlice compute(Viewport viewport, PullContext ctx) {
        LinkedHashSet<String> need = new LinkedHashSet<>();
        need.add(groupColumn);
        for (AggMeasure mv : measures) {
            if (mv.kind() == AggKind.SUM_DOUBLE) need.add(mv.sumInputColumnNullable());
        }
        ColumnarSlice inSlice =
                upstream.compute(Viewport.builder().columns(need).build(), ctx);
        final long upstreamVersion = inSlice.version();

        if (cachedVer != upstreamVersion) {
            resetScratch();
            rollup(inSlice);
            cachedVer = upstreamVersion;
        }

        ColumnarSlice full = assemble(upstreamVersion);
        return applyViewport(full, viewport);
    }

    private void resetScratch() {
        for (int i = 0; i < measures.size(); i++) {
            if (cntScratch[i] != null) java.util.Arrays.fill(cntScratch[i], 0);
            if (sumScratch[i] != null) java.util.Arrays.fill(sumScratch[i], 0.0);
        }
    }

    /** Perform full-scan aggregation rebuilding {@link #groupKeys}. */
    private void rollup(ColumnarSlice inSlice) {
        LinkedHashMap<String, Integer> ord = new LinkedHashMap<>();

        Column gcol = inSlice.column(groupColumn);
        Column[] sumCols = new Column[measures.size()];
        for (int mi = 0; mi < measures.size(); mi++) {
            if (measures.get(mi).kind() == AggKind.SUM_DOUBLE) {
                sumCols[mi] = inSlice.column(measures.get(mi).sumInputColumnNullable());
            }
        }

        for (int ci = 0; ci < gcol.chunkCount(); ci++) {
            StringChunk ck = (StringChunk) gcol.chunk(ci);
            int rowsChunk = ck.size();
            long[] valid = ck.validity().words();
            for (int local = 0; local < rowsChunk; local++) {
                if ((valid[local >>> 6] & (1L << (local & 63))) == 0) {
                    continue;
                }
                final String grp = ck.getString(local);
                int gid =
                        ord.computeIfAbsent(
                                grp,
                                key -> {
                                    int nextOrdinal = ord.size();
                                    widen(nextOrdinal + 2048);
                                    return nextOrdinal;
                                });

                for (int mi = 0; mi < measures.size(); mi++) {
                    AggMeasure mv = measures.get(mi);
                    switch (mv.kind()) {
                        case COUNT -> cntScratch[mi][gid]++;
                        case SUM_DOUBLE -> {
                            Column col = Objects.requireNonNull(sumCols[mi]);
                            DoubleChunk dc = (DoubleChunk) col.chunk(ci);
                            long[] vw = dc.validity().words();
                            if ((vw[local >>> 6] & (1L << (local & 63))) != 0) {
                                sumScratch[mi][gid] += dc.getDouble(local);
                                cntScratch[mi][gid]++;
                            }
                        }
                    }
                }
            }
        }

        groupKeys = List.copyOf(ord.keySet());
        shrinkTo(ord.size());
    }

    private void widen(int minRows) {
        for (int i = 0; i < measures.size(); i++) {
            AggMeasure mv = measures.get(i);
            if ((mv.kind() == AggKind.COUNT || mv.kind() == AggKind.SUM_DOUBLE)) {
                if (cntScratch[i] != null && cntScratch[i].length < minRows) {
                    int nl = Math.max(minRows + 8192, cntScratch[i].length * 2);
                    cntScratch[i] = java.util.Arrays.copyOf(cntScratch[i], nl);
                }
                if (mv.kind() == AggKind.SUM_DOUBLE && sumScratch[i] != null && sumScratch[i].length < minRows) {
                    int nl = Math.max(minRows + 8192, sumScratch[i].length * 2);
                    sumScratch[i] = java.util.Arrays.copyOf(sumScratch[i], nl);
                }
            }
        }
    }

    private void shrinkTo(int groups) {
        for (int i = 0; i < measures.size(); i++) {
            AggMeasure mv = measures.get(i);
            if (mv.kind() == AggKind.COUNT && cntScratch[i] != null) {
                cntScratch[i] = groups == 0 ? new long[0] : java.util.Arrays.copyOf(cntScratch[i], groups);
            }
            if (mv.kind() == AggKind.SUM_DOUBLE) {
                if (cntScratch[i] != null) {
                    cntScratch[i] = groups == 0 ? new long[0] : java.util.Arrays.copyOf(cntScratch[i], groups);
                }
                if (sumScratch[i] != null) {
                    sumScratch[i] = groups == 0 ? new double[0] : java.util.Arrays.copyOf(sumScratch[i], groups);
                }
            }
        }
    }

    private ColumnarSlice assemble(long version) {
        if (groupKeys.isEmpty()) {
            return ColumnarSlice.empty(outputSchema, version);
        }

        int groups = groupKeys.size();
        List<Column> cols = new ArrayList<>(outputSchema.size());
        StringColumnStore groupStore = new StringColumnStore(groupColumn);
        for (String k : groupKeys) {
            groupStore.appendString(k);
        }
        groupStore.sealActive();
        cols.add(groupStore.snapshot());

        for (int mi = 0; mi < measures.size(); mi++) {
            AggMeasure mv = measures.get(mi);
            switch (mv.kind()) {
                case COUNT -> {
                    LongColumnStore ls = new LongColumnStore(mv.outputColumn());
                    for (int g = 0; g < groups; g++) {
                        ls.appendLong(cntScratch[mi][g]);
                    }
                    ls.sealActive();
                    cols.add(ls.snapshot());
                }
                case SUM_DOUBLE -> {
                    DoubleColumnStore ds = new DoubleColumnStore(mv.outputColumn());
                    for (int g = 0; g < groups; g++) {
                        ds.appendDouble(sumScratch[mi][g]);
                    }
                    ds.sealActive();
                    cols.add(ds.snapshot());
                }
            }
        }

        long rows = cols.isEmpty() ? 0L : cols.get(0).size();
        return new ColumnarSlice(outputSchema, cols, rows, version);
    }

    private static ColumnarSlice applyViewport(ColumnarSlice full, Viewport viewport) {
        long from = viewport.rows().from();
        long to = Math.min(viewport.rows().to(), full.rowCount());
        if (to < from) to = from;

        List<Column> sliced = (from == 0 && to >= full.rowCount()) ? full.columns() : Slicing.slice(full.columns(), from, to);

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
