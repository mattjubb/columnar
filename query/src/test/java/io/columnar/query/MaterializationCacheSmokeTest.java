package io.columnar.query;

import io.columnar.api.BaseTable;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.core.SourceOperator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaterializationCacheSmokeTest {

    @Test
    void entriesAreStableAcrossIdenticalReads() {
        Schema schema = Schema.builder().add("x", DataType.LONG).build();
        CachedDerived cd = CachedDerived.of(new SourceOperator(Table.builder(schema).appendRow(1L).build()));
        cd.table().read(Viewport.ALL);
        int afterFirst = cd.cache().approximateSize();
        cd.table().read(Viewport.ALL);
        assertThat(cd.cache().approximateSize()).isEqualTo(afterFirst).isGreaterThan(0);
    }

    /**
     * One slot per (upstream table identity, viewport). A version bump does not allocate a second
     * map entry — the existing entry is replaced when {@link MaterializationCache#read} observes a
     * newer {@link Table#version()}.
     */
    @Test
    void versionBumpRefreshesCacheEntryKeepingSlotCount() {
        Schema schema = Schema.builder().add("x", DataType.LONG).build();
        BaseTable live = Table.create(schema);
        live.appendRow(1L);

        CachedDerived cd = CachedDerived.of(new SourceOperator(live));
        ColumnarSlice beforeBump = cd.table().read(Viewport.ALL);
        int baseline = cd.cache().approximateSize();

        live.appendRow(2L);
        ColumnarSlice afterBump = cd.table().read(Viewport.ALL);

        assertThat(cd.cache().approximateSize()).isEqualTo(baseline).isGreaterThan(0);
        assertThat(beforeBump.rowCount()).isOne();
        assertThat(afterBump.rowCount()).isEqualTo(2);
    }
}
