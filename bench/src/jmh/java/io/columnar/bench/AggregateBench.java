package io.columnar.bench;

import io.columnar.api.BaseTable;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.core.DerivedTable;
import io.columnar.core.HashAggregateOperator;
import io.columnar.core.HashAggregateOperator.AggKind;
import io.columnar.core.HashAggregateOperator.AggMeasure;
import io.columnar.core.SourceOperator;
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
 * GROUP BY aggregation throughput.
 *
 * Columns: symbol STRING (10 distinct values), amount DOUBLE
 * Each benchmark groups all rows by symbol and computes COUNT and/or SUM.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class AggregateBench {

    @Param({"1000", "10000", "1000000"})
    public int rowCount;

    private Table table;
    private DerivedTable countOnly;
    private DerivedTable sumOnly;
    private DerivedTable countAndSum;

    @Setup(Level.Trial)
    public void setup() {
        Schema schema = Schema.builder()
                .add("symbol", DataType.STRING)
                .add("amount", DataType.DOUBLE)
                .build();

        String[] symbols = {"AAPL","MSFT","GOOG","AMZN","META","TSLA","NVDA","NFLX","UBER","LYFT"};
        Random rnd = new Random(7);
        BaseTable t = Table.create(schema);
        for (int i = 0; i < rowCount; i++) {
            t.appendRow(symbols[i % symbols.length], 10.0 + rnd.nextDouble() * 990.0);
        }
        t.seal();
        table = t;

        countOnly   = agg(new AggMeasure(AggKind.COUNT, null, "n"));
        sumOnly     = agg(new AggMeasure(AggKind.SUM_DOUBLE, "amount", "total"));
        countAndSum = agg(
                new AggMeasure(AggKind.COUNT, null, "n"),
                new AggMeasure(AggKind.SUM_DOUBLE, "amount", "total"));
    }

    private DerivedTable agg(AggMeasure... measures) {
        return new DerivedTable(new HashAggregateOperator(
                new SourceOperator(table), "symbol", List.of(measures)));
    }

    /** COUNT(*) per group — cheapest aggregate. */
    @Benchmark public long count()      { return countOnly.read().rowCount(); }

    /** SUM(amount) per group — DOUBLE accumulation. */
    @Benchmark public long sum()        { return sumOnly.read().rowCount(); }

    /** COUNT + SUM together — two passes over column in one scan. */
    @Benchmark public long countAndSum(){ return countAndSum.read().rowCount(); }
}
