package io.columnar.core;
import io.columnar.api.JoinKind;

import io.columnar.api.Column;
import io.columnar.api.ColumnChunk;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Slicing;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.BooleanChunk;
import io.columnar.api.chunk.DoubleArrayChunk;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.FloatChunk;
import io.columnar.api.chunk.InstantChunk;
import io.columnar.api.chunk.IntChunk;
import io.columnar.api.chunk.LongChunk;
import io.columnar.api.chunk.StringChunk;
import io.columnar.api.store.BooleanColumnStore;
import io.columnar.api.store.ColumnStore;
import io.columnar.api.store.ColumnStores;
import io.columnar.api.store.DoubleArrayColumnStore;
import io.columnar.api.store.DoubleColumnStore;
import io.columnar.api.store.FloatColumnStore;
import io.columnar.api.store.InstantColumnStore;
import io.columnar.api.store.IntColumnStore;
import io.columnar.api.store.LongColumnStore;
import io.columnar.api.store.StringColumnStore;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
/**
 * Flexible STRING-equijoin spanning INNER / LEFT / FULL (hash-build, probe-stream) plus RIGHT outer
 * (hash-probe, build-stream).
 */
public final class HashJoinOperator implements Operator {

    private final Operator probe;
    private final Operator build;
    private final String probeKey;
    private final String buildKey;
    private final Schema outputSchema;
    private final JoinKind joinKind;

    private long cachedBuildVersion = -1L;
    private Object2ObjectOpenHashMap<String, IntArrayList> buildHash;
    private ColumnarSlice cachedBuildSlice;

    private long cachedProbeVersion = -1L;
    private Object2ObjectOpenHashMap<String, IntArrayList> probeHash;
    private ColumnarSlice cachedProbeSlice;

    /** Defaults to INNER. */
    public HashJoinOperator(Operator probe, Operator build, String probeKey, String buildKey) {
        this(probe, build, probeKey, buildKey, JoinKind.INNER);
    }

    public HashJoinOperator(Operator probe, Operator build, String probeKey, String buildKey, JoinKind joinKind) {
        this.probe = probe;
        this.build = build;
        this.probeKey = probeKey;
        this.buildKey = buildKey;
        this.joinKind = joinKind;

        ensureStringKey(probe.outputSchema(), probeKey, "probe");
        ensureStringKey(build.outputSchema(), buildKey, "build");

        Schema.Builder schemaBuilder = Schema.builder();
        for (Schema.Field f : probe.outputSchema().fields()) {
            schemaBuilder.add(f.name(), f.type());
        }
        for (Schema.Field f : build.outputSchema().fields()) {
            if (f.name().equals(buildKey)) {
                continue;
            }
            if (probe.outputSchema().contains(f.name())) {
                throw new IllegalArgumentException("column name conflict between probe and build sides: " + f.name());
            }
            schemaBuilder.add(f.name(), f.type());
        }
        this.outputSchema = schemaBuilder.build();
    }

    private static void ensureStringKey(Schema schema, String col, String side) {
        Schema.Field f = schema.field(col);
        if (f.type() != DataType.STRING) {
            throw new IllegalArgumentException(
                    side + " join key " + col + " has type " + f.type() + ", expected STRING");
        }
    }

    public JoinKind joinKind() {
        return joinKind;
    }

    @Override
    public Schema outputSchema() {
        return outputSchema;
    }

    @Override
    public List<Table> upstreams() {
        List<Table> all = new ArrayList<>(probe.upstreams().size() + build.upstreams().size());
        all.addAll(probe.upstreams());
        all.addAll(build.upstreams());
        return all;
    }

    @Override
    public String signature() {
        return "HashJoin[" + joinKind + "," + probeKey + "=" + buildKey + "]("
                + probe.signature() + "," + build.signature() + ")";
    }

    @Override
    public ColumnarSlice compute(Viewport viewport, PullContext ctx) {
        return switch (joinKind) {
            case INNER, LEFT, FULL -> computeHashBuildScanProbe(viewport, ctx);
            case RIGHT -> computeHashProbeScanBuild(viewport, ctx);
        };
    }

    /** Classic pattern: hash the build slice, iterate probe rows expanding matches. */

    /** FULL additionally tracks unseen build-side rows emitted after the probe pass. */

    /** LEFT/FULL unmatched probes append NULL build payloads. */

    /** FULL scans {@code matchedBuild} bitset afterward. */

