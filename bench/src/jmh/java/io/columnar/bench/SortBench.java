package io.columnar.bench;

import io.columnar.api.BaseTable;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.core.DerivedTable;
import io.columnar.core.OrderByOperator;
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

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Sort throughput on DOUBLE and LONG key columns.
 * Data is randomly shuffled so the sort is never a no-op.
 *
 * Columns: id LONG, price DOUBLE
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class SortBench {

    @Param({"1000", "10000", "1000000"})
    public int rowCount;

    private Table table;
    private DerivedTable sortByPriceAsc;
    private DerivedTable sortByPriceDesc;
    private DerivedTable sortByIdAsc;

    @Setup(Level.Trial)
    public void setup() {
        Schema schema = Schema.builder()
                .add("id",    DataType.LONG)
                .add("price", DataType.DOUBLE)
                .build();

        Random rnd = new Random(99);
        BaseTable t = Table.create(schema);
        for (long i = 0; i < rowCount; i++) {
            t.appendRow(rnd.nextLong(rowCount), 100.0 + rnd.nextDouble() * 900.0);
        }
        t.seal();
        table = t;

        sortByPriceAsc  = sort("price", true);
        sortByPriceDesc = sort("price", false);
        sortByIdAsc     = sort("id",    true);
    }

    private DerivedTable sort(String col, boolean asc) {
        return new DerivedTable(new OrderByOperator(new SourceOperator(table), col, asc));
    }

    /** Sort DOUBLE column ascending — typical analytical sort path. */
    @Benchmark public long priceAsc()  { return sortByPriceAsc.read().rowCount(); }

    /** Sort DOUBLE column descending. */
    @Benchmark public long priceDesc() { return sortByPriceDesc.read().rowCount(); }

    /** Sort LONG column ascending — integer comparator path. */
    @Benchmark public long idAsc()     { return sortByIdAsc.read().rowCount(); }
}
