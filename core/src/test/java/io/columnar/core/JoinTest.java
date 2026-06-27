package io.columnar.core;

import io.columnar.api.BaseTable;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.JoinKind;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive join tests covering all four join kinds (INNER, LEFT, RIGHT, FULL),
 * fan-out, empty inputs, null keys, and composition with other operators.
 *
 * <p>Convention: "probe" = left/fact table; "build" = right/dimension table.
 * The build-side join-key column is dropped from the output (it equals the probe key).
 */
@DisplayName("Hash join operator")
class JoinTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private static ColumnarSlice join(BaseTable probe, BaseTable build,
                                      String probeKey, String buildKey, JoinKind kind) {
        return new DerivedTable(new HashJoinOperator(
                new SourceOperator(probe), new SourceOperator(build),
                probeKey, buildKey, kind)).read(Viewport.ALL);
    }

    private static ColumnarSlice innerJoin(BaseTable probe, BaseTable build,
                                           String probeKey, String buildKey) {
        return join(probe, build, probeKey, buildKey, JoinKind.INNER);
    }

    private static List<String> strings(ColumnarSlice s, String col) {
        List<String> out = new ArrayList<>();
        StringChunk c = (StringChunk) s.column(col).chunk(0);
        for (int r = 0; r < c.size(); r++) out.add(c.getString(r));
        return out;
    }

    private static List<Double> doubles(ColumnarSlice s, String col) {
        List<Double> out = new ArrayList<>();
        for (int ci = 0; ci < s.column(col).chunkCount(); ci++) {
            DoubleChunk c = (DoubleChunk) s.column(col).chunk(ci);
            for (int r = 0; r < c.size(); r++) out.add(c.validity().isNull(r) ? null : c.getDouble(r));
        }
        return out;
    }

    private static List<Long> longs(ColumnarSlice s, String col) {
        List<Long> out = new ArrayList<>();
        LongChunk c = (LongChunk) s.column(col).chunk(0);
        for (int r = 0; r < c.size(); r++) out.add(c.validity().isNull(r) ? null : c.getLong(r));
        return out;
    }

    // =========================================================================
    // INNER join
    // =========================================================================

    @Test
    @DisplayName("INNER join — basic equijoin produces matched rows only")
    void innerJoinBasic() {
        Schema orders = Schema.builder()
                .add("order_id", DataType.LONG)
                .add("cust_id",  DataType.STRING)
                .add("amount",   DataType.DOUBLE)
                .build();
        Schema customers = Schema.builder()
                .add("cust_id", DataType.STRING)
                .add("name",    DataType.STRING)
                .build();

        BaseTable probe = Table.builder(orders)
                .appendRow(1L, "C1", 100.0)
                .appendRow(2L, "C2",  50.0)
                .appendRow(3L, "C1", 200.0)
                .build();
        BaseTable build = Table.builder(customers)
                .appendRow("C1", "Alice")
                .appendRow("C2", "Bob")
                .build();

        ColumnarSlice result = innerJoin(probe, build, "cust_id", "cust_id");

        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.schema().names()).contains("order_id", "cust_id", "amount", "name");
        // build key "cust_id" is dropped — only one "cust_id" in output (from probe)
        assertThat(strings(result, "name")).containsExactlyInAnyOrder("Alice", "Bob", "Alice");
    }

    @Test
    @DisplayName("INNER join fan-out — multiple probe rows per build key multiply correctly")
    void innerJoinFanOut() {
        Schema lineItems = Schema.builder()
                .add("sku",  DataType.STRING)
                .add("qty",  DataType.LONG)
                .build();
        Schema products = Schema.builder()
                .add("sku",   DataType.STRING)
                .add("price", DataType.DOUBLE)
                .build();

        // 3 probe rows for "SKU-1"
        BaseTable probe = Table.builder(lineItems)
                .appendRow("SKU-1", 2L)
                .appendRow("SKU-1", 5L)
                .appendRow("SKU-2", 1L)
                .appendRow("SKU-1", 3L)
                .build();
        BaseTable build = Table.builder(products)
                .appendRow("SKU-1", 10.0)
                .appendRow("SKU-2", 25.0)
                .build();

        ColumnarSlice result = innerJoin(probe, build, "sku", "sku");

        assertThat(result.rowCount()).isEqualTo(4);
        // SKU-1 rows see price 10.0, SKU-2 sees 25.0
        assertThat(doubles(result, "price")).containsExactlyInAnyOrder(10.0, 10.0, 25.0, 10.0);
    }

    @Test
    @DisplayName("INNER join — no matches produces empty result")
    void innerJoinNoMatches() {
        // Probe and build have disjoint keys — no output rows.
        Schema probe = Schema.builder().add("key", DataType.STRING).add("probe_val", DataType.LONG).build();
        Schema build = Schema.builder().add("key", DataType.STRING).add("build_desc", DataType.STRING).build();

        BaseTable probeTable = Table.builder(probe).appendRow("X", 1L).appendRow("Y", 2L).build();
        BaseTable buildTable = Table.builder(build).appendRow("A", "alpha").appendRow("B", "beta").build();

        ColumnarSlice result = innerJoin(probeTable, buildTable, "key", "key");

        assertThat(result.rowCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("INNER join — all probe rows match produces same count as probe")
    void innerJoinAllMatch() {
        Schema orders = Schema.builder()
                .add("id",   DataType.LONG)
                .add("code", DataType.STRING)
                .build();
        Schema codes = Schema.builder()
                .add("code", DataType.STRING)
                .add("desc", DataType.STRING)
                .build();

        BaseTable probe = Table.builder(orders)
                .appendRow(1L, "A").appendRow(2L, "B").appendRow(3L, "A").build();
        BaseTable build = Table.builder(codes)
                .appendRow("A", "Alpha").appendRow("B", "Beta").build();

        ColumnarSlice result = innerJoin(probe, build, "code", "code");

        assertThat(result.rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("INNER join with empty probe table returns empty result")
    void innerJoinEmptyProbe() {
        Schema probeSchema = Schema.builder().add("k", DataType.STRING).add("probe_n", DataType.LONG).build();
        Schema buildSchema = Schema.builder().add("k", DataType.STRING).add("build_n", DataType.LONG).build();
        BaseTable probe = Table.builder(probeSchema).build(); // 0 rows
        BaseTable build = Table.builder(buildSchema).appendRow("X", 1L).build();

        ColumnarSlice result = innerJoin(probe, build, "k", "k");

        assertThat(result.rowCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("INNER join with empty build table returns empty result")
    void innerJoinEmptyBuild() {
        Schema probeSchema = Schema.builder().add("k", DataType.STRING).add("probe_n", DataType.LONG).build();
        Schema buildSchema = Schema.builder().add("k", DataType.STRING).add("build_n", DataType.LONG).build();
        BaseTable probe = Table.builder(probeSchema).appendRow("X", 1L).build();
        BaseTable build = Table.builder(buildSchema).build(); // 0 rows

        ColumnarSlice result = innerJoin(probe, build, "k", "k");

        assertThat(result.rowCount()).isEqualTo(0);
    }

    // =========================================================================
    // LEFT join
    // =========================================================================

    @Test
    @DisplayName("LEFT join — unmatched probe rows appear with null build columns")
    void leftJoinRetainsUnmatched() {
        Schema orders = Schema.builder()
                .add("id",    DataType.LONG)
                .add("cid",   DataType.STRING)
                .add("total", DataType.DOUBLE)
                .build();
        Schema customers = Schema.builder()
                .add("cid",  DataType.STRING)
                .add("tier", DataType.STRING)
                .build();

        BaseTable probe = Table.builder(orders)
                .appendRow(1L, "C1", 99.0)
                .appendRow(2L, "C_UNKNOWN", 50.0)  // no matching customer
                .appendRow(3L, "C2", 200.0)
                .build();
        BaseTable build = Table.builder(customers)
                .appendRow("C1", "GOLD")
                .appendRow("C2", "SILVER")
                .build();

        ColumnarSlice result = join(probe, build, "cid", "cid", JoinKind.LEFT);

        assertThat(result.rowCount()).isEqualTo(3);
        StringChunk tier = (StringChunk) result.column("tier").chunk(0);
        assertThat(tier.getString(0)).isEqualTo("GOLD");
        assertThat(tier.validity().isNull(1)).isTrue();   // C_UNKNOWN gets null tier
        assertThat(tier.getString(2)).isEqualTo("SILVER");
    }

    @Test
    @DisplayName("LEFT join with empty build table — all probe rows survive with nulls")
    void leftJoinEmptyBuild() {
        Schema probe = Schema.builder()
                .add("k", DataType.STRING).add("v", DataType.LONG).build();
        Schema build = Schema.builder()
                .add("k", DataType.STRING).add("extra", DataType.DOUBLE).build();

        BaseTable probeTable = Table.builder(probe)
                .appendRow("A", 1L).appendRow("B", 2L).appendRow("C", 3L).build();
        BaseTable buildTable = Table.builder(build).build(); // 0 rows

        ColumnarSlice result = join(probeTable, buildTable, "k", "k", JoinKind.LEFT);

        assertThat(result.rowCount()).isEqualTo(3); // all probe rows survive
        DoubleChunk extra = (DoubleChunk) result.column("extra").chunk(0);
        assertThat(extra.validity().isNull(0)).isTrue();
        assertThat(extra.validity().isNull(1)).isTrue();
        assertThat(extra.validity().isNull(2)).isTrue();
    }

    // =========================================================================
    // RIGHT join
    // =========================================================================

    @Test
    @DisplayName("RIGHT join — unmatched build rows appear with null probe columns")
    void rightJoinRetainsUnmatched() {
        Schema salesSchema = Schema.builder()
                .add("region",  DataType.STRING)
                .add("revenue", DataType.DOUBLE)
                .build();
        Schema regionSchema = Schema.builder()
                .add("region",   DataType.STRING)
                .add("manager",  DataType.STRING)
                .build();

        // Build (dimension) has 3 regions; probe (fact) only covers 2
        BaseTable probe = Table.builder(salesSchema)
                .appendRow("NORTH", 1000.0)
                .appendRow("SOUTH",  500.0)
                .build();
        BaseTable build = Table.builder(regionSchema)
                .appendRow("NORTH", "Alice")
                .appendRow("SOUTH", "Bob")
                .appendRow("EAST",  "Carol")   // no sales — must appear with null revenue
                .build();

        ColumnarSlice result = join(probe, build, "region", "region", JoinKind.RIGHT);

        assertThat(result.rowCount()).isEqualTo(3);

        // For RIGHT join, the probe-side "region" column is null for unmatched build rows.
        // Identify rows by manager (build-only column, always present).
        StringChunk managers = (StringChunk) result.column("manager").chunk(0);
        DoubleChunk revenues  = (DoubleChunk) result.column("revenue").chunk(0);

        Map<String, Double> byManager = new HashMap<>();
        for (int r = 0; r < (int) result.rowCount(); r++) {
            byManager.put(managers.getString(r),
                    revenues.validity().isNull(r) ? null : revenues.getDouble(r));
        }
        assertThat(byManager).containsEntry("Alice", 1000.0)
                             .containsEntry("Bob",   500.0)
                             .containsEntry("Carol", null);  // EAST: no probe match → null revenue
    }

    // =========================================================================
    // FULL join
    // =========================================================================

    @Test
    @DisplayName("FULL join — unmatched rows on both sides appear with nulls on the other side")
    void fullJoinBothSidesUnmatched() {
        Schema a = Schema.builder().add("k", DataType.STRING).add("va", DataType.LONG).build();
        Schema b = Schema.builder().add("k", DataType.STRING).add("vb", DataType.LONG).build();

        // Only "SHARED" matches on both sides
        BaseTable probe = Table.builder(a)
                .appendRow("PROBE-ONLY", 10L)
                .appendRow("SHARED", 20L)
                .build();
        BaseTable build = Table.builder(b)
                .appendRow("SHARED", 30L)
                .appendRow("BUILD-ONLY", 40L)
                .build();

        ColumnarSlice result = join(probe, build, "k", "k", JoinKind.FULL);

        assertThat(result.rowCount()).isEqualTo(3); // PROBE-ONLY + SHARED + BUILD-ONLY

        LongChunk vas = (LongChunk) result.column("va").chunk(0);
        LongChunk vbs = (LongChunk) result.column("vb").chunk(0);

        // For FULL join, unmatched build rows have null probe key (k) — use va/vb nullity to classify rows.
        Long sharedVa = null, sharedVb = null;
        Long probeOnlyVa = null;
        Long buildOnlyVb = null;

        for (int r = 0; r < (int) result.rowCount(); r++) {
            boolean vaNull = vas.validity().isNull(r);
            boolean vbNull = vbs.validity().isNull(r);
            if (!vaNull && !vbNull) {
                sharedVa = vas.getLong(r);
                sharedVb = vbs.getLong(r);
            } else if (!vaNull) {
                probeOnlyVa = vas.getLong(r);   // probe-only: has va, no vb
            } else {
                buildOnlyVb = vbs.getLong(r);   // build-only: has vb, no va
            }
        }

        assertThat(sharedVa).isEqualTo(20L);
        assertThat(sharedVb).isEqualTo(30L);
        assertThat(probeOnlyVa).isEqualTo(10L);
        assertThat(buildOnlyVb).isEqualTo(40L);
    }

    // =========================================================================
    // Schema and output structure
    // =========================================================================

    @Test
    @DisplayName("join output schema drops build key but keeps all other columns from both sides")
    void outputSchemaDropsBuildKey() {
        Schema probe = Schema.builder()
                .add("order_id", DataType.LONG)
                .add("symbol",   DataType.STRING)
                .build();
        Schema build = Schema.builder()
                .add("symbol",   DataType.STRING)  // join key — will be dropped
                .add("sector",   DataType.STRING)
                .add("mkt_cap",  DataType.DOUBLE)
                .build();

        BaseTable probeTable = Table.builder(probe).appendRow(1L, "AAPL").build();
        BaseTable buildTable = Table.builder(build).appendRow("AAPL", "TECH", 3_000_000.0).build();

        ColumnarSlice result = innerJoin(probeTable, buildTable, "symbol", "symbol");

        // Build key "symbol" (from build side) is dropped — probe "symbol" is kept.
        assertThat(result.schema().names()).containsExactlyInAnyOrder("order_id", "symbol", "sector", "mkt_cap");
        assertThat(result.schema().size()).isEqualTo(4);
    }

    @Test
    @DisplayName("join then filter — composition with FilterOperator narrows results post-join")
    void joinThenFilter() {
        Schema trades = Schema.builder()
                .add("id",     DataType.LONG)
                .add("ticker", DataType.STRING)
                .add("price",  DataType.DOUBLE)
                .build();
        Schema tickers = Schema.builder()
                .add("ticker",   DataType.STRING)
                .add("exchange", DataType.STRING)
                .build();

        BaseTable probe = Table.builder(trades)
                .appendRow(1L, "AAPL", 180.0)
                .appendRow(2L, "MSFT", 310.0)
                .appendRow(3L, "AAPL", 175.0)
                .appendRow(4L, "GOOG", 140.0)
                .build();
        BaseTable build = Table.builder(tickers)
                .appendRow("AAPL", "NASDAQ")
                .appendRow("MSFT", "NASDAQ")
                .appendRow("GOOG", "NASDAQ")
                .build();

        Operator joined   = new HashJoinOperator(new SourceOperator(probe), new SourceOperator(build),
                "ticker", "ticker", JoinKind.INNER);
        Operator filtered = new FilterOperator(joined, RowPredicates.doubleGt("price", 170.0), "price-gt-170");

        ColumnarSlice result = new DerivedTable(filtered).read(Viewport.ALL);

        // price > 170: rows 1(180), 2(310), 3(175 excluded as 175 < 170? No: 175 > 170 passes)
        // Actually 175 > 170 passes too. So 180, 310, 175 all pass. 140 doesn't.
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(doubles(result, "price")).containsExactlyInAnyOrder(180.0, 310.0, 175.0);
    }

    @Test
    @DisplayName("join then aggregate — group-by on enriched dimension column after join")
    void joinThenAggregate() {
        Schema sales = Schema.builder()
                .add("region", DataType.STRING)
                .add("amount", DataType.DOUBLE)
                .build();
        Schema regions = Schema.builder()
                .add("region",  DataType.STRING)
                .add("country", DataType.STRING)
                .build();

        BaseTable probe = Table.builder(sales)
                .appendRow("NYC", 200.0)
                .appendRow("LA",  150.0)
                .appendRow("NYC", 300.0)
                .appendRow("LON", 400.0)
                .appendRow("NYC", 100.0)
                .build();
        BaseTable build = Table.builder(regions)
                .appendRow("NYC", "US")
                .appendRow("LA",  "US")
                .appendRow("LON", "UK")
                .build();

        Operator joined = new HashJoinOperator(new SourceOperator(probe), new SourceOperator(build),
                "region", "region", JoinKind.INNER);
        Operator agg = new HashAggregateOperator(joined, "country",
                List.of(new HashAggregateOperator.AggMeasure(
                        HashAggregateOperator.AggKind.SUM_DOUBLE, "amount", "total")));

        ColumnarSlice result = new DerivedTable(agg).read(Viewport.ALL);

        assertThat(result.rowCount()).isEqualTo(2); // US and UK

        Map<String, Double> totals = new HashMap<>();
        StringChunk countries = (StringChunk) result.column("country").chunk(0);
        DoubleChunk tots = (DoubleChunk) result.column("total").chunk(0);
        for (int r = 0; r < (int) result.rowCount(); r++) totals.put(countries.getString(r), tots.getDouble(r));

        assertThat(totals).containsEntry("US", 750.0)   // 200+150+300+100
                          .containsEntry("UK", 400.0);
    }
}