    /** RIGHT pattern reverses hashing. */
    private ColumnarSlice computeHashBuildScanProbe(Viewport viewport, PullContext ctx) {
        ColumnarSlice buildSlice = build.compute(Viewport.ALL, ctx);
        if (buildSlice.version() != cachedBuildVersion || buildHash == null) {
            buildHash = hashStringColumn(buildSlice, buildKey);
            cachedBuildSlice = buildSlice;
            cachedBuildVersion = buildSlice.version();
        } else {
            buildSlice = cachedBuildSlice;
        }

        ColumnarSlice probeSlice = probe.compute(Viewport.ALL, ctx);
        LinkedHashMap<String, ColumnStore> outs = bootstrapStores();

        int buildRows =
                joinKind == JoinKind.FULL ? safePositiveIntExact(buildSlice.rowCount()) : 0;

        boolean[] matchedBuild =
                joinKind == JoinKind.FULL ? new boolean[buildRows] : null;

        Column probeJoin = probeSlice.column(probeKey);
        long probeGlobal = 0L;
        for (int ci = 0; ci < probeJoin.chunkCount(); ci++) {
            StringChunk ck = (StringChunk) probeJoin.chunk(ci);
            int rows = ck.size();
            for (int i = 0; i < rows; i++) {
                if (ck.validity().isNull(i)) {
                    probeGlobal++;
                    continue;
                }
                String key = ck.getString(i);
                IntArrayList hits = buildHash.get(key);

                if (hits == null || hits.isEmpty()) {
                    if (joinKind == JoinKind.LEFT || joinKind == JoinKind.FULL) {
                        appendProbeRow(outs, probeSlice, probeGlobal);
                        appendNullBuildColumns(outs, build.outputSchema(), buildKey);
                    }
                    probeGlobal++;
                    continue;
                }

                for (int m = 0; m < hits.size(); m++) {
                    long buildGlob = hits.getInt(m);
                    emitMatchedProbeBuild(
                            outs,
                            probeSlice,
                            build.outputSchema(),
                            buildSlice,
                            buildKey,
                            probeGlobal,
                            buildGlob);
                    if (matchedBuild != null) {
                        matchedBuild[(int) buildGlob] = true;
                    }
                }
                probeGlobal++;
            }
        }

        if (joinKind == JoinKind.FULL && matchedBuild != null) {
            for (int b = 0; b < matchedBuild.length; b++) {
                if (!matchedBuild[b]) {
                    appendNullProbeSlices(outs, probe.outputSchema());
                    appendBuildDedupColumns(outs, build.outputSchema(), buildSlice, buildKey, (long) b);
                }
            }
        }

        long version = probeSlice.version() + buildSlice.version();
        return finish(outs, version, viewport);
    }

    private ColumnarSlice computeHashProbeScanBuild(Viewport viewport, PullContext ctx) {
        ColumnarSlice probeSlice = probe.compute(Viewport.ALL, ctx);
        if (probeSlice.version() != cachedProbeVersion || probeHash == null) {
            probeHash = hashStringColumn(probeSlice, probeKey);
            cachedProbeSlice = probeSlice;
            cachedProbeVersion = probeSlice.version();
        } else {
            probeSlice = cachedProbeSlice;
        }

        ColumnarSlice buildSlice = build.compute(Viewport.ALL, ctx);
        LinkedHashMap<String, ColumnStore> outs = bootstrapStores();

        Column buildJoin = buildSlice.column(buildKey);
        long buildGlobal = 0L;

        for (int ci = 0; ci < buildJoin.chunkCount(); ci++) {
            StringChunk chunk = (StringChunk) buildJoin.chunk(ci);
            int rows = chunk.size();
            for (int i = 0; i < rows; i++) {
                if (chunk.validity().isNull(i)) {
                    buildGlobal++;
                    continue;
                }
                String key = chunk.getString(i);
                IntArrayList probeHits = probeHash.get(key);

                if (probeHits == null || probeHits.isEmpty()) {
                    appendNullProbeSlices(outs, probeSlice.schema());
                    appendBuildDedupColumns(outs, build.outputSchema(), buildSlice, buildKey, buildGlobal);
                    buildGlobal++;
                    continue;
                }

                for (int m = 0; m < probeHits.size(); m++) {
                    long probeGlob = probeHits.getInt(m);
                    emitMatchedProbeBuild(
                            outs,
                            probeSlice,
                            build.outputSchema(),
                            buildSlice,
                            buildKey,
                            probeGlob,
                            buildGlobal);
                }
                buildGlobal++;
            }
        }

        long ver = probeSlice.version() + buildSlice.version();
        return finish(outs, ver, viewport);
    }

    private LinkedHashMap<String, ColumnStore> bootstrapStores() {
        LinkedHashMap<String, ColumnStore> outs = new LinkedHashMap<>();
        for (Schema.Field f : outputSchema.fields()) {
            outs.put(f.name(), ColumnStores.create(f));
        }
        return outs;
    }

    private ColumnarSlice finish(LinkedHashMap<String, ColumnStore> outs, long version, Viewport viewport) {
        List<Column> cols = new ArrayList<>(outs.size());
        for (ColumnStore s : outs.values()) {
            s.sealActive();
            cols.add(s.snapshot());
        }
        long rowCount = cols.isEmpty() ? 0L : cols.get(0).size();
        ColumnarSlice full = new ColumnarSlice(outputSchema, cols, rowCount, version);
        return applyOutputViewport(full, viewport);
    }

    private static void emitMatchedProbeBuild(
            LinkedHashMap<String, ColumnStore> outs,
            ColumnarSlice probeSlice,
            Schema buildSchema,
            ColumnarSlice buildSlice,
            String dedupeJoinKeyName,
            long probeGlob,
            long buildGlob) {
        appendProbeRow(outs, probeSlice, probeGlob);
        appendBuildDedupColumns(outs, buildSchema, buildSlice, dedupeJoinKeyName, buildGlob);
    }

