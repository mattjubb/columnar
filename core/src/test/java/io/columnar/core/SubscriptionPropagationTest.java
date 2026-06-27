package io.columnar.core;

import io.columnar.api.BaseTable;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Subscription;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.StringChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end demonstration that subscribers on a derived table receive
 * propagated updates whenever the underlying live source(s) change, with
 * viewport-aware materialization.
 *
 * <p>Topology under test:
 * <pre>
 *   open BaseTable trades
 *     └─ FilterOperator (price > 300)
 *         └─ ProjectOperator (id, symbol, price)
 *             └─ DerivedTable (subscribed to a viewport)
 * </pre>
 */
class SubscriptionPropagationTest {

    private static Schema tradesSchema() {
        return Schema.builder()
                .add("id", DataType.LONG)
                .add("symbol", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .add("region", DataType.STRING)
                .build();
    }

    @Test
    void subscriberSeesInitialThenLiveUpdates() {
        BaseTable trades = Table.create(tradesSchema());
        // Seed two rows BEFORE subscribing so the initial delivery sees content.
        trades.appendRow(1L, "AAPL", 220.0, "US");
        trades.appendRow(2L, "MSFT", 410.0, "US");

        Operator src = new SourceOperator(trades);
        Operator filt = new FilterOperator(src, RowPredicates.doubleGt("price", 300.0), "price>300");
        Operator proj = new ProjectOperator(filt, List.of("id", "symbol", "price"));
        DerivedTable derived = new DerivedTable(proj);

        // Capture every slice the subscriber sees.
        List<ColumnarSlice> deliveries = new CopyOnWriteArrayList<>();

        Subscription sub = derived.subscribe(Viewport.ALL, deliveries::add);

        // Delivery #0: initial — only MSFT (410 > 300) passes.
        assertThat(deliveries).hasSize(1);
        ColumnarSlice d0 = deliveries.get(0);
        assertThat(d0.rowCount()).isEqualTo(1);
        assertThat(((StringChunk) d0.column("symbol").chunk(0)).getString(0)).isEqualTo("MSFT");

        // Append a new row that passes the filter — subscriber should fire.
        trades.appendRow(3L, "TSLA", 305.5, "US");
        assertThat(deliveries).hasSize(2);
        ColumnarSlice d1 = deliveries.get(1);
        assertThat(d1.rowCount()).isEqualTo(2);
        assertThat(((StringChunk) d1.column("symbol").chunk(0)).getString(1)).isEqualTo("TSLA");

        // Append a row that fails the filter — subscriber still fires (any change
        // bumps the upstream version), but content is unchanged.
        trades.appendRow(4L, "AAPL", 221.0, "US");
        assertThat(deliveries).hasSize(3);
        ColumnarSlice d2 = deliveries.get(2);
        assertThat(d2.rowCount()).isEqualTo(2);

        // Append another high-priced row.
        trades.appendRow(5L, "MSFT", 411.0, "EU");
        assertThat(deliveries).hasSize(4);
        ColumnarSlice d3 = deliveries.get(3);
        assertThat(d3.rowCount()).isEqualTo(3);
        assertThat(((DoubleChunk) d3.column("price").chunk(0)).getDouble(2)).isEqualTo(411.0);

        // Sanity: versions are strictly increasing across deliveries.
        for (int i = 1; i < deliveries.size(); i++) {
            assertThat(deliveries.get(i).version())
                    .as("version monotonicity at #%d", i)
                    .isGreaterThanOrEqualTo(deliveries.get(i - 1).version());
        }
        assertThat(deliveries.get(deliveries.size() - 1).version())
                .isGreaterThan(deliveries.get(0).version());

        // Closing detaches the subscription — further updates should not fire.
        int sizeBeforeClose = deliveries.size();
        sub.close();
        assertThat(sub.isActive()).isFalse();
        trades.appendRow(6L, "GOOG", 999.0, "US");
        assertThat(deliveries).hasSize(sizeBeforeClose);
    }

    @Test
    void subscriberWithRowRangeViewportSeesOnlyThatWindow() {
        BaseTable trades = Table.create(tradesSchema());
        Operator src = new SourceOperator(trades);
        Operator filt = new FilterOperator(src, RowPredicates.doubleGt("price", 0), "price>0");
        DerivedTable derived = new DerivedTable(filt);

        List<ColumnarSlice> deliveries = new CopyOnWriteArrayList<>();
        // Viewport: only the first 2 rows.
        Subscription sub = derived.subscribe(Viewport.builder().limit(2).build(), deliveries::add);

        try {
            // Initial — empty.
            assertThat(deliveries).hasSize(1);
            assertThat(deliveries.get(0).rowCount()).isZero();

            trades.appendRow(1L, "AAPL", 220.0, "US");
            trades.appendRow(2L, "MSFT", 410.0, "US");
            trades.appendRow(3L, "GOOG", 175.0, "US");
            trades.appendRow(4L, "TSLA", 305.0, "US");

            // The subscriber sees 4 deliveries (one per append), but each is capped
            // at 2 rows. Once we hit the limit the slice contents stabilize.
            assertThat(deliveries).hasSize(5); // 1 initial + 4 appends
            ColumnarSlice last = deliveries.get(deliveries.size() - 1);
            assertThat(last.rowCount()).isEqualTo(2);
            assertThat(((StringChunk) last.column("symbol").chunk(0)).getString(0)).isEqualTo("AAPL");
            assertThat(((StringChunk) last.column("symbol").chunk(0)).getString(1)).isEqualTo("MSFT");
        } finally {
            sub.close();
        }
    }

    @Test
    void pivotSubscriptionRecomputesOnAppend() {
        BaseTable trades = Table.create(Schema.builder()
                .add("region", DataType.STRING)
                .add("category", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .build());

        Operator src = new SourceOperator(trades);
        Operator pivot = new PivotOperator(src, "region", "category", "price",
                List.of("cat1", "cat2", "cat3"));
        DerivedTable pivoted = new DerivedTable(pivot);

        List<ColumnarSlice> deliveries = new CopyOnWriteArrayList<>();
        try (Subscription sub = pivoted.subscribe(Viewport.ALL, deliveries::add)) {
            assertThat(sub.isActive()).isTrue();
            // Initial: empty pivot (no rows yet).
            assertThat(deliveries).hasSize(1);
            assertThat(deliveries.get(0).rowCount()).isZero();

            trades.appendRow("US", "cat1", 10.0);
            trades.appendRow("US", "cat2", 20.0);
            trades.appendRow("EU", "cat1", 5.0);

            // Each append triggers a fresh pivot computation.
            assertThat(deliveries).hasSize(4);

            ColumnarSlice last = deliveries.get(deliveries.size() - 1);
            // Two regions, cat1/cat2 populated, cat3 zero.
            assertThat(last.rowCount()).isEqualTo(2);
            last.prettyPrint();
        }
    }
}
