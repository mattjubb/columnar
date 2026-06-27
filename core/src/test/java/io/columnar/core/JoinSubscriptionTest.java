package io.columnar.core;

import io.columnar.api.BaseTable;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Subscription;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.LongChunk;
import io.columnar.api.chunk.StringChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates the full forward-propagation story across two source tables of
 * different kinds:
 *
 * <pre>
 *   sealed BaseTable symbols  (reference data; never bumps)
 *   open BaseTable   trades   (appended over time; version bumps per append)
 *                 │
 *                 ▼
 *           HashJoinOperator (on "symbol")
 *                 │
 *                 ▼
 *           DerivedTable
 *                 │ subscribe(viewport, cb)
 *                 ▼
 *             test callback
 * </pre>
 *
 * Trades flow in over time; the subscriber on the joined derived table sees
 * each new join row (trade enriched with sector) propagate through.
 */
class JoinSubscriptionTest {

    private static BaseTable symbolsTable() {
        Schema schema = Schema.builder()
                .add("symbol", DataType.STRING)
                .add("sector", DataType.STRING)
                .build();
        return Table.builder(schema)
                .appendRow("AAPL", "TECH")
                .appendRow("MSFT", "TECH")
                .appendRow("GOOG", "TECH")
                .appendRow("XOM",  "ENERGY")
                .appendRow("JPM",  "FIN")
                .build();
    }

    private static Schema tradesSchema() {
        return Schema.builder()
                .add("id", DataType.LONG)
                .add("symbol", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .build();
    }

    @Test
    void liveJoinedToStaticPropagatesAppendsToSubscriber() {
        BaseTable symbols = symbolsTable();
        BaseTable trades = Table.create(tradesSchema());

        Operator probe = new SourceOperator(trades);   // open / live
        Operator buildSide = new SourceOperator(symbols); // sealed / static
        Operator join = new HashJoinOperator(probe, buildSide, "symbol", "symbol");
        DerivedTable enriched = new DerivedTable(join);

        // Sanity: join output schema is (id, symbol, price, sector).
        assertThat(enriched.schema().names()).containsExactly("id", "symbol", "price", "sector");

        List<ColumnarSlice> deliveries = new CopyOnWriteArrayList<>();
        try (Subscription sub = enriched.subscribe(Viewport.ALL, deliveries::add)) {
            assertThat(sub.isActive()).isTrue();

            // Initial delivery: trades is empty → join is empty.
            assertThat(deliveries).hasSize(1);
            assertThat(deliveries.get(0).rowCount()).isZero();

            // First append: AAPL trade.
            trades.appendRow(1L, "AAPL", 220.0);
            assertThat(deliveries).hasSize(2);
            ColumnarSlice d1 = deliveries.get(1);
            assertThat(d1.rowCount()).isEqualTo(1);
            assertThat(((LongChunk) d1.column("id").chunk(0)).getLong(0)).isEqualTo(1L);
            assertThat(((StringChunk) d1.column("symbol").chunk(0)).getString(0)).isEqualTo("AAPL");
            assertThat(((DoubleChunk) d1.column("price").chunk(0)).getDouble(0)).isEqualTo(220.0);
            assertThat(((StringChunk) d1.column("sector").chunk(0)).getString(0)).isEqualTo("TECH");

            // Second append: XOM trade (different sector).
            trades.appendRow(2L, "XOM", 105.5);
            assertThat(deliveries).hasSize(3);
            ColumnarSlice d2 = deliveries.get(2);
            assertThat(d2.rowCount()).isEqualTo(2);
            assertThat(((StringChunk) d2.column("sector").chunk(0)).getString(1)).isEqualTo("ENERGY");

            // Third append: a symbol that is NOT in the static reference table.
            // Inner-join semantics: this row is dropped, so the subscriber sees no
            // row-count change, but the version still advances (any append fires
            // the listener).
            trades.appendRow(3L, "UNKNOWN", 999.0);
            assertThat(deliveries).hasSize(4);
            ColumnarSlice d3 = deliveries.get(3);
            assertThat(d3.rowCount()).isEqualTo(2);
            assertThat(d3.version()).isGreaterThan(d2.version());

            // Fourth append: another tech symbol.
            trades.appendRow(4L, "MSFT", 410.0);
            assertThat(deliveries).hasSize(5);
            ColumnarSlice d4 = deliveries.get(4);
            assertThat(d4.rowCount()).isEqualTo(3);
            assertThat(((StringChunk) d4.column("symbol").chunk(0)).getString(2)).isEqualTo("MSFT");
            assertThat(((StringChunk) d4.column("sector").chunk(0)).getString(2)).isEqualTo("TECH");

            // Dump the final state to stdout for visibility.
            System.out.println("Final joined slice after 4 appends:");
            d4.prettyPrint();
        }

        // After close, further appends should not fire the subscriber.
        int sizeAtClose = deliveries.size();
        trades.appendRow(5L, "GOOG", 170.0);
        assertThat(deliveries).hasSize(sizeAtClose);
    }

    @Test
    void subscribeToColumnAndLimitViewportPropagates() {
        BaseTable symbols = symbolsTable();
        BaseTable trades = Table.create(tradesSchema());

        Operator join = new HashJoinOperator(
                new SourceOperator(trades),
                new SourceOperator(symbols),
                "symbol", "symbol");
        DerivedTable enriched = new DerivedTable(join);

        // Subscribe to a viewport that wants only [symbol, sector] of the first 2 rows.
        List<ColumnarSlice> deliveries = new CopyOnWriteArrayList<>();
        Viewport vp = Viewport.builder()
                .columns("symbol", "sector")
                .limit(2)
                .build();
        try (Subscription sub = enriched.subscribe(vp, deliveries::add)) {
            assertThat(sub.viewport()).isSameAs(vp);

            // Initial empty delivery.
            assertThat(deliveries).hasSize(1);
            assertThat(deliveries.get(0).schema().names()).containsExactly("symbol", "sector");
            assertThat(deliveries.get(0).rowCount()).isZero();

            trades.appendRow(10L, "JPM",  100.0);
            trades.appendRow(11L, "AAPL", 200.0);
            trades.appendRow(12L, "MSFT", 300.0); // capped by limit=2

            assertThat(deliveries).hasSize(4); // 1 initial + 3 appends
            ColumnarSlice last = deliveries.get(deliveries.size() - 1);
            assertThat(last.columnCount()).isEqualTo(2);
            assertThat(last.rowCount()).isEqualTo(2);
            assertThat(((StringChunk) last.column("symbol").chunk(0)).getString(0)).isEqualTo("JPM");
            assertThat(((StringChunk) last.column("sector").chunk(0)).getString(0)).isEqualTo("FIN");
            assertThat(((StringChunk) last.column("symbol").chunk(0)).getString(1)).isEqualTo("AAPL");
            assertThat(((StringChunk) last.column("sector").chunk(0)).getString(1)).isEqualTo("TECH");
        }
    }
}
