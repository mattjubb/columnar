package io.columnar.bench;

import io.columnar.api.BaseTable;
import io.columnar.api.BinaryOp;
import io.columnar.api.DataType;
import io.columnar.api.Expr;
import io.columnar.api.RowPredicate;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.core.DerivedTable;
import io.columnar.core.ExprRowPredicate;
import io.columnar.core.FilterOperator;
import io.columnar.core.RowPredicates;
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
 * Filter throughput across predicate types.
 * ~50 % of rows pass each predicate to avoid branch-prediction dominance.
 *
 * Columns: id LONG, symbol STRING (5 values), price DOUBLE [100–200]
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class FilterBench {

    @Param({"1000", "10000", "1000000"})
    public int rowCount;

    private Table table;
    private Schema schema;

    // pre-built derived tables so benchmark measures execution, not operator construction
    private DerivedTable longGtTable;
    private DerivedTable doubleGtTable;
    private DerivedTable stringEqTable;
    private DerivedTable exprCompiledTable;
    private DerivedTable compoundAndTable;

    @Setup(Level.Trial)
    public void setup() {
        schema = Schema.builder()
                .add("id",     DataType.LONG)
                .add("symbol", DataType.STRING)
                .add("price",  DataType.DOUBLE)
                .build();

        String[] symbols = {"AAPL", "MSFT", "GOOG", "AMZN", "META"};
        Random rnd = new Random(42);
        BaseTable t = Table.create(schema);
        for (long i = 0; i < rowCount; i++) {
            t.appendRow(i, symbols[(int)(i % symbols.length)], 100.0 + rnd.nextDouble() * 100.0);
        }
        t.seal();
        table = t;

        longGtTable    = derived(RowPredicates.longGt("id", rowCount / 2L),          "id-gt");
        doubleGtTable  = derived(RowPredicates.doubleGt("price", 150.0),              "price-gt");
        stringEqTable  = derived(RowPredicates.stringEq("symbol", "AAPL"),            "sym-eq");
        compoundAndTable = derived(RowPredicates.and(
                RowPredicates.doubleGt("price", 130.0),
                RowPredicates.stringEq("symbol", "AAPL")), "compound");

        Expr priceGt = new Expr.Binary(
                new Expr.ColRef("price"), BinaryOp.GT, new Expr.Const(150.0, DataType.DOUBLE));
        exprCompiledTable = derived(new ExprRowPredicate(schema, priceGt), "expr-price-gt");
    }

    private DerivedTable derived(RowPredicate pred, String hint) {
        return new DerivedTable(new FilterOperator(new SourceOperator(table), pred, hint));
    }

    /** LONG column predicate — fastest path, no boxing. */
    @Benchmark public long longGt()       { return longGtTable.read().rowCount(); }

    /** DOUBLE column predicate. */
    @Benchmark public long doubleGt()     { return doubleGtTable.read().rowCount(); }

    /** Dictionary-encoded STRING equality — resolves code then compares. */
    @Benchmark public long stringEq()     { return stringEqTable.read().rowCount(); }

    /** Compiled expression predicate (ByteBuddy codegen path). */
    @Benchmark public long exprCompiled() { return exprCompiledTable.read().rowCount(); }

    /** AND of two predicates — short-circuit bitmap intersection. */
    @Benchmark public long compoundAnd()  { return compoundAndTable.read().rowCount(); }
}
