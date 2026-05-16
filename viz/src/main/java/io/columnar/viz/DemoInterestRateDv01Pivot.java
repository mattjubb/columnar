package io.columnar.viz;

import io.columnar.core.Viewport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Demo: wide DV01 sensitivities ({@code trades} + one DOUBLE column per IR bucket), sampled
 * in the browser only through scrolled row windows ({@linkplain AgGridTableVisualizer}),
 * while <b>every trade</b> is shocked on each scheduled tick ({@linkplain
 * IrDv01WideBookTable#applyGlobalTick}).
 *
 * <p>Pivot is modeled directly as the stored wide schema (rather than rebuilding a pivot
 * from 1.5M long-form rows each refresh), so ticking all cells stays practical.
 *
 * <p><b>Run:</b> {@code ./gradlew :viz:run}
 */
public final class DemoInterestRateDv01Pivot {

    private static final Logger log = LoggerFactory.getLogger(DemoInterestRateDv01Pivot.class);

    private static final int TICK_SECONDS = 5;

    /** Fifteen tenor buckets spanning money-market through long end. */
    public static final List<String> BUCKETS =
            List.of(
                    "3M",
                    "6M",
                    "9M",
                    "1Y",
                    "18M",
                    "2Y",
                    "3Y",
                    "4Y",
                    "5Y",
                    "7Y",
                    "10Y",
                    "12Y",
                    "15Y",
                    "20Y",
                    "30Y");

    private static final int TRADE_COUNT = 100_000;

    public static void main(String[] args) {
        java.util.SplittableRandom rng = new java.util.SplittableRandom(2026);

        System.out.println(
                "Building wide IR DV01 book: "
                        + TRADE_COUNT
                        + " trades × "
                        + BUCKETS.size()
                        + " buckets…");
        IrDv01WideBookTable book =
                new IrDv01WideBookTable(BUCKETS, TRADE_COUNT, rng, "T");

        ScheduledExecutorService tick =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual().factory());
        tick.scheduleAtFixedRate(
                () -> {
                    try {
                        log.debug("scheduled tick firing (every {} s)", TICK_SECONDS);
                        book.applyGlobalTick(rng);
                    } catch (RuntimeException ex) {
                        log.warn("tick failed", ex);
                    }
                },
                TICK_SECONDS,
                TICK_SECONDS,
                TimeUnit.SECONDS);
        log.info(
                "DV01 book tick scheduled every {} seconds (SSE clients should see invalidate + refetch)",
                TICK_SECONDS);

        try {
            book.show(Viewport.ALL);
        } finally {
            tick.shutdownNow();
        }
        System.out.println("Demo shutdown complete.");
    }
}