    private static void appendProbeRow(LinkedHashMap<String, ColumnStore> outs, ColumnarSlice probe, long probeGlob) {
        for (Schema.Field f : probe.schema().fields()) {
            appendCell(outs.get(f.name()), probe.column(f.name()), probeGlob);
        }
    }

    private static void appendNullProbeSlices(LinkedHashMap<String, ColumnStore> outs, Schema probeSchema) {
        for (Schema.Field f : probeSchema.fields()) {
            outs.get(f.name()).appendNull();
        }
    }

    private static void appendNullBuildColumns(
            LinkedHashMap<String, ColumnStore> outs, Schema rawBuildSchema, String buildJoinColumn) {
        for (Schema.Field bf : rawBuildSchema.fields()) {
            if (bf.name().equals(buildJoinColumn)) {
                continue;
            }
            if (!outs.containsKey(bf.name())) {
                continue;
            }
            outs.get(bf.name()).appendNull();
        }
    }

    private static void appendBuildDedupColumns(
            LinkedHashMap<String, ColumnStore> outs,
            Schema buildSchema,
            ColumnarSlice buildSlice,
            String dedupeJoinKeyName,
            long buildGlob) {

        for (Schema.Field f : buildSchema.fields()) {
            if (f.name().equals(dedupeJoinKeyName)) {
                continue;
            }
            if (!outs.containsKey(f.name())) {
                continue;
            }
            appendCell(outs.get(f.name()), buildSlice.column(f.name()), buildGlob);
        }
    }

    /** Build GLOBAL row index keyed hash buckets for STRING join columns. */

    /** */
    private Object2ObjectOpenHashMap<String, IntArrayList> hashStringColumn(ColumnarSlice slice, String columnName) {
        Object2ObjectOpenHashMap<String, IntArrayList> map = new Object2ObjectOpenHashMap<>();
        Column kCol = slice.column(columnName);
        int globalRow = 0;
        for (int ci = 0; ci < kCol.chunkCount(); ci++) {
            StringChunk sc = (StringChunk) kCol.chunk(ci);
            int rows = sc.size();
            for (int i = 0; i < rows; i++) {
                if (sc.validity().isNull(i)) {
                    globalRow++;
                    continue;
                }
                String key = sc.getString(i);
                IntArrayList list = map.get(key);
                if (list == null) {
                    list = new IntArrayList(1);
                    map.put(key, list);
                }
                list.add(globalRow);
                globalRow++;
            }
        }
        return map;
    }

    private static int safePositiveIntExact(long rowCount) {
        if (rowCount > Integer.MAX_VALUE) {
            throw new UnsupportedOperationException(
                    "FULL join bookkeeping limited to builds with <= Integer.MAX_VALUE rows (got " + rowCount + ")");
        }
        if (rowCount < 0) {
            throw new IllegalArgumentException("negative rowCount: " + rowCount);
        }
        return (int) rowCount;
    }

    private ColumnarSlice applyOutputViewport(ColumnarSlice full, Viewport viewport) {
        long from = viewport.rows().from();
        long to = Math.min(viewport.rows().to(), full.rowCount());
        if (to < from) {
            to = from;
        }

        List<Column> sliced =
                (from == 0 && to >= full.rowCount()) ? full.columns() : Slicing.slice(full.columns(), from, to);

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

    static void appendCell(ColumnStore dest, Column src, long globalRow) {
        long pos = 0;
        for (ColumnChunk chunk : src.chunks()) {
            if (globalRow < pos + chunk.size()) {
                int local = (int) (globalRow - pos);
                if (chunk.validity().isNull(local)) {
                    dest.appendNull();
                    return;
                }
                switch (chunk) {
                    case IntChunk ic -> ((IntColumnStore) dest).appendInt(ic.getInt(local));
                    case LongChunk lc -> ((LongColumnStore) dest).appendLong(lc.getLong(local));
                    case DoubleChunk dc -> ((DoubleColumnStore) dest).appendDouble(dc.getDouble(local));
                    case FloatChunk fc -> ((FloatColumnStore) dest).appendFloat(fc.getFloat(local));
                    case BooleanChunk bc -> ((BooleanColumnStore) dest).appendBoolean(bc.getBoolean(local));
                    case InstantChunk instantChunk ->
                            ((InstantColumnStore) dest).appendEpochNano(instantChunk.getEpochNano(local));
                    case StringChunk sc -> ((StringColumnStore) dest).appendString(sc.getString(local));
                    case DoubleArrayChunk dac -> {
                        int w = dac.elementsPerRow();
                        double[] row = new double[w];
                        for (int e = 0; e < w; e++) {
                            row[e] = dac.getDouble(local, e);
                        }
                        ((DoubleArrayColumnStore) dest).appendDoubleArray(row);
                    }
                    default -> dest.append(null);
                }
                return;
            }
            pos += chunk.size();
        }
        throw new IndexOutOfBoundsException("globalRow=" + globalRow + " size=" + src.size());
    }
}
