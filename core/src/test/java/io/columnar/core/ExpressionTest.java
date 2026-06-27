package io.columnar.core;

import io.columnar.api.BaseTable;
import io.columnar.api.BinaryOp;
import io.columnar.api.Column;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Expr;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.UnaryOp;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.LongChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for expression-based computation using {@link ExprInterpreter}.
 *
 * <p>Exercises:
 * <ul>
 *   <li>Arithmetic: ADD, SUB, MUL, DIV between two columns</li>
 *   <li>Arithmetic with constants (e.g. {@code price * 1.1})</li>
 *   <li>Unary negation</li>
 *   <li>Nested / compound expressions: {@code (A + B) * C}</li>
 *   <li>Type coercion: INT and LONG columns used in double expressions</li>
 *   <li>Comparison predicates: {@code A > B}, {@code A == B}, etc.</li>
 *   <li>Logical predicates: {@code AND}, {@code OR}, {@code NOT}</li>
 *   <li>Expression-based row filters via {@link ExprRowPredicate}</li>
 *   <li>Null propagation: null column cells produce {@link Double#NaN}</li>
 *   <li>Special float values: division by zero, very large products</li>
 * </ul>
 */
@DisplayName("Expression evaluation (ExprInterpreter)")
class ExpressionTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private static final ExprInterpreter interp = new ExprInterpreter();

    /** Build a column-ref expression. */
    private static Expr col(String name)         { return new Expr.ColRef(name); }
    private static Expr dbl(double v)            { return new Expr.Const(v,  DataType.DOUBLE); }
    private static Expr lng(long v)              { return new Expr.Const(v,  DataType.LONG);   }
    private static Expr intExpr(int v)           { return new Expr.Const(v,  DataType.INT);    }
    private static Expr add(Expr a, Expr b)      { return new Expr.Binary(a, BinaryOp.ADD, b); }
    private static Expr sub(Expr a, Expr b)      { return new Expr.Binary(a, BinaryOp.SUB, b); }
    private static Expr mul(Expr a, Expr b)      { return new Expr.Binary(a, BinaryOp.MUL, b); }
    private static Expr div(Expr a, Expr b)      { return new Expr.Binary(a, BinaryOp.DIV, b); }
    private static Expr neg(Expr a)              { return new Expr.Unary(UnaryOp.NEG, a); }
    private static Expr gt(Expr a, Expr b)       { return new Expr.Binary(a, BinaryOp.GT, b); }
    private static Expr lt(Expr a, Expr b)       { return new Expr.Binary(a, BinaryOp.LT, b); }
    private static Expr eq(Expr a, Expr b)       { return new Expr.Binary(a, BinaryOp.EQ, b); }
    private static Expr ne(Expr a, Expr b)       { return new Expr.Binary(a, BinaryOp.NE, b); }
    private static Expr ge(Expr a, Expr b)       { return new Expr.Binary(a, BinaryOp.GE, b); }
    private static Expr le(Expr a, Expr b)       { return new Expr.Binary(a, BinaryOp.LE, b); }
    private static Expr and(Expr a, Expr b)      { return new Expr.Binary(a, BinaryOp.AND, b); }
    private static Expr or(Expr a, Expr b)       { return new Expr.Binary(a, BinaryOp.OR, b); }
    private static Expr not(Expr a)              { return new Expr.Unary(UnaryOp.NOT, a); }

    /** Evaluate a numeric expression for every row in the slice, collecting results. */
    private static List<Double> evalDoubleAll(Expr expr, ColumnarSlice slice) {
        List<Column> cols = slice.columns();
        Schema schema = slice.schema();
        List<Double> results = new ArrayList<>();
        for (long row = 0; row < slice.rowCount(); row++) {
            results.add(interp.evalDouble(expr, cols, schema, row));
        }
        return results;
    }

    /** Evaluate a predicate for every row, collecting pass/fail booleans. */
    private static List<Boolean> evalBoolAll(Expr expr, ColumnarSlice slice) {
        List<Column> cols = slice.columns();
        Schema schema = slice.schema();
        List<Boolean> results = new ArrayList<>();
        for (long row = 0; row < slice.rowCount(); row++) {
            results.add(interp.evalPredicate(expr, cols, schema, row));
        }
        return results;
    }

    /** Run an expression-based filter and return the surviving slice. */
    private static ColumnarSlice exprFilter(BaseTable table, Expr pred, String hint) {
        return new DerivedTable(new FilterOperator(
                new SourceOperator(table),
                new ExprRowPredicate(table.schema(), pred), hint))
                .read(Viewport.ALL);
    }

    // =========================================================================
    // Arithmetic: column × column
    // =========================================================================

    @Test
    @DisplayName("A + B — addition of two DOUBLE columns")
    void addTwoColumns() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(3.0, 2.0)
                .appendRow(-1.5, 4.5)
                .appendRow(0.0, 0.0)
                .appendRow(100.0, -50.0)
                .build();

        List<Double> results = evalDoubleAll(add(col("a"), col("b")), table.read());

        assertThat(results).containsExactly(5.0, 3.0, 0.0, 50.0);
    }

    @Test
    @DisplayName("A - B — subtraction of two DOUBLE columns")
    void subtractTwoColumns() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(10.0, 3.0)
                .appendRow(1.0, 5.0)    // negative result
                .appendRow(-2.0, -2.0)  // zero result
                .build();

        List<Double> results = evalDoubleAll(sub(col("a"), col("b")), table.read());

        assertThat(results.get(0)).isCloseTo(7.0, within(1e-12));
        assertThat(results.get(1)).isCloseTo(-4.0, within(1e-12));
        assertThat(results.get(2)).isCloseTo(0.0, within(1e-12));
    }

    @Test
    @DisplayName("A * B — multiplication of two DOUBLE columns")
    void multiplyTwoColumns() {
        Schema schema = Schema.builder().add("price", DataType.DOUBLE).add("qty", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(9.99,  10.0)
                .appendRow(2.50, 100.0)
                .appendRow(-3.0,   4.0)  // negative × positive
                .appendRow(-2.0,  -5.0)  // negative × negative = positive
                .build();

        List<Double> results = evalDoubleAll(mul(col("price"), col("qty")), table.read());

        assertThat(results.get(0)).isCloseTo(99.9,  within(1e-9));
        assertThat(results.get(1)).isCloseTo(250.0, within(1e-9));
        assertThat(results.get(2)).isCloseTo(-12.0, within(1e-9));
        assertThat(results.get(3)).isCloseTo(10.0,  within(1e-9));
    }

    @Test
    @DisplayName("A / B — division of two DOUBLE columns")
    void divideTwoColumns() {
        Schema schema = Schema.builder().add("revenue", DataType.DOUBLE).add("units", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(100.0, 4.0)   // 25.0
                .appendRow(  7.0, 2.0)   // 3.5
                .appendRow(-30.0, 6.0)   // -5.0
                .build();

        List<Double> results = evalDoubleAll(div(col("revenue"), col("units")), table.read());

        assertThat(results.get(0)).isCloseTo(25.0, within(1e-12));
        assertThat(results.get(1)).isCloseTo(3.5,  within(1e-12));
        assertThat(results.get(2)).isCloseTo(-5.0, within(1e-12));
    }

    // =========================================================================
    // Arithmetic: column × constant
    // =========================================================================

    @Test
    @DisplayName("price * 1.1 — column multiplied by a constant (e.g. 10% markup)")
    void columnTimesConstant() {
        Schema schema = Schema.builder().add("price", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(100.0)
                .appendRow(49.99)
                .appendRow(0.01)
                .build();

        List<Double> results = evalDoubleAll(mul(col("price"), dbl(1.1)), table.read());

        assertThat(results.get(0)).isCloseTo(110.0,  within(1e-9));
        assertThat(results.get(1)).isCloseTo(54.989, within(1e-3));
        assertThat(results.get(2)).isCloseTo(0.011,  within(1e-12));
    }

    @Test
    @DisplayName("A + constant — offset every row by a fixed value")
    void columnPlusConstant() {
        Schema schema = Schema.builder().add("temp_c", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(0.0)
                .appendRow(100.0)
                .appendRow(-40.0)
                .build();

        // Convert Celsius to Fahrenheit: (C * 9/5) + 32
        Expr toFahrenheit = add(mul(col("temp_c"), dbl(9.0 / 5.0)), dbl(32.0));
        List<Double> results = evalDoubleAll(toFahrenheit, table.read());

        assertThat(results.get(0)).isCloseTo(32.0,   within(1e-9));
        assertThat(results.get(1)).isCloseTo(212.0,  within(1e-9));
        assertThat(results.get(2)).isCloseTo(-40.0,  within(1e-9));
    }

    @Test
    @DisplayName("constant / A — reciprocal (e.g. 1.0 / rate)")
    void constantDividedByColumn() {
        Schema schema = Schema.builder().add("rate", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(2.0)
                .appendRow(4.0)
                .appendRow(0.5)
                .build();

        List<Double> results = evalDoubleAll(div(dbl(1.0), col("rate")), table.read());

        assertThat(results.get(0)).isCloseTo(0.5, within(1e-12));
        assertThat(results.get(1)).isCloseTo(0.25, within(1e-12));
        assertThat(results.get(2)).isCloseTo(2.0, within(1e-12));
    }

    // =========================================================================
    // Unary negation
    // =========================================================================

    @Test
    @DisplayName("-A — unary negation flips sign of each row")
    void unaryNegation() {
        Schema schema = Schema.builder().add("v", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(5.0)
                .appendRow(-3.0)
                .appendRow(0.0)
                .build();

        List<Double> results = evalDoubleAll(neg(col("v")), table.read());

        assertThat(results).containsExactly(-5.0, 3.0, -0.0);
    }

    @Test
    @DisplayName("-(A + B) — negate a compound sub-expression")
    void negateCompoundExpression() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(3.0, 7.0)   // -(3+7) = -10
                .appendRow(-4.0, 1.0)  // -(-4+1) = 3
                .build();

        List<Double> results = evalDoubleAll(neg(add(col("a"), col("b"))), table.read());

        assertThat(results.get(0)).isCloseTo(-10.0, within(1e-12));
        assertThat(results.get(1)).isCloseTo(3.0,   within(1e-12));
    }

    // =========================================================================
    // Compound / nested expressions
    // =========================================================================

    @Test
    @DisplayName("(A + B) * C — three-column compound arithmetic")
    void compoundThreeColumns() {
        Schema schema = Schema.builder()
                .add("a", DataType.DOUBLE)
                .add("b", DataType.DOUBLE)
                .add("c", DataType.DOUBLE)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow(2.0,  3.0, 4.0)  // (2+3)*4 = 20
                .appendRow(1.0, -1.0, 5.0)  // (1-1)*5 = 0
                .appendRow(0.5,  0.5, 10.0) // 1.0*10  = 10
                .build();

        List<Double> results = evalDoubleAll(mul(add(col("a"), col("b")), col("c")), table.read());

        assertThat(results.get(0)).isCloseTo(20.0, within(1e-12));
        assertThat(results.get(1)).isCloseTo(0.0,  within(1e-12));
        assertThat(results.get(2)).isCloseTo(10.0, within(1e-12));
    }

    @Test
    @DisplayName("A/B + C*D — two independent sub-expressions summed")
    void twoSubExpressionsSummed() {
        Schema schema = Schema.builder()
                .add("a", DataType.DOUBLE).add("b", DataType.DOUBLE)
                .add("c", DataType.DOUBLE).add("d", DataType.DOUBLE)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow(10.0, 2.0, 3.0, 4.0)  // 10/2 + 3*4 = 5+12 = 17
                .appendRow( 6.0, 3.0, 2.0, 2.0)  // 6/3  + 2*2 = 2+4  = 6
                .build();

        Expr expr = add(div(col("a"), col("b")), mul(col("c"), col("d")));
        List<Double> results = evalDoubleAll(expr, table.read());

        assertThat(results.get(0)).isCloseTo(17.0, within(1e-12));
        assertThat(results.get(1)).isCloseTo(6.0,  within(1e-12));
    }

    @Test
    @DisplayName("revenue / units — gross margin per unit across many rows")
    void revenuePerUnit() {
        Schema schema = Schema.builder()
                .add("revenue", DataType.DOUBLE)
                .add("units",   DataType.DOUBLE)
                .build();
        BaseTable.Builder b = Table.builder(schema);
        for (int i = 1; i <= 100; i++) b.appendRow((double)(i * 10), (double) i);
        BaseTable table = b.build();

        // All rows should give revenue/units = 10.0
        List<Double> results = evalDoubleAll(div(col("revenue"), col("units")), table.read());
        assertThat(results).allSatisfy(v -> assertThat(v).isCloseTo(10.0, within(1e-9)));
    }

    // =========================================================================
    // Type coercion: INT / LONG columns in double expressions
    // =========================================================================

    @Test
    @DisplayName("LONG * DOUBLE — integer column coerced to double for arithmetic")
    void longTimesDouble() {
        Schema schema = Schema.builder()
                .add("qty",   DataType.LONG)
                .add("price", DataType.DOUBLE)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow(5L,  9.99)
                .appendRow(100L, 0.25)
                .appendRow(1L,  100.0)
                .build();

        List<Double> results = evalDoubleAll(mul(col("qty"), col("price")), table.read());

        assertThat(results.get(0)).isCloseTo(49.95, within(1e-9));
        assertThat(results.get(1)).isCloseTo(25.0,  within(1e-9));
        assertThat(results.get(2)).isCloseTo(100.0, within(1e-9));
    }

    @Test
    @DisplayName("INT + INT — integer columns summed as doubles")
    void intPlusInt() {
        Schema schema = Schema.builder()
                .add("x", DataType.INT)
                .add("y", DataType.INT)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow(3,   7)
                .appendRow(-5,  2)
                .appendRow(Integer.MAX_VALUE, 1)
                .build();

        List<Double> results = evalDoubleAll(add(col("x"), col("y")), table.read());

        assertThat(results.get(0)).isCloseTo(10.0, within(1e-12));
        assertThat(results.get(1)).isCloseTo(-3.0, within(1e-12));
        // INT max + 1 doesn't overflow in double arithmetic
        assertThat(results.get(2)).isCloseTo((double) Integer.MAX_VALUE + 1.0, within(1e-3));
    }

    @Test
    @DisplayName("LONG constant in expression — lng() constant matches col() coercion")
    void longConstantExpression() {
        Schema schema = Schema.builder().add("v", DataType.LONG).build();
        BaseTable table = Table.builder(schema)
                .appendRow(10L).appendRow(20L).appendRow(30L).build();

        // v * 3L (long constant)
        List<Double> results = evalDoubleAll(mul(col("v"), lng(3L)), table.read());

        assertThat(results).containsExactly(30.0, 60.0, 90.0);
    }

    // =========================================================================
    // Comparison predicates
    // =========================================================================

    @Test
    @DisplayName("A > B — evaluates correctly for each row")
    void columnGreaterThanColumn() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(5.0, 3.0)   // true
                .appendRow(3.0, 5.0)   // false
                .appendRow(4.0, 4.0)   // false (strict)
                .build();

        List<Boolean> results = evalBoolAll(gt(col("a"), col("b")), table.read());

        assertThat(results).containsExactly(true, false, false);
    }

    @Test
    @DisplayName("A == B — equality comparison between two columns")
    void columnEqualsColumn() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(1.0, 1.0)
                .appendRow(1.0, 2.0)
                .appendRow(0.0, -0.0)  // +0 == -0 in IEEE 754
                .build();

        List<Boolean> results = evalBoolAll(eq(col("a"), col("b")), table.read());

        assertThat(results).containsExactly(true, false, true);
    }

    @Test
    @DisplayName("A != B — inequality returns complement of equality")
    void columnNotEqualsColumn() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(1.0, 2.0)
                .appendRow(5.0, 5.0)
                .build();

        List<Boolean> results = evalBoolAll(ne(col("a"), col("b")), table.read());

        assertThat(results).containsExactly(true, false);
    }

    @Test
    @DisplayName("A <= B and A >= B — boundary inclusive comparisons")
    void inclusiveComparisons() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(3.0, 5.0)   // 3<=5 true, 3>=5 false
                .appendRow(5.0, 5.0)   // both true on boundary
                .appendRow(7.0, 5.0)   // 7<=5 false, 7>=5 true
                .build();

        List<Boolean> leResults = evalBoolAll(le(col("a"), col("b")), table.read());
        List<Boolean> geResults = evalBoolAll(ge(col("a"), col("b")), table.read());

        assertThat(leResults).containsExactly(true, true, false);
        assertThat(geResults).containsExactly(false, true, true);
    }

    // =========================================================================
    // Logical predicates: AND / OR / NOT
    // =========================================================================

    @Test
    @DisplayName("(A > 0) AND (B > 0) — both columns positive")
    void andPredicate() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow( 1.0,  2.0)   // both positive → true
                .appendRow(-1.0,  2.0)   // a negative   → false
                .appendRow( 1.0, -2.0)   // b negative   → false
                .appendRow(-1.0, -2.0)   // both negative → false
                .build();

        List<Boolean> results = evalBoolAll(
                and(gt(col("a"), dbl(0.0)), gt(col("b"), dbl(0.0))), table.read());

        assertThat(results).containsExactly(true, false, false, false);
    }

    @Test
    @DisplayName("(A > 10) OR (B > 10) — either column exceeds threshold")
    void orPredicate() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(15.0,  5.0)   // a > 10 → true
                .appendRow( 5.0, 15.0)   // b > 10 → true
                .appendRow(15.0, 15.0)   // both   → true
                .appendRow( 5.0,  5.0)   // neither → false
                .build();

        List<Boolean> results = evalBoolAll(
                or(gt(col("a"), dbl(10.0)), gt(col("b"), dbl(10.0))), table.read());

        assertThat(results).containsExactly(true, true, true, false);
    }

    @Test
    @DisplayName("NOT (A > B) — logical NOT of a comparison")
    void notPredicate() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(5.0, 3.0)   // a>b true  → NOT → false
                .appendRow(1.0, 9.0)   // a>b false → NOT → true
                .build();

        List<Boolean> results = evalBoolAll(not(gt(col("a"), col("b"))), table.read());

        assertThat(results).containsExactly(false, true);
    }

    // =========================================================================
    // Expression-based filtering
    // =========================================================================

    @Test
    @DisplayName("filter where price * qty > 100 — arithmetic in filter predicate")
    void filterByArithmeticExpression() {
        Schema schema = Schema.builder()
                .add("price", DataType.DOUBLE)
                .add("qty",   DataType.DOUBLE)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow(5.0,  25.0)   // 125 → pass
                .appendRow(10.0,  9.0)   // 90  → fail
                .appendRow(2.0,  60.0)   // 120 → pass
                .appendRow(50.0,  3.0)   // 150 → pass
                .appendRow(1.0, 100.0)   // 100 → fail (not strictly greater)
                .build();

        // price * qty > 100
        Expr pred = gt(mul(col("price"), col("qty")), dbl(100.0));
        ColumnarSlice result = exprFilter(table, pred, "value-gt-100");

        assertThat(result.rowCount()).isEqualTo(3);
        DoubleChunk prices = (DoubleChunk) result.column("price").chunk(0);
        assertThat(prices.getDouble(0)).isEqualTo(5.0);
        assertThat(prices.getDouble(1)).isEqualTo(2.0);
        assertThat(prices.getDouble(2)).isEqualTo(50.0);
    }

    @Test
    @DisplayName("filter where (revenue - cost) / revenue > 0.3 — margin > 30%")
    void filterByMarginExpression() {
        Schema schema = Schema.builder()
                .add("revenue", DataType.DOUBLE)
                .add("cost",    DataType.DOUBLE)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow(100.0, 65.0)  // margin = 35% → pass
                .appendRow(200.0, 150.0) // margin = 25% → fail
                .appendRow(500.0, 300.0) // margin = 40% → pass
                .appendRow( 80.0,  60.0) // margin = 25% → fail
                .build();

        // (revenue - cost) / revenue > 0.3
        Expr margin = div(sub(col("revenue"), col("cost")), col("revenue"));
        ColumnarSlice result = exprFilter(table, gt(margin, dbl(0.3)), "margin-gt-30pct");

        assertThat(result.rowCount()).isEqualTo(2);
        DoubleChunk revenues = (DoubleChunk) result.column("revenue").chunk(0);
        assertThat(revenues.getDouble(0)).isEqualTo(100.0);
        assertThat(revenues.getDouble(1)).isEqualTo(500.0);
    }

    @Test
    @DisplayName("filter where A * A + B * B < R*R — points inside a circle (radius R)")
    void filterPointsInsideCircle() {
        Schema schema = Schema.builder().add("x", DataType.DOUBLE).add("y", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(0.0,  0.0)   // origin — inside
                .appendRow(3.0,  4.0)   // hypotenuse=5  — on boundary, not strictly inside
                .appendRow(1.0,  1.0)   // sqrt(2)≈1.41  — inside radius 5
                .appendRow(5.0,  5.0)   // sqrt(50)≈7.07 — outside
                .appendRow(2.0,  3.0)   // sqrt(13)≈3.6  — inside
                .build();

        // x*x + y*y < 25  (radius 5, strictly inside)
        Expr distSq = add(mul(col("x"), col("x")), mul(col("y"), col("y")));
        ColumnarSlice result = exprFilter(table, lt(distSq, dbl(25.0)), "in-circle");

        assertThat(result.rowCount()).isEqualTo(3); // origin, (1,1), (2,3)
    }

    @Test
    @DisplayName("compound filter: (A + B > 10) AND (A * B < 50)")
    void compoundArithmeticFilter() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(6.0,  6.0)   // sum=12 pass, product=36 pass  → pass
                .appendRow(2.0,  3.0)   // sum=5  fail                   → fail
                .appendRow(8.0,  7.0)   // sum=15 pass, product=56 fail  → fail
                .appendRow(4.0,  8.0)   // sum=12 pass, product=32 pass  → pass
                .build();

        Expr sumGt10 = gt(add(col("a"), col("b")), dbl(10.0));
        Expr prodLt50 = lt(mul(col("a"), col("b")), dbl(50.0));
        ColumnarSlice result = exprFilter(table, and(sumGt10, prodLt50), "sum-and-prod");

        assertThat(result.rowCount()).isEqualTo(2);
    }

    // =========================================================================
    // Null handling
    // =========================================================================

    @Test
    @DisplayName("null operand in arithmetic evaluates to NaN")
    void nullOperandProducesNaN() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(5.0,  null)   // b is null → a*b = NaN
                .appendRow(null, 3.0)    // a is null → a+b = NaN
                .appendRow(4.0,  2.0)    // both non-null → 8.0
                .build();

        List<Double> mulResults = evalDoubleAll(mul(col("a"), col("b")), table.read());
        List<Double> addResults = evalDoubleAll(add(col("a"), col("b")), table.read());

        assertThat(mulResults.get(0)).isNaN();
        assertThat(addResults.get(1)).isNaN();
        assertThat(mulResults.get(2)).isCloseTo(8.0, within(1e-12));
    }

    @Test
    @DisplayName("null operand in comparison predicate evaluates to false")
    void nullOperandInPredicateIsFalse() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(5.0, null)    // a > b with null b → false
                .appendRow(null, 3.0)    // a > b with null a → false
                .appendRow(5.0, 3.0)     // non-null → true
                .build();

        List<Boolean> results = evalBoolAll(gt(col("a"), col("b")), table.read());

        assertThat(results).containsExactly(false, false, true);
    }

    @Test
    @DisplayName("null rows excluded by expression filter — null operand never passes >")
    void nullRowsExcludedByExpressionFilter() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).add("b", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(10.0, 2.0)   // 20 > 5 → pass
                .appendRow(null, 2.0)   // null * 2 → NaN, NaN > 5 = false
                .appendRow( 3.0, 2.0)   // 6  > 5 → pass
                .appendRow( 1.0, 2.0)   // 2  > 5 → fail
                .build();

        Expr pred = gt(mul(col("a"), col("b")), dbl(5.0));
        ColumnarSlice result = exprFilter(table, pred, "no-nulls");

        assertThat(result.rowCount()).isEqualTo(2); // rows 0 and 2 only
        DoubleChunk as = (DoubleChunk) result.column("a").chunk(0);
        assertThat(as.getDouble(0)).isEqualTo(10.0);
        assertThat(as.getDouble(1)).isEqualTo(3.0);
    }

    // =========================================================================
    // Special float values
    // =========================================================================

    @Test
    @DisplayName("A / 0.0 — division by zero produces Infinity (IEEE 754)")
    void divisionByZero() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(5.0)
                .appendRow(-5.0)
                .appendRow(0.0)   // 0/0 = NaN
                .build();

        List<Double> results = evalDoubleAll(div(col("a"), dbl(0.0)), table.read());

        assertThat(results.get(0)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(results.get(1)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(results.get(2)).isNaN(); // 0/0
    }

    @Test
    @DisplayName("very large product — Double.MAX_VALUE * 2 overflows to Infinity")
    void largeProductOverflow() {
        Schema schema = Schema.builder().add("a", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema).appendRow(Double.MAX_VALUE).build();

        List<Double> results = evalDoubleAll(mul(col("a"), dbl(2.0)), table.read());

        assertThat(results.get(0)).isEqualTo(Double.POSITIVE_INFINITY);
    }
}
