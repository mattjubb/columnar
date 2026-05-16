package io.columnar.engine;

import io.columnar.core.Schema;
import io.columnar.expr.CompiledPredicate;
import io.columnar.expr.Expr;
import io.columnar.expr.ExprCodegen;

import java.util.Set;

/**
 * {@link RowPredicate} adapter over {@link io.columnar.expr} compiled predicates (interpreter-backed
 * or ByteBuddy-tagged kernels).
 */
public final class ExprRowPredicate implements RowPredicate {

    private final CompiledPredicate compiled;

    public ExprRowPredicate(Schema schema, Expr expr) {
        this.compiled = ExprCodegen.compilePredicate(schema, expr);
    }

    @Override
    public Set<String> requiredColumns() {
        return compiled.requiredColumns();
    }

    @Override
    public void evalChunk(
            java.util.List<io.columnar.core.Column> cols,
            int chunkIdx,
            int rowCount,
            long[] outBits) {
        compiled.evalChunk(cols, chunkIdx, rowCount, outBits);
    }
}
