package io.columnar.bench;

import io.columnar.api.BaseTable;
import io.columnar.api.DataType;
import io.columnar.api.RowPredicate;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.core.DerivedTable;
import io.columnar.core.FilterOperator;
import io.columnar.core.HashAggregateOperator;
import io.columnar.core.HashAggregateOperator.AggKind;
import io.columnar.core.HashAggregateOperator.AggMeasure;
import io.columnar.core.Operator;
import io.columnar.core.OrderByOperator;
import io.columnar.core.ProjectOperator;
import io.columnar.core.RowPredicates;
import io.columnar.core.SourceOperator;
import io.columnar.query.CachedDerived;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Multi-operator pipeline throughput and caching benefit.
 *
 * Measures how well operators compose (filter → project → sort, filter → agg)
 * and the speedup from {@link CachedDerived} on repeated reads of unchanged data.
 *
 * Columns: id LONG, symbol STRING, price DOUBLE, qty INT
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class PipelineBench {

    @Param({"1000", "10000", "1000000"})
    public int rowCount;

    private DerivedTable filterProject;
    private DerivedTable filterProjectSort;
    private DerivedTable filterAgg;
    private CachedDerived cachedFilter;

    @Setup(Level.Trial)
    public void setup() {
        Schema schema = Schema.builder()
                .add("id",     DataType.LONG)
                .add("symbol", DataType.STRING)
                .add("price",  DataType.DOUBLE)
                .add("qty",    DataType.INT)
                .build();

        String[] symbols = {"AAPL","MSFT","GOOG","AMZN","META"};
        Random rnd = new Random(55);
        BaseTable t = Table.create(schema);
        for (long i = 0; i < rowCount; i++) {
            t.appendRow(i, symbols[(int)(i % symbols.length)], 100.0 + rnd.nextDouble() * 100.0, rnd.nextInt(500) + 1);
        }
        t.seal();

        RowPredicate priceFilter = RowPredicates.doubleGt("price", 150.0);

        // filter → project (drop qty, keep id/symbol/price)
        Operator filtered = new FilterOperator(new SourceOperator(t), priceFilter, "price-gt");
        filterProject = new DerivedTable(
                new ProjectOperator(filtered, List.of("id", "symbol", "price")));

        // filter → project → sort by price desc
        Operator filtered2 = new FilterOperator(new SourceOperator(t), priceFilter, "price-gt2");
        Operator projected2 = new ProjectOperator(filtered2, List.of("id", "symbol", "price"));
        filterProjectSort = new DerivedTable(new OrderByOperator(projected2, "price", false));

        // filter → groupBy symbol (count + sum qty)
        Operator filtered3 = new FilterOperator(new SourceOperator(t), priceFilter, "price-gt3");
        filterAgg = new DerivedTable(new HashAggregateOperator(filtered3, "symbol", List.of(
                new AggMeasure(AggKind.COUNT, null, "n"),
                new AggMeasure(AggKind.SUM_DOUBLE, "price", "total_price"))));

        // cached filter — second read should be served from cache
        Operator filtered4 = new FilterOperator(new SourceOperator(t), priceFilter, "price-gt-cached");
        cachedFilter = CachedDerived.of(filtered4);
        cachedFilter.table().read(); // prime the cache before measurement
    }

    /** filter → project (two operators). */
    @Benchmark public long filterProject()     { return filterProject.read().rowCount(); }

    /** filter → project → sort (three operators). */
    @Benchmark public long filterProjectSort() { return filterProjectSort.read().rowCount(); }

    /** filter → groupBy with COUNT + SUM (two operators, aggregation pass). */
    @Benchmark public long filterAgg()         { return filterAgg.read().rowCount(); }

    /** Cached filter re-read — should be near-zero cost after first read (version unchanged). */
    @Benchmark public long cachedRead()        { return cachedFilter.table().read().rowCount(); }
}
