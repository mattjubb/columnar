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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EngineEndToEndTest {

    private static Schema tradesSchema() {
        return Schema.builder()
                .add("id", DataType.LONG)
                .add("symbol", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .build();
    }

    @Test
    void filterOverSealedTable() {
        BaseTable trades = Table.builder(tradesSchema())
                .appendRow(1L, "AAPL", 220.0)
                .appendRow(2L, "MSFT", 410.0)
                .appendRow(3L, "AAPL", 221.0)
                .appendRow(4L, "GOOG", 175.0)
                .appendRow(5L, "MSFT", 411.0)
                .build();

        Operator src = new SourceOperator(trades);
        Operator filt = new FilterOperator(src, RowPredicates.doubleGt("price", 300.0), "price>300");
        DerivedTable derived = new DerivedTable(filt);

        ColumnarSlice slice = derived.read();
        assertThat(slice.rowCount()).isEqualTo(2);
        // Verify only MSFT/MSFT survived (220, 221, 175 all <= 300).
        StringChunk syms = (StringChunk) slice.column("symbol").chunk(0);
        assertThat(syms.getString(0)).isEqualTo("MSFT");
        assertThat(syms.getString(1)).isEqualTo("MSFT");
    }

    @Test
    void filterOverOpenTableInvalidatesOnAppend() {
        BaseTable trades = Table.create(tradesSchema());
        trades.appendRow(1L, "AAPL", 220.0);
        trades.appendRow(2L, "MSFT", 410.0);
        trades.appendRow(3L, "AAPL", 221.0);

        Operator src = new SourceOperator(trades);
        Operator filt = new FilterOperator(src, RowPredicates.doubleGt("price", 300.0), "price>300");
        DerivedTable derived = new DerivedTable(filt);
        derived.wireListeners();

        // First read: only MSFT row passes.
        ColumnarSlice slice1 = derived.read();
        assertThat(slice1.rowCount()).isEqualTo(1);
        long version1 = derived.version();

        // Append a new row; derived table's cache should invalidate.
        trades.appendRow(4L, "MSFT", 411.0);
        long version2 = derived.version();
        assertThat(version2).isGreaterThan(version1);

        ColumnarSlice slice2 = derived.read();
        assertThat(slice2.rowCount()).isEqualTo(2);

        // And the old slice object should still be the old one (not auto-mutated).
        assertThat(slice1.rowCount()).isEqualTo(1);
    }

    @Test
    void cachedReadReturnsSameInstanceWhenUpstreamUnchanged() {
        BaseTable trades = Table.builder(tradesSchema())
                .appendRow(1L, "AAPL", 220.0)
                .appendRow(2L, "MSFT", 410.0)
                .build();

        Operator src = new SourceOperator(trades);
        Operator filt = new FilterOperator(src, RowPredicates.doubleGt("price", 0), "price>0");
        DerivedTable derived = new DerivedTable(filt);

        ColumnarSlice a = derived.read();
        ColumnarSlice b = derived.read();
        assertThat(b).isSameAs(a);
    }

    @Test
    void projectPushesColumnSelectionUpstream() {
        BaseTable trades = Table.builder(tradesSchema())
                .appendRow(1L, "AAPL", 220.0)
                .appendRow(2L, "MSFT", 410.0)
                .build();

        Operator src = new SourceOperator(trades);
        Operator proj = new ProjectOperator(src, List.of("symbol", "price"));
        DerivedTable derived = new DerivedTable(proj);

        ColumnarSlice slice = derived.read();
        assertThat(slice.columnCount()).isEqualTo(2);
        assertThat(slice.schema().names()).containsExactly("symbol", "price");
        StringChunk syms = (StringChunk) slice.column("symbol").chunk(0);
        assertThat(syms.getString(0)).isEqualTo("AAPL");
        DoubleChunk prices = (DoubleChunk) slice.column("price").chunk(0);
        assertThat(prices.getDouble(1)).isEqualTo(410.0);
    }

    @Test
    void filterThenProjectComposes() {
        BaseTable trades = Table.create(tradesSchema());
        for (int i = 0; i < 100; i++) {
            trades.appendRow((long) i, i % 2 == 0 ? "AAPL" : "MSFT", 100.0 + i);
        }

        Operator src = new SourceOperator(trades);
        Operator filt = new FilterOperator(src, RowPredicates.stringEq("symbol", "MSFT"), "sym=MSFT");
        Operator proj = new ProjectOperator(filt, List.of("id", "price"));
        DerivedTable derived = new DerivedTable(proj);
        derived.wireListeners();

        ColumnarSlice slice = derived.read();
        assertThat(slice.rowCount()).isEqualTo(50);
        assertThat(slice.columnCount()).isEqualTo(2);
        // First MSFT id is 1, then 3, 5, ...
        LongChunk ids = (LongChunk) slice.column("id").chunk(0);
        assertThat(ids.getLong(0)).isEqualTo(1L);
        assertThat(ids.getLong(1)).isEqualTo(3L);
        assertThat(ids.getLong(49)).isEqualTo(99L);
    }

    @Test
    void viewportLimitTrimsResults() {
        BaseTable trades = Table.builder(tradesSchema())
                .appendRow(1L, "AAPL", 220.0)
                .appendRow(2L, "MSFT", 410.0)
                .appendRow(3L, "AAPL", 221.0)
                .appendRow(4L, "GOOG", 175.0)
                .appendRow(5L, "MSFT", 411.0)
                .build();

        Operator src = new SourceOperator(trades);
        Operator filt = new FilterOperator(src, RowPredicates.doubleGt("price", 0), "price>0");
        DerivedTable derived = new DerivedTable(filt);

        ColumnarSlice slice = derived.read(Viewport.builder().limit(2).build());
        assertThat(slice.rowCount()).isEqualTo(2);
    }
}
