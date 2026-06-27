package io.columnar.core;

import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PivotKeysTest {

    @Test
    void discoversPivotValuesInChunkOrder() {
        Schema schema =
                Schema.builder().add("rk", DataType.STRING).add("pv", DataType.STRING).build();
        var tbl =
                Table.builder(schema)
                        .appendRow("a", "east")
                        .appendRow("a", "west")
                        .appendRow("b", "east")
                        .build();
        var keys = PivotKeys.discoverStringKeys(tbl.read(Viewport.ALL).column("pv"));
        assertThat(keys).isEqualTo(List.of("east", "west"));
    }
}
