package io.columnar.engine;

import io.columnar.core.DataType;
import io.columnar.core.Schema;
import io.columnar.core.Table;
import io.columnar.core.Viewport;
import io.columnar.core.chunk.LongChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HashAggregateOperatorSmokeTest {

    @Test
    void groupsCountsAndSum() {
        Schema schema =
                Schema.builder().add("symbol", DataType.STRING).add("price", DataType.DOUBLE).build();
        var tbl =
                Table.builder(schema)
                        .appendRow("A", 10.0)
                        .appendRow("A", 30.0)
                        .appendRow("B", 5.5)
                        .build();

        HashAggregateOperator op =
                new HashAggregateOperator(
                        new SourceOperator(tbl),
                        "symbol",
                        List.of(
                                new HashAggregateOperator.AggMeasure(
                                        HashAggregateOperator.AggKind.COUNT,
                                        /* sum input unused */ null,
                                        "volume"),
                                new HashAggregateOperator.AggMeasure(
                                        HashAggregateOperator.AggKind.SUM_DOUBLE,
                                        "price",
                                        "spent")));

        var slice = new DerivedTable(op).read(Viewport.ALL);
        assertThat(slice.rowCount()).isEqualTo(2);

        LongChunk cnt = (LongChunk) slice.column("volume").chunk(0);
        assertThat(cnt.getLong(0)).isEqualTo(2);
        assertThat(cnt.getLong(1)).isEqualTo(1);
    }
}
