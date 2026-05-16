package io.columnar.engine;

import io.columnar.core.BaseTable;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.DataType;
import io.columnar.core.Schema;
import io.columnar.core.Table;
import io.columnar.core.Viewport;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.StringChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashJoinLeftOuterTest {

    private static BaseTable symbolsTable() {
        Schema schema = Schema.builder().add("symbol", DataType.STRING).add("sector", DataType.STRING).build();
        return Table.builder(schema)
                .appendRow("AAPL", "TECH")
                .appendRow("MSFT", "TECH")
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
    void leftOuterRetainsTradesWithoutSymbols() {
        BaseTable symbols = symbolsTable();
        BaseTable trades = Table.create(tradesSchema());
        trades.appendRow(1L, "AAPL", 10.0);
        trades.appendRow(2L, "GHOST", 20.0); // no lookup => must appear with null sector

        Operator join =
                new HashJoinOperator(
                        new SourceOperator(trades),
                        new SourceOperator(symbols),
                        "symbol",
                        "symbol",
                        JoinKind.LEFT);

        ColumnarSlice slice = new DerivedTable(join).read(Viewport.ALL);
        assertThat(slice.rowCount()).isEqualTo(2);
        StringChunk sym = (StringChunk) slice.column("symbol").chunk(0);
        assertThat(sym.getString(0)).isEqualTo("AAPL");
        assertThat(sym.getString(1)).isEqualTo("GHOST");
        assertThat(((DoubleChunk) slice.column("price").chunk(0)).getDouble(1)).isEqualTo(20.0);

        StringChunk sector = (StringChunk) slice.column("sector").chunk(0);
        assertThat(sector.validity().isNull(1)).isTrue();
    }
}
