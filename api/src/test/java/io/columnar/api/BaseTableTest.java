package io.columnar.api;

import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.LongChunk;
import io.columnar.api.chunk.StringChunk;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaseTableTest {

    private static Schema tradesSchema() {
        return Schema.builder()
                .add("id", DataType.LONG)
                .add("symbol", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .build();
    }

    // ----- sealed (builder-built) path ---------------------------------------

    private static BaseTable sealedTrades() {
        return Table.builder(tradesSchema())
                .appendRow(1L, "AAPL", 220.13)
                .appendRow(2L, "MSFT", 410.50)
                .appendRow(3L, "AAPL", 221.00)
                .appendRow(4L, "GOOG", 175.20)
                .appendRow(5L, "MSFT", 411.00)
                .build();
    }

    @Test
    void sealedTableReadAllReturnsEverything() {
        BaseTable t = sealedTrades();
        ColumnarSlice slice = t.read();
        assertThat(slice.rowCount()).isEqualTo(5);
        assertThat(slice.columnCount()).isEqualTo(3);
        assertThat(slice.version()).isZero();
        assertThat(((LongChunk) slice.column("id").chunk(0)).getLong(0)).isEqualTo(1L);
        assertThat(((LongChunk) slice.column("id").chunk(0)).getLong(4)).isEqualTo(5L);
        assertThat(((StringChunk) slice.column("symbol").chunk(0)).getString(0)).isEqualTo("AAPL");
    }

    @Test
    void rowRangeViewportSlices() {
        BaseTable t = sealedTrades();
        ColumnarSlice slice = t.read(Viewport.rows(RowRange.of(1, 4)));
        assertThat(slice.rowCount()).isEqualTo(3);
        assertThat(((LongChunk) slice.column("id").chunk(0)).getLong(0)).isEqualTo(2L);
        assertThat(((LongChunk) slice.column("id").chunk(0)).getLong(2)).isEqualTo(4L);
    }

    @Test
    void columnSubsetViewportProjects() {
        BaseTable t = sealedTrades();
        ColumnarSlice slice = t.read(Viewport.builder()
                .columns(Set.of("symbol", "price"))
                .build());
        assertThat(slice.columnCount()).isEqualTo(2);
        assertThat(slice.schema().names()).containsExactlyInAnyOrder("symbol", "price");
    }

    @Test
    void limitCapsRows() {
        BaseTable t = sealedTrades();
        ColumnarSlice slice = t.read(Viewport.builder().limit(2).build());
        assertThat(slice.rowCount()).isEqualTo(2);
    }

    @Test
    void typedRowAppender() {
        Schema schema = Schema.builder()
                .add("a", DataType.LONG)
                .add("b", DataType.DOUBLE)
                .build();
        BaseTable.Builder b = Table.builder(schema);
        b.row().setLong(0, 7L).setDouble(1, 0.5).commit();
        b.row().setLong(0, 8L).setDouble(1, 1.5).commit();
        BaseTable t = b.build();

        assertThat(t.size()).isEqualTo(2);
        assertThat(t.isSealed()).isTrue();
        ColumnarSlice slice = t.read();
        assertThat(((LongChunk) slice.column("a").chunk(0)).getLong(0)).isEqualTo(7L);
        assertThat(((DoubleChunk) slice.column("b").chunk(0)).getDouble(1)).isEqualTo(1.5);
    }

    @Test
    void unsetColumnsBecomeNull() {
        Schema schema = Schema.builder()
                .add("a", DataType.LONG)
                .add("b", DataType.DOUBLE)
                .build();
        BaseTable.Builder b = Table.builder(schema);
        b.row().setLong(0, 1L).commit(); // b unset → null
        BaseTable t = b.build();
        ColumnarSlice slice = t.read();
        assertThat(slice.column("b").chunk(0).validity().isNull(0)).isTrue();
    }

    @Test
    void appendingToSealedTableThrows() {
        BaseTable t = sealedTrades();
        assertThatThrownBy(() -> t.appendRow(99L, "ZZZ", 1.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sealed");
    }

    // ----- open (live) path --------------------------------------------------

    @Test
    void appendsBumpVersion() {
        BaseTable t = Table.create(tradesSchema());
        assertThat(t.isSealed()).isFalse();
        assertThat(t.version()).isZero();
        t.appendRow(1L, "AAPL", 220.0);
        assertThat(t.version()).isEqualTo(1L);
        assertThat(t.size()).isEqualTo(1L);
        t.appendRow(2L, "MSFT", 410.0);
        assertThat(t.version()).isEqualTo(2L);
    }

    @Test
    void readReflectsCurrentSnapshot() {
        BaseTable t = Table.create(tradesSchema());
        t.appendRow(1L, "AAPL", 220.0);
        t.appendRow(2L, "MSFT", 410.0);
        ColumnarSlice slice = t.read();
        assertThat(slice.rowCount()).isEqualTo(2);
        assertThat(slice.version()).isEqualTo(2L);
        assertThat(((LongChunk) slice.column("id").chunk(0)).getLong(0)).isEqualTo(1L);
        assertThat(((LongChunk) slice.column("id").chunk(0)).getLong(1)).isEqualTo(2L);
    }

    @Test
    void snapshotsAreImmutableAcrossAppends() {
        BaseTable t = Table.create(tradesSchema());
        t.appendRow(1L, "AAPL", 220.0);
        ColumnarSlice early = t.read();
        long earlyVersion = early.version();

        t.appendRow(2L, "MSFT", 410.0);
        ColumnarSlice late = t.read();

        assertThat(early.rowCount()).isEqualTo(1L);
        assertThat(early.version()).isEqualTo(earlyVersion);
        assertThat(late.rowCount()).isEqualTo(2L);
        assertThat(late.version()).isEqualTo(2L);
    }

    @Test
    void listenersFireOnEachAppend() {
        BaseTable t = Table.create(tradesSchema());
        AtomicLong lastSeen = new AtomicLong();
        t.addListener(lastSeen::set);
        t.appendRow(1L, "AAPL", 220.0);
        assertThat(lastSeen.get()).isEqualTo(1L);
        t.appendRow(2L, "MSFT", 410.0);
        assertThat(lastSeen.get()).isEqualTo(2L);
    }

    @Test
    void sealStopsVersionFromAdvancing() {
        BaseTable t = Table.create(tradesSchema());
        t.appendRow(1L, "AAPL", 220.0);
        long v = t.version();
        t.seal();
        assertThat(t.isSealed()).isTrue();
        assertThat(t.version()).isEqualTo(v); // no change
        assertThatThrownBy(() -> t.appendRow(2L, "MSFT", 410.0))
                .isInstanceOf(IllegalStateException.class);
    }
}
