package io.columnar.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaTest {

    @Test
    void buildsAndIndexes() {
        Schema s = Schema.builder()
                .add("id", DataType.LONG)
                .add("name", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .build();
        assertThat(s.size()).isEqualTo(3);
        assertThat(s.indexOf("name")).isEqualTo(1);
        assertThat(s.field(2).type()).isEqualTo(DataType.DOUBLE);
        assertThat(s.names()).containsExactly("id", "name", "price");
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> Schema.builder()
                .add("a", DataType.INT)
                .add("a", DataType.LONG)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void selectReorders() {
        Schema s = Schema.builder()
                .add("a", DataType.INT)
                .add("b", DataType.STRING)
                .add("c", DataType.DOUBLE)
                .build();
        Schema sub = s.select(List.of("c", "a"));
        assertThat(sub.names()).containsExactly("c", "a");
        assertThat(sub.field(0).type()).isEqualTo(DataType.DOUBLE);
    }
}
