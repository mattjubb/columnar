package io.columnar.query;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TickCoordinatorSmokeTest {

    @Test
    void drainProcessesQueue() {
        TickCoordinator tick = new TickCoordinator();
        try {
            AtomicInteger counter = new AtomicInteger();
            tick.enqueue(counter::incrementAndGet);
            tick.drainBlocking();
            assertThat(counter.get()).isEqualTo(1);
        } finally {
            tick.close();
        }
    }
}
