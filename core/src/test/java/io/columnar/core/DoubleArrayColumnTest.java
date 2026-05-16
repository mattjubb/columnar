package io.columnar.core;

import io.columnar.core.chunk.DoubleArrayChunk;
import io.columnar.core.chunk.HotDoubleArrayChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoubleArrayColumnTest {

    @Test
    void builderStoresAndReadsFixedDoubleArrays() {
        Schema schema =
                Schema.builder()
                        .add("id", DataType.LONG)
                        .add("curve", DataType.DOUBLE_ARRAY, 3)
                        .build();

        BaseTable t =
                Table.builder(schema)
                        .row()
                        .setLong(0, 1L)
                        .setDoubleArray(1, new double[] {1.0, 2.0, 3.0})
                        .commit()
                        .row()
                        .setLong(0, 2L)
                        .setDoubleArray(1, new double[] {-1.0, 0.0, 1.5})
                        .commit()
                        .build();

        ColumnarSlice slice = t.read();
        assertThat(slice.rowCount()).isEqualTo(2);

        Column curve = slice.column("curve");
        assertThat(curve.type()).isEqualTo(DataType.DOUBLE_ARRAY);
        DoubleArrayChunk chunk = (DoubleArrayChunk) curve.chunk(0);
        assertThat(chunk.elementsPerRow()).isEqualTo(3);
        assertThat(chunk.getDouble(0, 0)).isEqualTo(1.0);
        assertThat(chunk.getDouble(1, 2)).isEqualTo(1.5);
    }

    @Test
    void wrongArrayLengthRejectedOnAppend() {
        Schema schema = Schema.builder().add("x", DataType.DOUBLE_ARRAY, 2).build();
        BaseTable.Builder b = Table.builder(schema);
        assertThatThrownBy(() -> b.row().setDoubleArray(0, new double[] {1.0}).commit())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elementsPerRow");
    }

    @Test
    void hotChunkPacksRowMajor() {
        HotDoubleArrayChunk c = HotDoubleArrayChunk.of(new double[] {1, 2, 3, 4}, 2);
        assertThat(c.size()).isEqualTo(2);
        assertThat(c.getDouble(1, 1)).isEqualTo(4.0);
    }
}
