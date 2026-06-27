package io.columnar.core;

import io.columnar.api.Column;
import io.columnar.api.Expr;
import io.columnar.api.BinaryOp;
import io.columnar.api.UnaryOp;
import io.columnar.api.ColumnChunk;
import io.columnar.api.Expr;
import io.columnar.api.BinaryOp;
import io.columnar.api.UnaryOp;
import io.columnar.api.DataType;
import io.columnar.api.Expr;
import io.columnar.api.BinaryOp;
import io.columnar.api.UnaryOp;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.Expr;
import io.columnar.api.BinaryOp;
import io.columnar.api.UnaryOp;
import io.columnar.api.chunk.HotDoubleChunk;
import io.columnar.api.Expr;
import io.columnar.api.BinaryOp;
import io.columnar.api.UnaryOp;

import java.util.Arrays;
import io.columnar.api.Expr;
import io.columnar.api.BinaryOp;
import io.columnar.api.UnaryOp;
import java.util.List;
import io.columnar.api.Expr;
import io.columnar.api.BinaryOp;
import io.columnar.api.UnaryOp;
import java.util.Set;
import io.columnar.api.Expr;
import io.columnar.api.BinaryOp;
import io.columnar.api.UnaryOp;

/**
 * Hand-specialized kernel for {@code col > literal}. Mirrors the HOT fast-path style used by
 * {@code io.columnar.core.RowPredicates}; kept here so {@code :expr} stays self-contained.
 *
 * <p>The class is deliberately non-final so ByteBuddy can generate tagged subclasses via
 * {@link ExprCodegen}.
 */
public class DoubleGtCompiled implements CompiledPredicate {

    private final String column;
    private final double threshold;

    public DoubleGtCompiled(String column, double threshold) {
        this.column = column;
        this.threshold = threshold;
    }

    public String column() {
        return column;
    }

    public double threshold() {
        return threshold;
    }

    @Override
    public Set<String> requiredColumns() {
        return Set.of(column);
    }

    @Override
    public void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits) {
        Arrays.fill(outBits, 0L);
        ColumnChunk chunk = cols.stream().filter(c -> c.name().equals(column)).findFirst().map(c -> c.chunk(chunkIdx)).orElseThrow();
        long[] valid = chunk.validity().words();
        if (chunk instanceof HotDoubleChunk hot) {
            double[] vals = hot.values();
            for (int i = 0; i < rowCount; i++) {
                if ((valid[i >>> 6] & (1L << (i & 63))) != 0 && vals[i] > threshold) {
                    outBits[i >>> 6] |= 1L << (i & 63);
                }
            }
            return;
        }
        DoubleChunk dc = (DoubleChunk) chunk;
        for (int i = 0; i < rowCount; i++) {
            if ((valid[i >>> 6] & (1L << (i & 63))) != 0 && dc.getDouble(i) > threshold) {
                outBits[i >>> 6] |= 1L << (i & 63);
            }
        }
    }

    /** Pattern recognized by {@link ExprCodegen}. */
    public static Expr pattern(String columnName, double threshold) {
        return new Expr.Binary(
                new Expr.ColRef(columnName), BinaryOp.GT, new Expr.Const(threshold, DataType.DOUBLE));
    }
}
