package io.columnar.engine;

import io.columnar.core.BaseTable;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.DataType;
import io.columnar.core.Schema;
import io.columnar.core.Table;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.StringChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end perf-leaning test: 1,000,000 rows, 5 columns, pivot a string
 * column with 20 unique values into 20 output columns summing a double value
 * column, grouped by a row-key column with 5 unique values.
 *
 * <p>Verifies correctness against an in-test reference computation and prints
 * timings for visibility.
 */
class LargePivotTest {

    private static final int ROWS = 1_000_000;

    private static final String[] REGIONS = {
            "US", "EU", "APAC", "LATAM", "AFRICA"
    };

    private static final String[] CATEGORIES = new String[20];
    static {
        for (int i = 0; i < CATEGORIES.length; i++) {
            CATEGORIES[i] = "cat" + i;
        }
    }

    @Test
    void onMillionRowsAcrossTwentyPivotKeys() {
        // Build the source table: 1M rows × 5 cols (id, region, category, price, qty).
        Schema schema = Schema.builder()
                .add("id", DataType.LONG)
                .add("region", DataType.STRING)
                .add("category", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .add("qty", DataType.LONG)
                .build();

        long buildStart = System.nanoTime();
        BaseTable.Builder b = Table.builder(schema);
        SplittableRandom rng = new SplittableRandom(42);

        // Resolve schema column indexes once (avoids name-lookup per row).
        int idCol = schema.indexOf("id");
        int regionCol = schema.indexOf("region");
        int categoryCol = schema.indexOf("category");
        int priceCol = schema.indexOf("price");
        int qtyCol = schema.indexOf("qty");

        // Reference accumulator: region -> category -> sum(price).
        Map<String, double[]> reference = new HashMap<>();
        for (String r : REGIONS) {
            reference.put(r, new double[CATEGORIES.length]);
        }

        // Hot-path: typed setters via RowAppender — no boxing, no Object[] vararg.
        for (int i = 0; i < ROWS; i++) {
            int regionIdx = rng.nextInt(REGIONS.length);
            int catIdx = rng.nextInt(CATEGORIES.length);
            String region = REGIONS[regionIdx];
            String category = CATEGORIES[catIdx];
            double price = rng.nextDouble() * 1000.0;
            long qty = 1L + rng.nextLong(100L);

            b.row()
                    .setLong(idCol, i)
                    .setString(regionCol, region)
                    .setString(categoryCol, category)
                    .setDouble(priceCol, price)
                    .setLong(qtyCol, qty)
                    .commit();

            reference.get(region)[catIdx] += price;
        }
        BaseTable trades = b.build();
        long buildMs = (System.nanoTime() - buildStart) / 1_000_000L;

        assertThat(trades.size()).isEqualTo(ROWS);

        // Build the pivot: rows=region, columns=category (20 keys), values=sum(price).
        List<String> pivotKeys = new ArrayList<>(CATEGORIES.length);
        for (String c : CATEGORIES) pivotKeys.add(c);

        Operator src = new SourceOperator(trades);
        Operator pivot = new PivotOperator(src, "region", "category", "price", pivotKeys);
        DerivedTable pivoted = new DerivedTable(pivot);

        long pivotStart = System.nanoTime();
        ColumnarSlice slice = pivoted.read();
        long pivotMs = (System.nanoTime() - pivotStart) / 1_000_000L;

        long pivotWarmStart = System.nanoTime();
        ColumnarSlice warm = pivoted.read();
        long pivotWarmMs = (System.nanoTime() - pivotWarmStart) / 1_000_000L;

        System.out.println("LargePivotTest: build " + ROWS + " rows took " + buildMs
                + "ms; pivot cold " + pivotMs + "ms, warm " + pivotWarmMs + "ms");
        // Dump the pivot output so it's visible in test logs.
        slice.prettyPrint();

        // Schema: 1 row-key column (region) + 20 category columns.
        assertThat(slice.schema().size()).isEqualTo(1 + CATEGORIES.length);
        assertThat(slice.schema().names().get(0)).isEqualTo("region");
        for (int p = 0; p < CATEGORIES.length; p++) {
            assertThat(slice.schema().names().get(1 + p)).isEqualTo(CATEGORIES[p]);
        }

        // 5 output rows (one per region).
        assertThat(slice.rowCount()).isEqualTo(REGIONS.length);

        // Build the output rows by region for assertions.
        StringChunk outRegion = (StringChunk) slice.column("region").chunk(0);
        Map<String, Integer> regionRowIdx = new HashMap<>();
        for (int i = 0; i < slice.rowCount(); i++) {
            regionRowIdx.put(outRegion.getString(i), i);
        }
        assertThat(regionRowIdx.keySet())
                .containsExactlyInAnyOrder(REGIONS);

        // Verify every cell against the reference.
        double tolerance = 1e-6;
        for (String region : REGIONS) {
            int rowIdx = regionRowIdx.get(region);
            for (int catIdx = 0; catIdx < CATEGORIES.length; catIdx++) {
                String catName = CATEGORIES[catIdx];
                DoubleChunk col = (DoubleChunk) slice.column(catName).chunk(0);
                double actual = col.getDouble(rowIdx);
                double expected = reference.get(region)[catIdx];
                assertThat(actual)
                        .as("region=%s category=%s", region, catName)
                        .isCloseTo(expected, org.assertj.core.api.Assertions.within(tolerance));
            }
        }

        // Cached/warm read returns the same instance because no upstream changes occurred.
        assertThat(warm).isSameAs(slice);
    }

    @Test
    void pivotViewportProjectsToSubsetOfPivotColumns() {
        // Smaller dataset for this one — focus on viewport behavior.
        Schema schema = Schema.builder()
                .add("region", DataType.STRING)
                .add("category", DataType.STRING)
                .add("price", DataType.DOUBLE)
                .build();
        BaseTable.Builder b = Table.builder(schema);
        b.appendRow("US", "cat1", 10.0);
        b.appendRow("US", "cat2", 20.0);
        b.appendRow("EU", "cat1", 30.0);
        b.appendRow("EU", "cat2", 40.0);
        BaseTable t = b.build();

        Operator src = new SourceOperator(t);
        Operator pivot = new PivotOperator(src, "region", "category", "price",
                List.of("cat1", "cat2", "cat3"));
        DerivedTable pivoted = new DerivedTable(pivot);

        // Request just region + cat1 columns from the pivot.
        ColumnarSlice slice = pivoted.read(io.columnar.core.Viewport.builder()
                .columns("region", "cat1")
                .build());
        assertThat(slice.schema().names()).containsExactly("region", "cat1");
        assertThat(slice.rowCount()).isEqualTo(2);
    }
}
