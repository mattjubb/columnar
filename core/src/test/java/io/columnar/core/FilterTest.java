package io.columnar.core;

import io.columnar.api.BaseTable;
import io.columnar.api.BinaryOp;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Expr;
import io.columnar.api.RowPredicate;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.LongChunk;
import io.columnar.api.chunk.StringChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive filter tests covering every predicate type, compound predicates,
 * edge cases (null rows, empty results, all-pass), chaining, and expression predicates.
 *
 * <p>Each test uses a purpose-built dataset that makes the expected outcome obvious from
 * the data alone.
 */
@DisplayName("Filter operator")
class FilterTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private static ColumnarSlice filter(BaseTable table, RowPredicate pred) {
        return new DerivedTable(
                new FilterOperator(new SourceOperator(table), pred, "test"))
                .read(Viewport.ALL);
    }

    private static ColumnarSlice filter(BaseTable table, RowPredicate pred, String hint) {
        return new DerivedTable(
                new FilterOperator(new SourceOperator(table), pred, hint))
                .read(Viewport.ALL);
    }

    /** Extract all long values from a column as a list. */
    private static List<Long> longs(ColumnarSlice slice, String col) {
        List<Long> result = new ArrayList<>();
        LongChunk chunk = (LongChunk) slice.column(col).chunk(0);
        for (int r = 0; r < chunk.size(); r++) result.add(chunk.getLong(r));
        return result;
    }

    /** Extract all double values from a column (skips nulls). */
    private static List<Double> doubles(ColumnarSlice slice, String col) {
        List<Double> result = new ArrayList<>();
        for (int ci = 0; ci < slice.column(col).chunkCount(); ci++) {
            DoubleChunk chunk = (DoubleChunk) slice.column(col).chunk(ci);
            for (int r = 0; r < chunk.size(); r++) {
                if (!chunk.validity().isNull(r)) result.add(chunk.getDouble(r));
            }
        }
        return result;
    }

    /** Extract all string values from a column (null becomes the literal null in the list). */
    private static List<String> strings(ColumnarSlice slice, String col) {
        List<String> result = new ArrayList<>();
        StringChunk chunk = (StringChunk) slice.column(col).chunk(0);
        for (int r = 0; r < chunk.size(); r++) result.add(chunk.getString(r));
        return result;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("longGt keeps only rows above the threshold")
    void filterByLongGt() {
        Schema schema = Schema.builder().add("id", DataType.LONG).build();
        BaseTable table = Table.builder(schema)
                .appendRow(1L).appendRow(5L).appendRow(10L).appendRow(3L).appendRow(7L)
                .build();

        ColumnarSlice result = filter(table, RowPredicates.longGt("id", 4L));

        assertThat(longs(result, "id")).containsExactlyInAnyOrder(5L, 10L, 7L);
    }

    @Test
    @DisplayName("longEq returns exactly matching rows")
    void filterByLongEq() {
        Schema schema = Schema.builder().add("id", DataType.LONG).add("label", DataType.STRING).build();
        BaseTable table = Table.builder(schema)
                .appendRow(1L, "one")
                .appendRow(2L, "two-a")
                .appendRow(2L, "two-b")
                .appendRow(3L, "three")
                .build();

        ColumnarSlice result = filter(table, RowPredicates.longEq("id", 2L));

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(strings(result, "label")).containsExactlyInAnyOrder("two-a", "two-b");
    }

    @Test
    @DisplayName("doubleGt keeps rows where price strictly exceeds threshold")
    void filterByDoubleGt() {
        Schema schema = Schema.builder().add("price", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(99.99)
                .appendRow(100.00)   // boundary — must NOT pass (not strictly greater)
                .appendRow(100.01)
                .appendRow(200.00)
                .appendRow(0.01)
                .build();

        ColumnarSlice result = filter(table, RowPredicates.doubleGt("price", 100.00));

        assertThat(doubles(result, "price")).containsExactlyInAnyOrder(100.01, 200.00);
    }

    @Test
    @DisplayName("stringEq keeps only rows matching the string exactly (case-sensitive)")
    void filterByStringEq() {
        Schema schema = Schema.builder().add("symbol", DataType.STRING).add("price", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow("AAPL",  150.0)
                .appendRow("MSFT",  300.0)
                .appendRow("AAPL",  155.0)
                .appendRow("aapl",  1.0)    // different case — must NOT match
                .appendRow("GOOG",  140.0)
                .build();

        ColumnarSlice result = filter(table, RowPredicates.stringEq("symbol", "AAPL"));

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(doubles(result, "price")).containsExactlyInAnyOrder(150.0, 155.0);
    }

    @Test
    @DisplayName("AND predicate keeps only rows satisfying both conditions")
    void filterWithAnd() {
        Schema schema = Schema.builder()
                .add("region", DataType.STRING)
                .add("revenue", DataType.DOUBLE)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow("NORTH",  500.0)
                .appendRow("NORTH",   50.0)  // fails revenue threshold
                .appendRow("SOUTH",  700.0)  // fails region
                .appendRow("NORTH", 1000.0)
                .appendRow("EAST",   900.0)  // fails region
                .build();

        RowPredicate pred = RowPredicates.and(
                RowPredicates.stringEq("region", "NORTH"),
                RowPredicates.doubleGt("revenue", 100.0));

        ColumnarSlice result = filter(table, pred);

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(doubles(result, "revenue")).containsExactlyInAnyOrder(500.0, 1000.0);
    }

    @Test
    @DisplayName("OR predicate keeps rows satisfying either condition")
    void filterWithOr() {
        Schema schema = Schema.builder().add("code", DataType.STRING).add("val", DataType.LONG).build();
        BaseTable table = Table.builder(schema)
                .appendRow("A", 1L)
                .appendRow("B", 2L)
                .appendRow("C", 3L)
                .appendRow("A", 4L)
                .appendRow("D", 5L)
                .build();

        RowPredicate pred = RowPredicates.or(
                RowPredicates.stringEq("code", "A"),
                RowPredicates.stringEq("code", "C"));

        ColumnarSlice result = filter(table, pred);

        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(longs(result, "val")).containsExactlyInAnyOrder(1L, 3L, 4L);
    }

    @Test
    @DisplayName("filter that passes all rows returns the full table unchanged")
    void filterPassesAllRows() {
        Schema schema = Schema.builder().add("n", DataType.LONG).build();
        BaseTable table = Table.builder(schema)
                .appendRow(10L).appendRow(20L).appendRow(30L).build();

        ColumnarSlice result = filter(table, RowPredicates.longGt("n", 0L));

        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(longs(result, "n")).containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    @Test
    @DisplayName("filter that passes no rows returns an empty slice")
    void filterPassesNoRows() {
        Schema schema = Schema.builder().add("n", DataType.LONG).build();
        BaseTable table = Table.builder(schema)
                .appendRow(1L).appendRow(2L).appendRow(3L).build();

        ColumnarSlice result = filter(table, RowPredicates.longGt("n", 100L));

        assertThat(result.rowCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("null values in the filtered column do not pass any predicate")
    void nullValuesExcludedByPredicates() {
        Schema schema = Schema.builder().add("price", DataType.DOUBLE).add("label", DataType.STRING).build();
        BaseTable table = Table.builder(schema)
                .appendRow(200.0, "high")
                .appendRow(null,  "null-price")  // null price — must be excluded
                .appendRow(50.0,  "low")
                .build();

        ColumnarSlice result = filter(table, RowPredicates.doubleGt("price", 10.0));

        // null price row must not pass even though conceptually "unknown > 10"
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(strings(result, "label")).containsExactlyInAnyOrder("high", "low");
    }

    @Test
    @DisplayName("chained filters compose correctly — each narrows the result further")
    void chainedFilters() {
        Schema schema = Schema.builder()
                .add("dept",   DataType.STRING)
                .add("salary", DataType.DOUBLE)
                .add("tenure", DataType.LONG)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow("ENG", 90_000.0, 5L)
                .appendRow("ENG", 60_000.0, 1L)
                .appendRow("ENG", 95_000.0, 8L)
                .appendRow("MKT", 85_000.0, 6L)
                .appendRow("ENG", 70_000.0, 3L)
                .build();

        // First filter: department = ENG
        Operator step1 = new FilterOperator(
                new SourceOperator(table), RowPredicates.stringEq("dept", "ENG"), "dept-eng");
        // Second filter: salary > 80k
        Operator step2 = new FilterOperator(
                step1, RowPredicates.doubleGt("salary", 80_000.0), "salary-gt-80k");

        ColumnarSlice result = new DerivedTable(step2).read(Viewport.ALL);

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(doubles(result, "salary")).containsExactlyInAnyOrder(90_000.0, 95_000.0);
    }

    @Test
    @DisplayName("expression predicate (compiled via ByteBuddy) returns same results as hand-written")
    void expressionPredicate() {
        Schema schema = Schema.builder().add("score", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow(40.0).appendRow(75.0).appendRow(60.0).appendRow(90.0).appendRow(55.0)
                .build();

        Expr expr = new Expr.Binary(
                new Expr.ColRef("score"), BinaryOp.GT, new Expr.Const(60.0, DataType.DOUBLE));

        ColumnarSlice byExpr = new DerivedTable(new FilterOperator(
                new SourceOperator(table),
                new ExprRowPredicate(schema, expr), "expr-score-gt-60")).read(Viewport.ALL);

        ColumnarSlice byHand = filter(table, RowPredicates.doubleGt("score", 60.0));

        assertThat(byExpr.rowCount()).isEqualTo(byHand.rowCount());
        assertThat(doubles(byExpr, "score")).containsExactlyInAnyOrder(75.0, 90.0);
    }

    @Test
    @DisplayName("filter on table spanning multiple chunks preserves all passing rows")
    void filterAcrossChunkBoundaries() {
        int rowCount = 150_000; // > default chunk size of 65536
        Schema schema = Schema.builder().add("n", DataType.LONG).build();
        BaseTable table = Table.create(schema);
        for (long i = 0; i < rowCount; i++) table.appendRow(i);
        table.seal();

        ColumnarSlice result = filter(table, RowPredicates.longGt("n", rowCount / 2L - 1));

        // Exactly half the rows (rowCount/2 through rowCount-1)
        assertThat(result.rowCount()).isEqualTo(rowCount / 2L);
    }

    @Test
    @DisplayName("filtered rows retain null values in unfiltered columns")
    void filteredRowsKeepNullsInOtherColumns() {
        // Filter on "id" (long), verify that passing rows keep null values in "notes" column.
        Schema schema = Schema.builder()
                .add("id",    DataType.LONG)
                .add("notes", DataType.STRING)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow(10L, "has notes")
                .appendRow(20L, null)       // id passes filter, but notes is null
                .appendRow(5L,  "below threshold")  // id fails filter
                .build();

        // Filter: id > 8 — passes rows 0 (id=10) and 1 (id=20)
        ColumnarSlice result = filter(table, RowPredicates.longGt("id", 8L));

        assertThat(result.rowCount()).isEqualTo(2);
        StringChunk notes = (StringChunk) result.column("notes").chunk(0);
        assertThat(notes.getString(0)).isEqualTo("has notes");
        assertThat(notes.validity().isNull(1)).isTrue(); // row with id=20 keeps its null notes
    }

    @Test
    @DisplayName("filter → project → aggregate pipeline produces correct grouped counts")
    void filterThenProjectThenAggregate() {
        Schema schema = Schema.builder()
                .add("region", DataType.STRING)
                .add("value",  DataType.DOUBLE)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow("US",  100.0)
                .appendRow("US",   50.0)
                .appendRow("EU",  200.0)
                .appendRow("US",  300.0)
                .appendRow("EU",   75.0)
                .appendRow("APAC", 400.0)
                .build();

        // Keep only US and EU, then count per region
        Operator filtered = new FilterOperator(
                new SourceOperator(table),
                RowPredicates.or(RowPredicates.stringEq("region", "US"),
                                 RowPredicates.stringEq("region", "EU")),
                "us-or-eu");

        Operator agg = new HashAggregateOperator(filtered, "region",
                List.of(new HashAggregateOperator.AggMeasure(
                        HashAggregateOperator.AggKind.COUNT, null, "n")));

        ColumnarSlice result = new DerivedTable(agg).read(Viewport.ALL);

        // 2 groups: US(3) and EU(2)
        assertThat(result.rowCount()).isEqualTo(2);
        // Build a map: region → count
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        StringChunk regions = (StringChunk) result.column("region").chunk(0);
        LongChunk ns = (LongChunk) result.column("n").chunk(0);
        for (int i = 0; i < (int) result.rowCount(); i++) counts.put(regions.getString(i), ns.getLong(i));
        assertThat(counts).containsEntry("US", 3L).containsEntry("EU", 2L);
    }
}
