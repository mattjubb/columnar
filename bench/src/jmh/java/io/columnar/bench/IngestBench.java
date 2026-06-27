package io.columnar.bench;

import io.columnar.api.BaseTable;
import io.columnar.api.DataType;
import io.columnar.api.RowAppender;
import io.columnar.api.Schema;
import io.columnar.api.Table;
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

import java.util.concurrent.TimeUnit;

/**
 * Row-ingestion throughput: how fast can rows be appended to a live table?
 *
 * Compares two paths:
 * <ul>
 *   <li>{@code appendRow(Object...)} — varargs boxing, convenient</li>
 *   <li>{@link RowAppender} typed setters — zero boxing on primitive columns</li>
 * </ul>
 *
 * Schema: id LONG, price DOUBLE, qty INT (three columns, primitives only)
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class IngestBench {

    @Param({"1000", "10000", "1000000"})
    public int rowCount;

    private Schema schema;

    @Setup(Level.Trial)
    public void setup() {
        schema = Schema.builder()
                .add("id",    DataType.LONG)
                .add("price", DataType.DOUBLE)
                .add("qty",   DataType.INT)
                .build();
    }

    /**
     * Boxed varargs path — convenient but allocates an Object[] per row and boxes each primitive.
     * Baseline for comparison.
     */
    @Benchmark
    public long boxedAppendRow() {
        BaseTable t = Table.create(schema);
        for (long i = 0; i < rowCount; i++) {
            t.appendRow(i, i * 1.5, (int)(i % 1000));
        }
        return t.size();
    }

    /**
     * Typed {@link RowAppender} path — no boxing on LONG/DOUBLE/INT columns.
     * Expected to be faster than boxed on hot loops.
     */
    @Benchmark
    public long typedRowAppender() {
        BaseTable t = Table.create(schema);
        for (long i = 0; i < rowCount; i++) {
            t.row()
             .setLong(0, i)
             .setDouble(1, i * 1.5)
             .setInt(2, (int)(i % 1000))
             .commit();
        }
        return t.size();
    }

    /**
     * Typed path using the builder (sealed table) — extra version reset overhead at end,
     * but same typed path as live table.
     */
    @Benchmark
    public long builderTyped() {
        BaseTable.Builder b = Table.builder(schema);
        for (long i = 0; i < rowCount; i++) {
            b.row()
             .setLong(0, i)
             .setDouble(1, i * 1.5)
             .setInt(2, (int)(i % 1000))
             .commit();
        }
        return b.build().size();
    }
}
