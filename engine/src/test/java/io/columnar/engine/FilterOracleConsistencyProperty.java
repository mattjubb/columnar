package io.columnar.engine;

import io.columnar.core.BaseTable;
import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.DataType;
import io.columnar.core.Schema;
import io.columnar.core.Table;
import io.columnar.core.Viewport;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.expr.DoubleGtCompiled;
import io.columnar.expr.Expr;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Randomized regression between interpreter/codegen predicates and brute-force oracle counting. */

class FilterOracleConsistencyProperty {

    private static Schema schema() {
        return Schema.builder().add("id", DataType.LONG).add("price", DataType.DOUBLE).build();
    }

    @Property
    void handwrittenDoubleGtMatchesExprPipeline(@ForAll long seed) {
        double threshold = 42.73;
        Random rnd = new Random(seed);
        BaseTable tbl = Table.create(schema());
        int rows = 1 + Math.abs(rnd.nextInt() % 37);
        for (long i = 0; i < rows; i++) {
            double price = -50.0 + rnd.nextDouble() * 550.0;
            tbl.appendRow(i, price);
        }

        Expr expr = DoubleGtCompiled.pattern("price", threshold);

        DerivedTable scripted =
                new DerivedTable(
                        new FilterOperator(
                                new SourceOperator(tbl), RowPredicates.doubleGt("price", threshold), "hand"));
        DerivedTable exprPipeline =
                new DerivedTable(
                        new FilterOperator(
                                new SourceOperator(tbl), new ExprRowPredicate(tbl.schema(), expr), "expr"));

        long brute = bruteCount(tbl.read(Viewport.ALL), threshold);
        assertThat(scripted.read(Viewport.ALL).rowCount()).isEqualTo(brute);
        assertThat(exprPipeline.read(Viewport.ALL).rowCount()).isEqualTo(brute);
    }

    private static long bruteCount(ColumnarSlice slice, double threshold) {
        Column price = slice.column("price");
        long hits = 0L;
        for (ColumnChunk chunk : price.chunks()) {
            DoubleChunk dc = (DoubleChunk) chunk;
            int rows = chunk.size();
            for (int row = 0; row < rows; row++) {
                if (!chunk.validity().isValid(row)) {
                    continue;
                }
                if (dc.getDouble(row) > threshold) {
                    hits++;
                }
            }
        }
        return hits;
    }
}
