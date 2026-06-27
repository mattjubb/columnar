package io.columnar.core;
import io.columnar.api.Column;
import io.columnar.api.RowPredicate;

import io.columnar.api.Schema;
import io.columnar.core.CompiledPredicate;
import io.columnar.api.Expr;
import io.columnar.core.ExprCodegen;

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
            java.util.List<io.columnar.api.Column> cols,
            int chunkIdx,
            int rowCount,
            long[] outBits) {
        compiled.evalChunk(cols, chunkIdx, rowCount, outBits);
    }
}
