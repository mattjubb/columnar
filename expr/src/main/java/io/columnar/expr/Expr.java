package io.columnar.expr;

import io.columnar.core.DataType;

import java.util.List;

/**
 * Minimal expression DAG for codegen + interpreter tests. Booleans emerge from comparisons /
 * logical connectives; aggregates are delegated to operators, not modeled here yet.
 */
public sealed interface Expr permits Expr.ColRef, Expr.Const, Expr.Unary, Expr.Binary, Expr.Call {

    record ColRef(String name) implements Expr {}

    /** Boxed JVM value annotated with logical {@link DataType}. */
    record Const(Object value, DataType type) implements Expr {}

    record Unary(UnaryOp op, Expr child) implements Expr {}

    record Binary(Expr left, BinaryOp op, Expr right) implements Expr {}

    record Call(String name, List<Expr> args) implements Expr {}
}
