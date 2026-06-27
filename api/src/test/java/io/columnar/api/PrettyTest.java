package io.columnar.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrettyTest {

    @Test
    void formatsBasicSlice() {
        Schema schema = Schema.builder()
                .add("id", DataType.LONG)
                .add("symbol", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .build();
        BaseTable t = Table.builder(schema)
                .appendRow(1L, "AAPL", 220.13)
                .appendRow(2L, "MSFT", 410.50)
                .appendRow(3L, "GOOG", 175.20)
                .build();

        ColumnarSlice slice = t.read();
        String out = slice.toPrettyString();

        // Smoke checks: every column header and every value appears.
        assertThat(out).contains("id").contains("symbol").contains("price");
        assertThat(out).contains("AAPL").contains("MSFT").contains("GOOG");
        assertThat(out).contains("220.13").contains("410.5").contains("175.2");
        assertThat(out).contains("LONG").contains("STRING").contains("DOUBLE");
        assertThat(out).contains("3 rows × 3 columns");

        // Also print so we can eyeball it.
        System.out.println(out);
    }

    @Test
    void rendersNullsAsWord() {
        Schema schema = Schema.builder()
                .add("a", DataType.LONG)
                .add("b", DataType.STRING)
                .build();
        BaseTable.Builder b = Table.builder(schema);
        b.row().setLong(0, 1L).setString(1, "x").commit();
        b.row().setLong(0, 2L).commit(); // b unset → null
        BaseTable t = b.build();
        String out = t.read().toPrettyString();
        assertThat(out).contains("null");
    }

    @Test
    void truncatesLongTablesWithFooter() {
        Schema schema = Schema.builder().add("n", DataType.LONG).build();
        BaseTable.Builder b = Table.builder(schema);
        for (int i = 0; i < 100; i++) {
            b.row().setLong(0, i).commit();
        }
        BaseTable t = b.build();
        String out = t.read().toPrettyString(10, 10, 40);
        assertThat(out).contains("more row");
        assertThat(out).contains("100 rows");
    }

    @Test
    void formatColumnOnly() {
        Schema schema = Schema.builder().add("name", DataType.STRING).build();
        BaseTable t = Table.builder(schema)
                .appendRow("alpha")
                .appendRow("beta")
                .appendRow("gamma")
                .build();
        Column col = t.read().column("name");
        String out = col.toPrettyString();
        assertThat(out).contains("alpha").contains("beta").contains("gamma");
        assertThat(out).contains("STRING");
    }
}
