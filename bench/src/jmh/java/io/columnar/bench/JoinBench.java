package io.columnar.bench;

import io.columnar.api.BaseTable;
import io.columnar.api.DataType;
import io.columnar.api.JoinKind;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.core.DerivedTable;
import io.columnar.core.HashJoinOperator;
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
 * Hash-join throughput: probe side scales with {@code rowCount}, build side is fixed at 50 rows
 * (simulating a small dimension table — the common case).
 *
 * Probe: order_id LONG, symbol STRING, qty INT
 * Build: symbol STRING, name STRING   (50 distinct values)
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class JoinBench {

    @Param({"1000", "10000", "1000000"})
    public int rowCount;

    private Table probeTable;
    private Table buildTable;
    private DerivedTable innerJoin;
    private DerivedTable leftJoin;

    private static final int BUILD_SIZE     = 50;
    private static final int MATCH_SYMBOLS  = 40;  // 40 of 50 build rows match probe keys → 80 % hit rate

    @Setup(Level.Trial)
    public void setup() {
        String[] probeSymbols = new String[50];
        for (int i = 0; i < 50; i++) probeSymbols[i] = "SYM-" + i;

        // Build side: 50 symbols, all with names
        Schema buildSchema = Schema.builder()
                .add("symbol", DataType.STRING)
                .add("name",   DataType.STRING)
                .build();
        BaseTable build = Table.create(buildSchema);
        for (int i = 0; i < BUILD_SIZE; i++) {
            build.appendRow("SYM-" + i, "Company " + i);
        }
        build.seal();
        buildTable = build;

        // Probe side: rowCount orders, cycling through first MATCH_SYMBOLS symbols
        // (the remaining 10 symbols in build have no matching orders → left-join NULLs)
        Schema probeSchema = Schema.builder()
                .add("order_id", DataType.LONG)
                .add("symbol",   DataType.STRING)
                .add("qty",      DataType.INT)
                .build();
        Random rnd = new Random(13);
        BaseTable probe = Table.create(probeSchema);
        for (long i = 0; i < rowCount; i++) {
            probe.appendRow(i, probeSymbols[(int)(i % MATCH_SYMBOLS)], rnd.nextInt(100) + 1);
        }
        probe.seal();
        probeTable = probe;

        innerJoin = join(JoinKind.INNER);
        leftJoin  = join(JoinKind.LEFT);
    }

    private DerivedTable join(JoinKind kind) {
        return new DerivedTable(new HashJoinOperator(
                new SourceOperator(probeTable),
                new SourceOperator(buildTable),
                "symbol", "symbol", kind));
    }

    /** INNER join — only matched rows survive; build side hash-table stays hot in cache. */
    @Benchmark public long inner() { return innerJoin.read().rowCount(); }

    /** LEFT join — all probe rows survive; unmatched get NULL build columns. */
    @Benchmark public long left()  { return leftJoin.read().rowCount(); }
}
