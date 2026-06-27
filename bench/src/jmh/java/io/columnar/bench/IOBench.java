package io.columnar.bench;

import io.columnar.api.BaseTable;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.io.Format;
import io.columnar.api.io.TableIO;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * File I/O throughput for CSV, Arrow IPC, and Parquet.
 *
 * Read benchmarks measure deserialization (file pre-written in setup).
 * Write benchmarks create a fresh temp file each invocation and delete it after.
 *
 * Row sizes top out at 100 000 to keep individual benchmark iterations under ~1 s.
 *
 * Schema: id LONG, label STRING, value DOUBLE, flag BOOLEAN
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsPrepend = "--add-opens=java.base/java.nio=ALL-UNNAMED")
public class IOBench {

    @Param({"1000", "10000", "100000"})
    public int rowCount;

    private Table table;
    private Schema schema;

    private Path csvFile;
    private Path arrowFile;
    private Path parquetFile;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        schema = Schema.builder()
                .add("id",    DataType.LONG)
                .add("label", DataType.STRING)
                .add("value", DataType.DOUBLE)
                .add("flag",  DataType.BOOLEAN)
                .build();

        String[] labels = {"alpha","beta","gamma","delta","epsilon"};
        Random rnd = new Random(3);
        BaseTable t = Table.create(schema);
        for (long i = 0; i < rowCount; i++) {
            t.appendRow(i, labels[(int)(i % labels.length)], rnd.nextDouble() * 1000.0, i % 2 == 0);
        }
        t.seal();
        table = t;

        // Pre-write files so read benchmarks only measure deserialization
        csvFile     = Files.createTempFile("bench-", ".csv");
        arrowFile   = Files.createTempFile("bench-", ".arrow");
        parquetFile = Files.createTempFile("bench-", ".parquet");

        TableIO.write(table, Format.CSV,     csvFile);
        TableIO.write(table, Format.ARROW,   arrowFile);
        TableIO.write(table, Format.PARQUET, parquetFile);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        Files.deleteIfExists(csvFile);
        Files.deleteIfExists(arrowFile);
        Files.deleteIfExists(parquetFile);
    }

    // ---- read benchmarks (file already on disk) --------------------------------

    @Benchmark public long readCsv()     throws IOException { return TableIO.read(Format.CSV,     csvFile).size(); }
    @Benchmark public long readArrow()   throws IOException { return TableIO.read(Format.ARROW,   arrowFile).size(); }
    @Benchmark public long readParquet() throws IOException { return TableIO.read(Format.PARQUET, parquetFile).size(); }

    // ---- write benchmarks (write + delete per invocation) ----------------------

    @Benchmark
    public long writeCsv() throws IOException {
        Path tmp = Files.createTempFile("bw-", ".csv");
        try {
            TableIO.write(table, Format.CSV, tmp);
            return Files.size(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Benchmark
    public long writeArrow() throws IOException {
        Path tmp = Files.createTempFile("bw-", ".arrow");
        try {
            TableIO.write(table, Format.ARROW, tmp);
            return Files.size(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Benchmark
    public long writeParquet() throws IOException {
        Path tmp = Files.createTempFile("bw-", ".parquet");
        try {
            TableIO.write(table, Format.PARQUET, tmp);
            return Files.size(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
