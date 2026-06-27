package io.columnar.core;

import io.columnar.api.BaseTable;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Subscription;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.LongChunk;
import io.columnar.api.chunk.StringChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates that an active {@link Subscription} can have its viewport
 * swapped via {@link Subscription#updateViewport(Viewport)} — the change takes
 * effect immediately (one re-delivery) and persists for subsequent upstream
 * mutations, all without paying the cost of a re-subscribe (no listener
 * detach/attach, no cache rewarm).
 *
 * <p>Models the typical UI pattern: a subscriber is bound to a derived table
 * once at component mount, then scrolls / re-filters / re-orders by mutating
 * the viewport on each user interaction.
 */
class SubscriptionViewportSwapTest {

    private static Schema tradesSchema() {
        return Schema.builder()
                .add("id", DataType.LONG)
                .add("symbol", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .build();
    }

    @Test
    void updateViewportOnBaseTableSubscriptionRefreshesImmediately() {
        BaseTable trades = Table.create(tradesSchema());
        for (long i = 0; i < 10; i++) {
            trades.appendRow(i, "S" + i, 100.0 + i);
        }

        List<ColumnarSlice> deliveries = new CopyOnWriteArrayList<>();
        Viewport firstFive = Viewport.builder().rows(0, 5).build();
        try (Subscription sub = trades.subscribe(firstFive, deliveries::add)) {
            // Initial delivery: rows [0, 5).
            assertThat(deliveries).hasSize(1);
            assertThat(deliveries.get(0).rowCount()).isEqualTo(5);
            assertThat(((LongChunk) deliveries.get(0).column("id").chunk(0)).getLong(0))
                    .isEqualTo(0L);
            assertThat(sub.viewport()).isSameAs(firstFive);

            // Swap the viewport — should deliver immediately.
            Viewport lastFive = Viewport.builder().rows(5, 10).build();
            sub.updateViewport(lastFive);
            assertThat(deliveries).hasSize(2);
            assertThat(deliveries.get(1).rowCount()).isEqualTo(5);
            assertThat(((LongChunk) deliveries.get(1).column("id").chunk(0)).getLong(0))
                    .isEqualTo(5L);
            assertThat(sub.viewport()).isSameAs(lastFive);

            // Mutate upstream — next delivery uses the *new* viewport.
            trades.appendRow(10L, "S10", 110.0);
            assertThat(deliveries).hasSize(3);
            // lastFive = rows [5, 10). The append doesn't touch that window, so
            // the slice should still hold ids 5..9.
            assertThat(deliveries.get(2).rowCount()).isEqualTo(5);
            assertThat(((LongChunk) deliveries.get(2).column("id").chunk(0)).getLong(0))
                    .isEqualTo(5L);

            // Widen the viewport to include the new row.
            sub.updateViewport(Viewport.builder().rows(5, 11).build());
            assertThat(deliveries).hasSize(4);
            assertThat(deliveries.get(3).rowCount()).isEqualTo(6);
            assertThat(((LongChunk) deliveries.get(3).column("id").chunk(0)).getLong(5))
                    .isEqualTo(10L);
        }
    }

    @Test
    void updateViewportOnDerivedTableSubscriptionRefreshesImmediately() {
        BaseTable trades = Table.create(tradesSchema());
        for (long i = 0; i < 6; i++) {
            trades.appendRow(i, i % 2 == 0 ? "AAPL" : "MSFT", 100.0 + i);
        }

        Operator src = new SourceOperator(trades);
        Operator filt = new FilterOperator(
                src, RowPredicates.stringEq("symbol", "AAPL"), "sym=AAPL");
        DerivedTable derived = new DerivedTable(filt);

        List<ColumnarSlice> deliveries = new CopyOnWriteArrayList<>();
        Viewport allCols = Viewport.ALL;
        try (Subscription sub = derived.subscribe(allCols, deliveries::add)) {
            // Initial: 3 AAPL rows, all 3 columns.
            assertThat(deliveries).hasSize(1);
            assertThat(deliveries.get(0).rowCount()).isEqualTo(3);
            assertThat(deliveries.get(0).columnCount()).isEqualTo(3);

            // Narrow to (id, symbol) columns only — should re-deliver immediately.
            Viewport idSymOnly = Viewport.builder().columns("id", "symbol").build();
            sub.updateViewport(idSymOnly);
            assertThat(deliveries).hasSize(2);
            assertThat(deliveries.get(1).columnCount()).isEqualTo(2);
            assertThat(deliveries.get(1).schema().names()).containsExactly("id", "symbol");
            assertThat(deliveries.get(1).rowCount()).isEqualTo(3);
            assertThat(((StringChunk) deliveries.get(1).column("symbol").chunk(0))
                    .getString(0)).isEqualTo("AAPL");

            // Now mutate upstream — should deliver against the narrowed viewport.
            trades.appendRow(6L, "AAPL", 106.0);
            assertThat(deliveries).hasSize(3);
            assertThat(deliveries.get(2).columnCount()).isEqualTo(2);
            assertThat(deliveries.get(2).rowCount()).isEqualTo(4);

            // Add a limit on top of the columns.
            sub.updateViewport(Viewport.builder().columns("id", "symbol").limit(2).build());
            assertThat(deliveries).hasSize(4);
            assertThat(deliveries.get(3).rowCount()).isEqualTo(2);

            // One more upstream tick — limit + columns should still apply.
            trades.appendRow(7L, "AAPL", 107.0);
            assertThat(deliveries).hasSize(5);
            assertThat(deliveries.get(4).rowCount()).isEqualTo(2);
            assertThat(deliveries.get(4).columnCount()).isEqualTo(2);
        }
    }

    @Test
    void updateViewportAfterCloseIsNoOp() {
        BaseTable trades = Table.create(tradesSchema());
        trades.appendRow(1L, "AAPL", 220.0);

        List<ColumnarSlice> deliveries = new CopyOnWriteArrayList<>();
        Subscription sub = trades.subscribe(Viewport.ALL, deliveries::add);
        assertThat(deliveries).hasSize(1);

        sub.close();
        assertThat(sub.isActive()).isFalse();

        sub.updateViewport(Viewport.builder().columns("symbol").build());
        assertThat(deliveries).hasSize(1); // no extra delivery
    }
}
