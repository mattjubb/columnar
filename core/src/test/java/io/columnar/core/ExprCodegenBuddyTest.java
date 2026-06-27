package io.columnar.core;
import io.columnar.api.BinaryOp;
import io.columnar.api.Expr;

import io.columnar.api.DataType;
import io.columnar.api.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExprCodegenBuddyTest {

    @Test
    void doubleGtBuddySubclassIsMarked() {
        Schema schema = Schema.builder().add("price", DataType.DOUBLE).build();
        Expr expr =
                new Expr.Binary(
                        new Expr.ColRef("price"),
                        BinaryOp.GT,
                        new Expr.Const(42.5, DataType.DOUBLE));

        CompiledPredicate compiled = ExprCodegen.compilePredicate(schema, expr);

        assertThat(BuddyMarker.class.isAssignableFrom(compiled.getClass())).isTrue();

        CompiledPredicate interpretedOnly =
                ExprCodegen.compilePredicate(
                        schema,
                        new Expr.Binary(
                                new Expr.ColRef("price"),
                                BinaryOp.LT,
                                new Expr.Const(1.0, DataType.DOUBLE)));

        assertThat(BuddyMarker.class.isAssignableFrom(interpretedOnly.getClass())).isFalse();
    }
}
