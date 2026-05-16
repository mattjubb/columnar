package io.columnar.bench;

import io.columnar.api.Columnar;
import io.columnar.core.BaseTable;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.DataType;
import io.columnar.core.Schema;
import io.columnar.core.Table;
import io.columnar.core.Viewport;
import io.columnar.expr.DoubleGtCompiled;
import io.columnar.expr.Expr;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH scaffolding that exercises filter-heavy pull pipelines. Additional suites (cold vs cached
 * pull contexts, codegen vs interpreter) can fork this pattern.
 */

@Warmup(iterations = 3, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@SuppressWarnings({"NotNullFieldNotInitialized", "FieldCanBeLocal"})
public class OperatorJmhBench {

    @State(Scope.Thread)
    public static class Harness {
        DerivedLike derived;
        io.columnar.query.CachedDerived cached;

        @Setup
        public void prepare() {
            Schema schema =
                    Schema.builder()
                            .add("id", DataType.LONG)
                            .add("symbol", DataType.STRING)
                            .add("qty", DataType.INT)
                            .add("price", DataType.DOUBLE)
                            .build();

            Random rnd = new Random(17L);
            BaseTable tbl = Table.create(schema);
            for (long i = 0; i < 2000; i++) {
                tbl.appendRow(
                        i,
                        rnd.nextBoolean() ? "AAPL" : "MSFT",
                        rnd.nextInt(20) + 1,
                        100.0 + rnd.nextDouble() * 50.0);
            }

            Expr pred = DoubleGtCompiled.pattern("price", 119.99);
            Table source = tbl;
            this.derived = new DerivedLike(source, pred);

            cached = Columnar.from(source).filterCached(pred, "jmh-hot-cache");
            // Force one warm-read so bytecode + caches are exercised before measurement window.
            cached.table().read(Viewport.ALL);
        }
    }

    /** Minimal holder so Bench module does not need to instantiate inner types reflectively. */
    static final class DerivedLike extends io.columnar.engine.DerivedTable {
        DerivedLike(Table t, Expr e) {
            super(
                    new io.columnar.engine.FilterOperator(
                            new io.columnar.engine.SourceOperator(t),
                            new io.columnar.engine.ExprRowPredicate(t.schema(), e),
                            "jmh-derived"));
        }
    }

    @Benchmark
    public long baselineFilterThroughput(Harness state) {
        ColumnarSlice slice = state.derived.read(Viewport.ALL);
        return slice.rowCount();
    }

    @Benchmark
    public long cachedPullThroughput(Harness state) {
        ColumnarSlice slice = state.cached.table().read(Viewport.ALL);
        return slice.rowCount();
    }
}
