package io.columnar.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidityTest {

    @Test
    void allValidStartsWithNoNulls() {
        Validity v = Validity.allValid(100);
        assertThat(v.size()).isEqualTo(100);
        assertThat(v.nullCount()).isZero();
        assertThat(v.hasNulls()).isFalse();
        for (int i = 0; i < 100; i++) {
            assertThat(v.isValid(i)).as("row %d", i).isTrue();
        }
    }

    @Test
    void allNullStartsWithEverythingNull() {
        Validity v = Validity.allNull(70);
        assertThat(v.size()).isEqualTo(70);
        assertThat(v.nullCount()).isEqualTo(70);
        for (int i = 0; i < 70; i++) {
            assertThat(v.isNull(i)).as("row %d", i).isTrue();
        }
    }

    @Test
    void setNullThenSetValidTracksCount() {
        Validity v = Validity.allValid(8);
        assertThat(v.setNull(3)).isTrue();
        assertThat(v.setNull(3)).isFalse(); // idempotent
        assertThat(v.nullCount()).isEqualTo(1);
        assertThat(v.isValid(3)).isFalse();
        assertThat(v.isValid(2)).isTrue();
        assertThat(v.setValid(3)).isTrue();
        assertThat(v.nullCount()).isZero();
    }

    @Test
    void crossesWordBoundaries() {
        Validity v = Validity.allValid(200);
        v.setNull(63);
        v.setNull(64);
        v.setNull(127);
        v.setNull(128);
        assertThat(v.nullCount()).isEqualTo(4);
        assertThat(v.isNull(63)).isTrue();
        assertThat(v.isNull(64)).isTrue();
        assertThat(v.isNull(127)).isTrue();
        assertThat(v.isNull(128)).isTrue();
        assertThat(v.isValid(65)).isTrue();
        assertThat(v.isValid(126)).isTrue();
    }

    @Test
    void rejectsNegativeSize() {
        assertThatThrownBy(() -> Validity.allValid(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsCheckedOnAccess() {
        Validity v = Validity.allValid(4);
        assertThatThrownBy(() -> v.isValid(4)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> v.setNull(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
