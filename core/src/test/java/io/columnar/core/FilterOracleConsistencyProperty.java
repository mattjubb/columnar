package io.columnar.core;

import io.columnar.api.BaseTable;
import io.columnar.api.Column;
import io.columnar.api.ColumnChunk;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.core.DoubleGtCompiled;
import io.columnar.api.Expr;
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
