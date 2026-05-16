package io.columnar.memory;

import io.columnar.core.Residency;
import io.columnar.core.Validity;
import io.columnar.core.chunk.HotDoubleChunk;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WarmDoubleChunkTest {

    @Test
    void warmLayoutPromotesBackToHot() {
        try (Arena arena = Arena.ofConfined()) {
            double[] vals = new double[]{1.0, 2.5, -3.0};
            MemorySegment packed = DoubleColdLayout.allocateAndWrite(arena, vals, vals.length, Validity.allValid(vals.length));

            WarmDoubleChunk cold = WarmDoubleChunk.fromHot(packed);

            HotDoubleChunk hotAgain = cold.promoteToHot();
            assertThat(hotAgain.residency()).isEqualTo(Residency.HOT);
            assertThat(hotAgain.size()).isEqualTo(vals.length);
            assertThat(hotAgain.getDouble(1)).isEqualTo(2.5);

            assertThat(cold.residency()).isEqualTo(Residency.WARM);
        }
    }
}
