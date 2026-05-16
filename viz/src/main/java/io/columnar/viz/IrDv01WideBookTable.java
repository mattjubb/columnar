package io.columnar.viz;

import io.columnar.core.BasicSubscription;
import io.columnar.core.Column;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.DataType;
import io.columnar.core.RowRange;
import io.columnar.core.Schema;
import io.columnar.core.Subscription;
import io.columnar.core.Table;
import io.columnar.core.Viewport;
import io.columnar.core.chunk.HotDoubleChunk;
import io.columnar.core.chunk.HotStringChunk;
import io.columnar.core.chunk.StringDictionary;
import io.columnar.core.Validity;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Mutable, already-pivoted IR DV01 book: STRING {@code trades}, STRING {@code book}
 * ({@linkplain #BOOK_COUNT} desks), plus one DOUBLE column per tenor bucket.
 *
 * <p>{@link #applyGlobalTick(RandomGenerator)} shocks every DV01 cell. {@link #read(Viewport)}
 * materializes only the requested row/column window — used with the AG Grid infinite
 * datasource {@code /api/rows} endpoint.
 */
public final class IrDv01WideBookTable implements Table {

    private static final Logger log = LoggerFactory.getLogger(IrDv01WideBookTable.class);

    /** Number of distinct book labels; each trade belongs to exactly one book. */
    public static final int BOOK_COUNT = 10;

    private static final String[] BOOK_LABELS = buildBookLabels();

    private final Schema schema;
    private final Schema aggregateSchema;
    private final List<String> bucketOrder;
    private final Object2IntOpenHashMap<String> bucketIndexByName;
    private final String[] tradeIds;
    /** Trade {@code t} rolls up to {@code bookIndexForTrade[t] & 0xff} ∈ {@code [0, BOOK_COUNT)}. */
    private final byte[] bookIndexForTrade;
    private final double[][] dv01ByTrade;
    /** Row {@code bi} sums every trade in {@code BOOK_LABELS[bi]}; recomputed whenever DV01 moves. */
    private final double[][] bookDv01Aggregate;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile long version;
    private final List<Consumer<Long>> listeners = new CopyOnWriteArrayList<>();

    public IrDv01WideBookTable(List<String> bucketLabels, int trades, RandomGenerator rng, String tradePrefix) {
        Objects.requireNonNull(bucketLabels, "bucketLabels");
        Objects.requireNonNull(rng, "rng");
        if (bucketLabels.isEmpty()) {
            throw new IllegalArgumentException("bucketLabels empty");
        }
        Objects.requireNonNull(tradePrefix, "tradePrefix");
        if (tradePrefix.isEmpty()) {
            throw new IllegalArgumentException("tradePrefix blank");
        }
        if (trades < 1) {
            throw new IllegalArgumentException("trades: " + trades);
        }
        if (BOOK_COUNT > 127) {
            throw new IllegalStateException("book index must fit in signed byte bookkeeping");
        }
        this.bucketOrder = List.copyOf(bucketLabels);
        this.bucketIndexByName = new Object2IntOpenHashMap<>(bucketLabels.size());
        this.bucketIndexByName.defaultReturnValue(-1);
        for (int i = 0; i < bucketLabels.size(); i++) {
            bucketIndexByName.put(bucketLabels.get(i), i);
        }
        this.schema = schemaForBuckets(bucketLabels);
        this.aggregateSchema = aggregateSchemaForBuckets(bucketLabels);
        this.tradeIds = new String[trades];
        this.bookIndexForTrade = new byte[trades];
        this.dv01ByTrade = new double[trades][bucketLabels.size()];
        this.bookDv01Aggregate = new double[BOOK_COUNT][bucketLabels.size()];

        for (int t = 0; t < trades; t++) {
            tradeIds[t] = tradePrefix + t;
            bookIndexForTrade[t] = (byte)(t % BOOK_COUNT);
            for (int b = 0; b < bucketLabels.size(); b++) {
                dv01ByTrade[t][b] = (rng.nextDouble() * 2 - 1) * 25_000;
            }
        }
        recomputeBookAggregatesLocked();
    }

    /** Schema served when the viz layer requests book-level rollup rows ({@linkplain #BOOK_COUNT}). */
    public Schema aggregateSchema() {
        return aggregateSchema;
    }

    private static String[] buildBookLabels() {
        String[] out = new String[BOOK_COUNT];
        for (int i = 0; i < BOOK_COUNT; i++) {
            out[i] = "BOOK_" + i;
        }
        return out;
    }

    private static Schema schemaForBuckets(List<String> buckets) {
        Schema.Builder sb = Schema.builder().add("trades", DataType.STRING).add("book", DataType.STRING);
        for (String b : buckets) {
            sb.add(b, DataType.DOUBLE);
        }
        return sb.build();
    }

    private static Schema aggregateSchemaForBuckets(List<String> buckets) {
        Schema.Builder sb = Schema.builder().add("book", DataType.STRING);
        for (String b : buckets) {
            sb.add(b, DataType.DOUBLE);
        }
        return sb.build();
    }

    /** Gaussian shock on every trade × bucket DV01 cell; bumps {@link #version()}. */
    public void applyGlobalTick(RandomGenerator rng) {
        lock.writeLock().lock();
        try {
            double scale = 400.0;
            for (int t = 0; t < tradeIds.length; t++) {
                for (int b = 0; b < bucketOrder.size(); b++) {
                    dv01ByTrade[t][b] += rng.nextGaussian() * scale;
                }
            }
            recomputeBookAggregatesLocked();
            version++;
            long snap = version;
            int listenerCount = listeners.size();
            log.debug("applyGlobalTick: shocked {} trades × {} buckets -> version={}, listeners registered={}",
                    tradeIds.length, bucketOrder.size(), snap, listenerCount);
        } finally {
            lock.writeLock().unlock();
        }
        notifyListeners();
    }

    /** Like {@link io.columnar.core.BaseTable#addListener(Consumer)} — for SSE invalidations. */
    public void addListener(Consumer<Long> listener) {
        lock.writeLock().lock();
        try {
            listeners.add(listener);
            log.info("listener registered; total={}, current version={}", listeners.size(), version);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeListener(Consumer<Long> listener) {
        lock.writeLock().lock();
        try {
            listeners.remove(listener);
            log.info("listener removed; remaining={}, version={}", listeners.size(), version);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void notifyListeners() {
        long v = version;
        int n = listeners.size();
        if (n == 0) {
            log.warn("notifyListeners(version={}) but no listeners registered — browser /stream may not be connected",
                    v);
        } else {
            log.debug("notifyListeners(version={}), delivering to {} subscriber(s)", v, n);
        }
        for (Consumer<Long> l : listeners) {
            try {
                l.accept(v);
            } catch (RuntimeException ex) {
                log.warn("listener failed for version {}", v, ex);
            }
        }
    }

    private void recomputeBookAggregatesLocked() {
        for (double[] row : bookDv01Aggregate) {
            Arrays.fill(row, 0.0);
        }
        for (int t = 0; t < tradeIds.length; t++) {
            int bi = bookIndexForTrade[t] & 0xFF;
            for (int b = 0; b < bucketOrder.size(); b++) {
                bookDv01Aggregate[bi][b] += dv01ByTrade[t][b];
            }
        }
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public long size() {
        return tradeIds.length;
    }

    /**
     * Logical row count for server-side book aggregation (one row per distinct book).
     * {@link #readAggregatedByBook(Viewport)} materializes DV01 summed across trades in each book —
     * the {@code trades} identifier is intentionally omitted because it cannot be meaningfully summed.
     */
    public long aggregateRowCount() {
        return BOOK_COUNT;
    }

    /**
     * Read a window over the {@linkplain #BOOK_COUNT} book rollup rows ({@linkplain #aggregateSchema()},
     * no {@code trades} column).
     */
    public ColumnarSlice readAggregatedByBook(Viewport viewport) {
        Objects.requireNonNull(viewport);
        RowRange requested = viewport.rows().intersect(RowRange.head(BOOK_COUNT));
        long from = requested.from();
        long end = requested.to();
        if (viewport.hasLimit()) {
            long lim = viewport.limit().getAsLong();
            long span = end - from;
            if (lim < span) {
                end = from + Math.max(lim, 0);
            }
        }
        int n = (int) Math.max(end - from, 0);

        Schema outSchema =
                viewport.columns().isPresent()
                        ? aggregateSchema.select(new ArrayList<>(viewport.columns().get()))
                        : aggregateSchema;

        lock.readLock().lock();
        try {
            long vers = version;
            List<Column> columns = new ArrayList<>(outSchema.size());
            StringDictionary dict = new StringDictionary();

            for (Schema.Field f : outSchema.fields()) {
                if ("book".equals(f.name())) {
                    int[] codes = new int[n];
                    for (int i = 0; i < n; i++) {
                        int bookIdx = (int) (from + i);
                        codes[i] = dict.intern(BOOK_LABELS[bookIdx]);
                    }
                    columns.add(
                            Column.of(
                                    "book",
                                    DataType.STRING,
                                    List.of(
                                            new HotStringChunk(
                                                    codes,
                                                    n,
                                                    Validity.allValid(n),
                                                    dict))));
                    continue;
                }
                int bIdx = bucketIndexByName.getInt(f.name());
                if (bIdx < 0) {
                    throw new IllegalArgumentException("unknown column on book aggregate: " + f.name());
                }
                double[] vals = new double[n];
                for (int i = 0; i < n; i++) {
                    int bookIdx = (int) (from + i);
                    vals[i] = bookDv01Aggregate[bookIdx][bIdx];
                }
                columns.add(Column.of(f.name(), DataType.DOUBLE, List.of(HotDoubleChunk.of(vals))));
            }

            return new ColumnarSlice(outSchema, columns, n, vers);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ColumnarSlice read(Viewport viewport) {
        Objects.requireNonNull(viewport);
        RowRange requested = viewport.rows().intersect(RowRange.head(tradeIds.length));
        long from = requested.from();
        long end = requested.to(); // exclusive, capped ≤ tradeIds.length
        if (viewport.hasLimit()) {
            long lim = viewport.limit().getAsLong();
            long span = end - from;
            if (lim < span) {
                end = from + Math.max(lim, 0);
            }
        }
        int n = (int) Math.max(end - from, 0);

        Schema outSchema =
                viewport.columns().isPresent()
                        ? schema.select(new ArrayList<>(viewport.columns().get()))
                        : schema;

        lock.readLock().lock();
        try {
            long vers = version;
            List<Column> columns = new ArrayList<>(outSchema.size());
            StringDictionary dict = new StringDictionary();

            for (Schema.Field f : outSchema.fields()) {
                if ("trades".equals(f.name())) {
                    int[] codes = new int[n];
                    for (int i = 0; i < n; i++) {
                        codes[i] = dict.intern(tradeIds[(int) (from + i)]);
                    }
                    columns.add(
                            Column.of(
                                    "trades",
                                    DataType.STRING,
                                    List.of(
                                            new HotStringChunk(
                                                    codes,
                                                    n,
                                                    Validity.allValid(n),
                                                    dict))));
                    continue;
                }
                if ("book".equals(f.name())) {
                    int[] codes = new int[n];
                    for (int i = 0; i < n; i++) {
                        int tIdx = (int) (from + i);
                        codes[i] = dict.intern(BOOK_LABELS[bookIndexForTrade[tIdx] & 0xFF]);
                    }
                    columns.add(
                            Column.of(
                                    "book",
                                    DataType.STRING,
                                    List.of(
                                            new HotStringChunk(
                                                    codes,
                                                    n,
                                                    Validity.allValid(n),
                                                    dict))));
                    continue;
                }
                int bIdx = bucketIndexByName.getInt(f.name());
                if (bIdx < 0) {
                    throw new IllegalArgumentException("unknown column on wide book: " + f.name());
                }
                double[] vals = new double[n];
                for (int i = 0; i < n; i++) {
                    vals[i] = dv01ByTrade[(int) (from + i)][bIdx];
                }
                columns.add(
                        Column.of(f.name(), DataType.DOUBLE, List.of(HotDoubleChunk.of(vals))));
            }

            return new ColumnarSlice(outSchema, columns, n, vers);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Subscription subscribe(Viewport viewport, Consumer<ColumnarSlice> onChange) {
        BasicSubscription[] holder = new BasicSubscription[1];
        Consumer<Long> listener =
                v -> {
                    BasicSubscription s = holder[0];
                    if (s != null && s.isActive()) {
                        onChange.accept(read(s.viewport()));
                    }
                };
        addListener(listener);
        BasicSubscription sub =
                new BasicSubscription(
                        viewport,
                        newVp -> onChange.accept(read(newVp)),
                        () -> removeListener(listener));
        holder[0] = sub;
        onChange.accept(read(viewport));
        return sub;
    }
}
