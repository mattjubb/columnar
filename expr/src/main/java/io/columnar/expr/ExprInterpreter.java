package io.columnar.expr;

import io.columnar.core.Column;
import io.columnar.core.DataType;
import io.columnar.core.Schema;

import java.util.List;

/**
 * Row-wise interpreter baseline for predicates and scalar expressions. Comparisons involving
 * null operands evaluate to {@code false} for predicate mode.
 */
public final class ExprInterpreter {

    public ExprInterpreter() {}

    public boolean evalPredicate(Expr expr, List<Column> columns, Schema schema, long globalRow) {
        return switch (expr) {
            case Expr.Binary b ->
                    switch (b.op()) {
                        case EQ -> cmpEq(columns, schema, globalRow, b.left(), b.right());
                        case NE -> !cmpEq(columns, schema, globalRow, b.left(), b.right());
                        case LT -> cmpOrdered(columns, schema, globalRow, b.left(), b.right(), BinaryOp.LT);
                        case LE -> cmpOrdered(columns, schema, globalRow, b.left(), b.right(), BinaryOp.LE);
                        case GT -> cmpOrdered(columns, schema, globalRow, b.left(), b.right(), BinaryOp.GT);
                        case GE -> cmpOrdered(columns, schema, globalRow, b.left(), b.right(), BinaryOp.GE);
                        case AND ->
                                evalPredicate(b.left(), columns, schema, globalRow)
                                        && evalPredicate(b.right(), columns, schema, globalRow);
                        case OR ->
                                evalPredicate(b.left(), columns, schema, globalRow)
                                        || evalPredicate(b.right(), columns, schema, globalRow);
                        default ->
                                throw new UnsupportedOperationException(
                                        "binary op " + b.op() + " not in predicate mode");
                    };
            case Expr.Unary u ->
                    switch (u.op()) {
                        case NOT -> !evalPredicate(u.child(), columns, schema, globalRow);
                        default ->
                                throw new UnsupportedOperationException(
                                        "unary " + u.op() + " not supported for booleans");
                    };
            default -> throw new UnsupportedOperationException("expression is not boolean-typed");
        };
    }

    private boolean cmpOrdered(
            List<Column> columns,
            Schema schema,
            long row,
            Expr left,
            Expr right,
            BinaryOp op) {
        double lv = cmpDouble(columns, schema, row, left);
        double rv = cmpDouble(columns, schema, row, right);
        if (Double.isNaN(lv) || Double.isNaN(rv)) {
            return false;
        }
        int c = Double.compare(lv, rv);
        return switch (op) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            default -> throw new IllegalArgumentException();
        };
    }

    public double evalDouble(Expr expr, List<Column> columns, Schema schema, long globalRow) {
        return switch (expr) {
            case Expr.ColRef c -> resolveDouble(columns, schema, globalRow, c.name());
            case Expr.Const k -> numericConst(k);
            case Expr.Unary u -> {
                if (u.op() != UnaryOp.NEG) {
                    throw new UnsupportedOperationException("unsupported unary");
                }
                yield -evalDouble(u.child(), columns, schema, globalRow);
            }
            case Expr.Binary b ->
                    switch (b.op()) {
                        case ADD ->
                                evalDouble(b.left(), columns, schema, globalRow)
                                        + evalDouble(b.right(), columns, schema, globalRow);
                        case SUB ->
                                evalDouble(b.left(), columns, schema, globalRow)
                                        - evalDouble(b.right(), columns, schema, globalRow);
                        case MUL ->
                                evalDouble(b.left(), columns, schema, globalRow)
                                        * evalDouble(b.right(), columns, schema, globalRow);
                        case DIV ->
                                evalDouble(b.left(), columns, schema, globalRow)
                                        / evalDouble(b.right(), columns, schema, globalRow);
                        default ->
                                throw new UnsupportedOperationException("binary " + b.op() + " not numeric");
                    };
            default -> throw new UnsupportedOperationException();
        };
    }

    private boolean cmpEq(List<Column> columns, Schema schema, long row, Expr a, Expr b) {
        double da = cmpDouble(columns, schema, row, a);
        double db = cmpDouble(columns, schema, row, b);
        if (Double.isNaN(da) || Double.isNaN(db)) {
            return false;
        }
        return Double.compare(da, db) == 0;
    }

    /** @return numeric value or {@link Double#NaN} when a referenced column cell is null. */
    private double cmpDouble(List<Column> columns, Schema schema, long row, Expr e) {
        if (e instanceof Expr.ColRef c) {
            Column col =
                    columns.stream().filter(cc -> cc.name().equals(c.name())).findFirst().orElseThrow();
            if (ChunkValueAccess.isNull(col, row)) {
                return Double.NaN;
            }
        }
        return evalDouble(e, columns, schema, row);
    }

    private static double resolveDouble(List<Column> columns, Schema schema, long row, String colName) {
        schema.field(colName); // existence
        Column col = columns.stream().filter(c -> c.name().equals(colName)).findFirst().orElseThrow();
        return switch (col.type()) {
            case DOUBLE -> ChunkValueAccess.readDoubleNonNull(col, row);
            case LONG -> (double) ChunkValueAccess.readLongNonNull(col, row);
            case INT -> (double) ChunkValueAccess.readIntNonNull(col, row);
            default ->
                    throw new UnsupportedOperationException(
                            "DOUBLE/INT/LONG coercion not implemented for " + col.type());
        };
    }

    private static double numericConst(Expr.Const k) {
        if (k.type() != DataType.DOUBLE && k.type() != DataType.LONG && k.type() != DataType.INT) {
            throw new UnsupportedOperationException("unsupported const type " + k.type());
        }
        return switch (k.type()) {
            case DOUBLE -> ((Number) k.value()).doubleValue();
            case LONG -> ((Number) k.value()).longValue();
            case INT -> ((Number) k.value()).intValue();
            default -> throw new UnsupportedOperationException();
        };
    }
}
