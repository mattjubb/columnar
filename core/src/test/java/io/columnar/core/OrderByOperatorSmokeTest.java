package io.columnar.core;

import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.LongChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderByOperatorSmokeTest {

    @Test
    void sortsByLongAscending() {
        Schema schema = Schema.builder().add("rank", DataType.LONG).add("label", DataType.STRING).build();
        Table tbl =
                Table.builder(schema)
                        .appendRow(30L, "c")
                        .appendRow(10L, "a")
                        .appendRow(20L, "b")
                        .build();

        Operator src = new SourceOperator(tbl);
        Operator ordered = new OrderByOperator(src, "rank", true);
        var slice = new DerivedTable(ordered).read(Viewport.ALL);

        LongChunk rk = (LongChunk) slice.column("rank").chunk(0);
        assertThat(rk.getLong(0)).isEqualTo(10);
        assertThat(rk.getLong(1)).isEqualTo(20);
        assertThat(rk.getLong(2)).isEqualTo(30);
    }
}
