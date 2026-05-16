package io.columnar.expr;

import io.columnar.core.Column;
import io.columnar.core.Schema;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Baseline CompiledPredicate emitted when no ByteBuddy specialization matches. Mirrors
 * interpreter semantics chunk-by-chunk.
 */
public final class InterpreterCompiledPredicate implements CompiledPredicate {

    private final Schema schema;
    private final Expr expr;
    private final ExprInterpreter interpreter;
    private final Set<String> cols;

    public InterpreterCompiledPredicate(Schema schema, Expr expr) {
        this(schema, expr, new ExprInterpreter());
    }

    public InterpreterCompiledPredicate(Schema schema, Expr expr, ExprInterpreter interpreter) {
        this.schema = schema;
        this.expr = expr;
        this.interpreter = interpreter;
        this.cols = RequiredColumnCollector.collect(expr);
    }

    @Override
    public Set<String> requiredColumns() {
        return cols;
    }

    @Override
    public void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits) {
        Arrays.fill(outBits, 0L);
        if (cols.isEmpty() || rowCount == 0) {
            return;
        }
        long globalBase = chunkBaseGlobalRow(cols, chunkIdx);
        for (int i = 0; i < rowCount; i++) {
            if (!interpreter.evalPredicate(expr, cols, schema, globalBase + i)) {
                continue;
            }
            int bit = i;
            outBits[bit >>> 6] |= 1L << (bit & 63);
        }
    }

    private static long chunkBaseGlobalRow(List<Column> cols, int chunkIdx) {
        Column pivot = cols.get(0);
        long pos = 0;
        for (int ci = 0; ci < chunkIdx; ci++) {
            pos += pivot.chunk(ci).size();
        }
        return pos;
    }
}
