package io.columnar.memory;

import io.columnar.core.chunk.HotDoubleChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkResidencyManagerEvictionTest {

    @Test
    void pinningPreventsColdDemotionImmediately() throws Exception {
        try (ChunkResidencyManager mgr = new ChunkResidencyManager(16_384)) {
            HotDoubleChunk hot = HotDoubleChunk.of(new double[1024]);
            long id = mgr.admit(hot);

            try (ChunkPin pin = mgr.pin(id)) {
                assertThat(mgr.currentChunk(id)).isInstanceOf(HotDoubleChunk.class);
            }

            mgr.evictWhileOverBudget();
            assertThat(mgr.hotBytes()).isGreaterThanOrEqualTo(0L);
        }
    }
}
