package io.columnar.core;

import io.columnar.api.BaseTable;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.LongChunk;
import io.columnar.api.chunk.StringChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Comprehensive GROUP BY tests covering COUNT, SUM, average (derived from SUM/COUNT),
 * single/many groups, nulls in the group key, large tables, multi-measure aggregations,
 * and compositions with filter and sort.
 */
@DisplayName("HashAggregate operator (GROUP BY)")
class GroupByTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private static final HashAggregateOperator.AggKind COUNT = HashAggregateOperator.AggKind.COUNT;
    private static final HashAggregateOperator.AggKind SUM   = HashAggregateOperator.AggKind.SUM_DOUBLE;

    private static HashAggregateOperator.AggMeasure count(String out) {
        return new HashAggregateOperator.AggMeasure(COUNT, null, out);
    }

    private static HashAggregateOperator.AggMeasure sum(String in, String out) {
        return new HashAggregateOperator.AggMeasure(SUM, in, out);
    }

    /** Run groupBy and return results as a map: group_key → {measure_name → value}. */
    private static Map<String, Map<String, Double>> groupBy(
            BaseTable table, String groupCol, HashAggregateOperator.AggMeasure... measures) {

        HashAggregateOperator op = new HashAggregateOperator(
                new SourceOperator(table), groupCol, List.of(measures));
        ColumnarSlice slice = new DerivedTable(op).read(Viewport.ALL);

        if (slice.rowCount() == 0 || slice.column(groupCol).chunkCount() == 0) {
            return new HashMap<>();
        }

        StringChunk keys = (StringChunk) slice.column(groupCol).chunk(0);
        Map<String, Map<String, Double>> result = new HashMap<>();

        for (int r = 0; r < (int) slice.rowCount(); r++) {
            String key = keys.getString(r);
            Map<String, Double> row = new HashMap<>();
            for (HashAggregateOperator.AggMeasure m : measures) {
                String colName = m.outputColumn();
                if (m.kind() == COUNT) {
                    LongChunk c = (LongChunk) slice.column(colName).chunk(0);
                    row.put(colName, (double) c.getLong(r));
                } else {
                    DoubleChunk c = (DoubleChunk) slice.column(colName).chunk(0);
                    row.put(colName, c.getDouble(r));
                }
            }
            result.put(key, row);
        }
        return result;
    }

    /** Sales table used across multiple tests. */
    private static BaseTable salesTable() {
        Schema schema = Schema.builder()
                .add("region",   DataType.STRING)
                .add("category", DataType.STRING)
                .add("amount",   DataType.DOUBLE)
                .add("units",    DataType.DOUBLE)
                .build();
        return Table.builder(schema)
                .appendRow("NORTH", "FOOD",   120.0, 10.0)
                .appendRow("NORTH", "DRINK",   80.0,  5.0)
                .appendRow("SOUTH", "FOOD",   200.0, 15.0)
                .appendRow("SOUTH", "FOOD",   150.0, 12.0)
                .appendRow("NORTH", "FOOD",    90.0,  8.0)
                .appendRow("EAST",  "DRINK",  300.0, 20.0)
                .appendRow("SOUTH", "DRINK",   60.0,  4.0)
                .appendRow("EAST",  "FOOD",   250.0, 18.0)
                .build();
    }

    // =========================================================================
    // COUNT
    // =========================================================================

    @Test
    @DisplayName("COUNT — produces one output row per distinct key with correct counts")
    void countByGroup() {
        Map<String, Map<String, Double>> result = groupBy(salesTable(), "region", count("n"));

        assertThat(result).hasSize(3);
        assertThat(result.get("NORTH").get("n")).isEqualTo(3.0);
        assertThat(result.get("SOUTH").get("n")).isEqualTo(3.0);
        assertThat(result.get("EAST").get("n")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("COUNT — every row in its own group (all distinct keys) → N groups of 1")
    void countWithAllDistinctKeys() {
        Schema schema = Schema.builder().add("id", DataType.STRING).build();
        BaseTable table = Table.builder(schema)
                .appendRow("A").appendRow("B").appendRow("C").appendRow("D").appendRow("E")
                .build();

        Map<String, Map<String, Double>> result = groupBy(table, "id", count("n"));

        assertThat(result).hasSize(5);
        result.values().forEach(m -> assertThat(m.get("n")).isEqualTo(1.0));
    }

    @Test
    @DisplayName("COUNT — single group (all rows share the same key)")
    void countSingleGroup() {
        Schema schema = Schema.builder().add("tag", DataType.STRING).add("v", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow("X", 1.0).appendRow("X", 2.0).appendRow("X", 3.0).build();

        Map<String, Map<String, Double>> result = groupBy(table, "tag", count("n"));

        assertThat(result).hasSize(1);
        assertThat(result.get("X").get("n")).isEqualTo(3.0);
    }

    // =========================================================================
    // SUM
    // =========================================================================

    @Test
    @DisplayName("SUM — accumulates DOUBLE values correctly per group")
    void sumByGroup() {
        Map<String, Map<String, Double>> result = groupBy(salesTable(), "region", sum("amount", "total"));

        assertThat(result).hasSize(3);
        assertThat(result.get("NORTH").get("total")).isCloseTo(290.0, within(1e-9)); // 120+80+90
        assertThat(result.get("SOUTH").get("total")).isCloseTo(410.0, within(1e-9)); // 200+150+60
        assertThat(result.get("EAST").get("total")).isCloseTo(550.0, within(1e-9));  // 300+250
    }

    @Test
    @DisplayName("SUM — negative values are accumulated correctly")
    void sumWithNegativeValues() {
        Schema schema = Schema.builder().add("grp", DataType.STRING).add("delta", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow("A",  100.0)
                .appendRow("A", -30.0)
                .appendRow("A",  -20.0)
                .appendRow("B",  50.0)
                .appendRow("B", -50.0)
                .build();

        Map<String, Map<String, Double>> result = groupBy(table, "grp", sum("delta", "net"));

        assertThat(result.get("A").get("net")).isCloseTo(50.0, within(1e-9));
        assertThat(result.get("B").get("net")).isCloseTo(0.0,  within(1e-9));
    }

    @Test
    @DisplayName("SUM — floating-point precision: many small values accumulate without gross error")
    void sumFloatingPointPrecision() {
        Schema schema = Schema.builder().add("grp", DataType.STRING).add("v", DataType.DOUBLE).build();
        BaseTable.Builder b = Table.builder(schema);
        // 1000 additions of 0.1 should give approximately 100.0
        for (int i = 0; i < 1000; i++) b.appendRow("G", 0.1);
        BaseTable table = b.build();

        Map<String, Map<String, Double>> result = groupBy(table, "grp", sum("v", "total"));

        assertThat(result.get("G").get("total")).isCloseTo(100.0, within(1e-9));
    }

    // =========================================================================
    // COUNT + SUM together (and derived metrics)
    // =========================================================================

    @Test
    @DisplayName("COUNT + SUM together — both measures computed in a single pass")
    void countAndSumByGroup() {
        Map<String, Map<String, Double>> result = groupBy(
                salesTable(), "category",
                count("n"), sum("amount", "revenue"));

        assertThat(result).hasSize(2); // FOOD and DRINK

        // FOOD: 4 rows (120+200+150+90=560), DRINK: 4 rows (80+300+60=440... wait 3 rows 80+300+60=440)
        // FOOD appears 5 times? No: NORTH-FOOD, NORTH-FOOD, SOUTH-FOOD, SOUTH-FOOD, EAST-FOOD = 5?
        // Counting from the table: rows 1(NORTH/FOOD), 3(SOUTH/FOOD), 4(SOUTH/FOOD), 5(NORTH/FOOD), 8(EAST/FOOD) = 5 FOODs
        // DRINK: rows 2(NORTH/DRINK), 6(EAST/DRINK), 7(SOUTH/DRINK) = 3 DRINKs
        assertThat(result.get("FOOD").get("n")).isEqualTo(5.0);
        assertThat(result.get("DRINK").get("n")).isEqualTo(3.0);
        assertThat(result.get("FOOD").get("revenue")).isCloseTo(810.0, within(1e-9));  // 120+200+150+90+250
        assertThat(result.get("DRINK").get("revenue")).isCloseTo(440.0, within(1e-9)); // 80+300+60
    }

    @Test
    @DisplayName("average derived from SUM / COUNT — correct per-group mean")
    void averageDerivedFromSumAndCount() {
        Schema schema = Schema.builder().add("dept", DataType.STRING).add("salary", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow("ENG", 90_000.0)
                .appendRow("ENG", 80_000.0)
                .appendRow("ENG", 100_000.0)
                .appendRow("MKT", 70_000.0)
                .appendRow("MKT", 60_000.0)
                .build();

        HashAggregateOperator op = new HashAggregateOperator(
                new SourceOperator(table), "dept",
                List.of(count("n"), sum("salary", "total_salary")));
        ColumnarSlice slice = new DerivedTable(op).read(Viewport.ALL);

        StringChunk depts = (StringChunk) slice.column("dept").chunk(0);
        LongChunk ns = (LongChunk) slice.column("n").chunk(0);
        DoubleChunk totals = (DoubleChunk) slice.column("total_salary").chunk(0);

        Map<String, Double> avg = new HashMap<>();
        for (int r = 0; r < (int) slice.rowCount(); r++) {
            avg.put(depts.getString(r), totals.getDouble(r) / ns.getLong(r));
        }

        assertThat(avg.get("ENG")).isCloseTo(90_000.0, within(1e-6));
        assertThat(avg.get("MKT")).isCloseTo(65_000.0, within(1e-6));
    }

    @Test
    @DisplayName("multiple measures — count rows, sum two different columns independently")
    void multipleMeasures() {
        Schema schema = Schema.builder()
                .add("type",     DataType.STRING)
                .add("quantity", DataType.DOUBLE)
                .add("cost",     DataType.DOUBLE)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow("A", 10.0, 5.0)
                .appendRow("A", 20.0, 8.0)
                .appendRow("B",  5.0, 2.0)
                .build();

        HashAggregateOperator op = new HashAggregateOperator(
                new SourceOperator(table), "type",
                List.of(count("n"), sum("quantity", "qty_total"), sum("cost", "cost_total")));
        ColumnarSlice result = new DerivedTable(op).read(Viewport.ALL);

        StringChunk types = (StringChunk) result.column("type").chunk(0);
        Map<String, Integer> rowIdx = new HashMap<>();
        for (int r = 0; r < (int) result.rowCount(); r++) rowIdx.put(types.getString(r), r);

        DoubleChunk qty  = (DoubleChunk) result.column("qty_total").chunk(0);
        DoubleChunk cost = (DoubleChunk) result.column("cost_total").chunk(0);
        LongChunk   n    = (LongChunk)   result.column("n").chunk(0);

        int ai = rowIdx.get("A");
        assertThat(n.getLong(ai)).isEqualTo(2L);
        assertThat(qty.getDouble(ai)).isCloseTo(30.0, within(1e-9));
        assertThat(cost.getDouble(ai)).isCloseTo(13.0, within(1e-9));

        int bi = rowIdx.get("B");
        assertThat(n.getLong(bi)).isEqualTo(1L);
        assertThat(qty.getDouble(bi)).isCloseTo(5.0, within(1e-9));
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    @DisplayName("empty table — zero input rows produce zero output groups")
    void emptyTableProducesNoGroups() {
        Schema schema = Schema.builder().add("g", DataType.STRING).add("v", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema).build();

        Map<String, Map<String, Double>> result = groupBy(table, "g", count("n"), sum("v", "s"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null group key rows are excluded — only non-null keys are aggregated")
    void nullGroupKeyRowsExcluded() {
        // The operator skips rows with a null group key (same behaviour as SQL GROUP BY).
        Schema schema = Schema.builder().add("grp", DataType.STRING).add("v", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow("A",  10.0)
                .appendRow(null, 20.0)   // null key — excluded from all groups
                .appendRow("A",  30.0)
                .appendRow(null, 40.0)   // null key — excluded
                .appendRow("B",  50.0)
                .build();

        Map<String, Map<String, Double>> result = groupBy(table, "grp", count("n"), sum("v", "s"));

        // Only "A" and "B" groups — null rows are not counted
        assertThat(result).hasSize(2);
        assertThat(result.get("A").get("n")).isEqualTo(2.0);
        assertThat(result.get("A").get("s")).isCloseTo(40.0, within(1e-9)); // 10+30
        assertThat(result.get("B").get("n")).isEqualTo(1.0);
        assertThat(result.get("B").get("s")).isCloseTo(50.0, within(1e-9));
        assertThat(result).doesNotContainKey(null);
    }

    @Test
    @DisplayName("large table — correct counts and sums across chunk boundaries")
    void largeTableGroupBy() {
        Schema schema = Schema.builder().add("bucket", DataType.STRING).add("v", DataType.DOUBLE).build();
        int rowsPerBucket = 100_000; // total 200k rows > one chunk
        BaseTable.Builder b = Table.builder(schema);
        for (int i = 0; i < rowsPerBucket; i++) b.appendRow("ODD",  1.0);
        for (int i = 0; i < rowsPerBucket; i++) b.appendRow("EVEN", 2.0);
        BaseTable table = b.build();

        Map<String, Map<String, Double>> result = groupBy(table, "bucket", count("n"), sum("v", "s"));

        assertThat(result.get("ODD").get("n")).isEqualTo(rowsPerBucket);
        assertThat(result.get("ODD").get("s")).isCloseTo(rowsPerBucket, within(1e-3));
        assertThat(result.get("EVEN").get("n")).isEqualTo(rowsPerBucket);
        assertThat(result.get("EVEN").get("s")).isCloseTo(rowsPerBucket * 2.0, within(1e-3));
    }

    @Test
    @DisplayName("groupBy then sort — output can be sorted by a measure column")
    void groupByThenSort() {
        Schema schema = Schema.builder().add("dept", DataType.STRING).add("cost", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow("OPS", 500.0)
                .appendRow("ENG", 300.0)
                .appendRow("ENG", 200.0)
                .appendRow("OPS", 100.0)
                .appendRow("MKT", 400.0)
                .build();

        Operator agg = new HashAggregateOperator(new SourceOperator(table), "dept",
                List.of(sum("cost", "total")));
        Operator sorted = new OrderByOperator(agg, "total", true); // ascending by total

        ColumnarSlice result = new DerivedTable(sorted).read(Viewport.ALL);

        assertThat(result.rowCount()).isEqualTo(3);
        DoubleChunk totals = (DoubleChunk) result.column("total").chunk(0);
        // ascending: ENG(500), MKT(400), OPS(600) → sorted: 400(MKT), 500(ENG), 600(OPS)
        // ENG: 300+200=500, OPS: 500+100=600, MKT: 400
        assertThat(totals.getDouble(0)).isEqualTo(400.0); // MKT
        assertThat(totals.getDouble(1)).isEqualTo(500.0); // ENG
        assertThat(totals.getDouble(2)).isEqualTo(600.0); // OPS
    }

    @Test
    @DisplayName("HAVING equivalent — filter on aggregate result removes low-volume groups")
    void groupByThenFilterOnAggregate() {
        Schema schema = Schema.builder().add("category", DataType.STRING).add("sale", DataType.DOUBLE).build();
        BaseTable table = Table.builder(schema)
                .appendRow("A", 10.0)
                .appendRow("A", 20.0)
                .appendRow("A", 30.0)  // total 60
                .appendRow("B",  5.0)  // total 5 (below threshold)
                .appendRow("C", 50.0)
                .appendRow("C", 50.0)  // total 100
                .build();

        Operator agg = new HashAggregateOperator(new SourceOperator(table), "category",
                List.of(count("n"), sum("sale", "revenue")));
        // HAVING revenue > 10 (removes B)
        Operator having = new FilterOperator(agg, RowPredicates.doubleGt("revenue", 10.0), "having");

        ColumnarSlice result = new DerivedTable(having).read(Viewport.ALL);

        assertThat(result.rowCount()).isEqualTo(2); // A and C only
        StringChunk cats = (StringChunk) result.column("category").chunk(0);
        List<String> names = new ArrayList<>();
        for (int r = 0; r < (int) result.rowCount(); r++) names.add(cats.getString(r));
        assertThat(names).containsExactlyInAnyOrder("A", "C");
    }

    @Test
    @DisplayName("filter then groupBy — only filtered rows contribute to aggregates")
    void filterThenGroupBy() {
        Schema schema = Schema.builder()
                .add("region", DataType.STRING)
                .add("active", DataType.DOUBLE)  // 1.0 = active, 0.0 = inactive (no BOOLEAN filter available)
                .add("revenue", DataType.DOUBLE)
                .build();
        BaseTable table = Table.builder(schema)
                .appendRow("US", 1.0, 500.0)
                .appendRow("US", 0.0, 100.0)  // inactive — filtered out
                .appendRow("EU", 1.0, 400.0)
                .appendRow("EU", 0.0,  50.0)  // inactive — filtered out
                .appendRow("US", 1.0, 300.0)
                .build();

        Operator filtered = new FilterOperator(
                new SourceOperator(table), RowPredicates.doubleGt("active", 0.5), "active-only");
        Operator agg = new HashAggregateOperator(filtered, "region",
                List.of(count("n"), sum("revenue", "total")));

        ColumnarSlice result = new DerivedTable(agg).read(Viewport.ALL);

        Map<String, Map<String, Double>> grouped = new HashMap<>();
        StringChunk regions = (StringChunk) result.column("region").chunk(0);
        LongChunk ns = (LongChunk) result.column("n").chunk(0);
        DoubleChunk totals = (DoubleChunk) result.column("total").chunk(0);
        for (int r = 0; r < (int) result.rowCount(); r++) {
            Map<String, Double> m = new HashMap<>();
            m.put("n", (double) ns.getLong(r));
            m.put("total", totals.getDouble(r));
            grouped.put(regions.getString(r), m);
        }

        assertThat(grouped.get("US").get("n")).isEqualTo(2.0);
        assertThat(grouped.get("US").get("total")).isCloseTo(800.0, within(1e-9)); // 500+300
        assertThat(grouped.get("EU").get("n")).isEqualTo(1.0);
        assertThat(grouped.get("EU").get("total")).isCloseTo(400.0, within(1e-9));
    }

    @Test
    @DisplayName("groupBy on a joined table — dimensions enriched before aggregation")
    void groupByAfterJoin() {
        Schema sales = Schema.builder()
                .add("product",  DataType.STRING)
                .add("revenue",  DataType.DOUBLE)
                .build();
        Schema catalog = Schema.builder()
                .add("product",  DataType.STRING)
                .add("category", DataType.STRING)
                .build();

        BaseTable probe = Table.builder(sales)
                .appendRow("APPLE",  1.50)
                .appendRow("BANANA", 0.75)
                .appendRow("STEAK",  8.00)
                .appendRow("APPLE",  2.00)
                .appendRow("CHICKEN",6.00)
                .appendRow("BANANA", 1.00)
                .build();
        BaseTable build = Table.builder(catalog)
                .appendRow("APPLE",   "FRUIT")
                .appendRow("BANANA",  "FRUIT")
                .appendRow("STEAK",   "MEAT")
                .appendRow("CHICKEN", "MEAT")
                .build();

        Operator joined = new HashJoinOperator(
                new SourceOperator(probe), new SourceOperator(build),
                "product", "product", io.columnar.api.JoinKind.INNER);
        Operator agg = new HashAggregateOperator(joined, "category",
                List.of(count("n"), sum("revenue", "total")));

        ColumnarSlice result = new DerivedTable(agg).read(Viewport.ALL);

        assertThat(result.rowCount()).isEqualTo(2);
        StringChunk cats = (StringChunk) result.column("category").chunk(0);
        DoubleChunk tots = (DoubleChunk) result.column("total").chunk(0);
        LongChunk ns = (LongChunk) result.column("n").chunk(0);

        Map<String, Double[]> res = new HashMap<>();
        for (int r = 0; r < (int) result.rowCount(); r++) {
            res.put(cats.getString(r), new Double[]{(double) ns.getLong(r), tots.getDouble(r)});
        }

        // FRUIT: APPLE(1.5+2.0) + BANANA(0.75+1.0) = 5.25, 4 rows
        assertThat(res.get("FRUIT")[0]).isEqualTo(4.0);
        assertThat(res.get("FRUIT")[1]).isCloseTo(5.25, within(1e-9));
        // MEAT: STEAK(8.0) + CHICKEN(6.0) = 14.0, 2 rows
        assertThat(res.get("MEAT")[0]).isEqualTo(2.0);
        assertThat(res.get("MEAT")[1]).isCloseTo(14.0, within(1e-9));
    }
}
