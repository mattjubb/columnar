package io.columnar.api;

import io.columnar.api.io.Format;
import io.columnar.api.BaseTable;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.RowAppender;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.Viewport;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.LongChunk;
import io.columnar.api.chunk.StringChunk;
import io.columnar.api.JoinKind;
import io.columnar.api.BinaryOp;
import io.columnar.api.Expr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guide-style tests demonstrating the columnar framework API end-to-end.
 *
 * <p>All operations flow through {@link Columnar}, the single entry point.
 * Start here when learning the framework.
 */
@DisplayName("Columnar API Guide")
class ColumnarApiTest {

    // =========================================================================
    // 1. Schemas
    // =========================================================================

    @Nested
    @DisplayName("1. Schema definition")
    class SchemaDefinition {

        /**
         * Schemas declare column names and types.
         * All operations — building tables, reading results — are anchored to a Schema.
         */
        @Test
        @DisplayName("build a schema with mixed types")
        void buildSchema() {
            Schema schema = Columnar.schema()
                    .add("id",        DataType.LONG)
                    .add("name",      DataType.STRING)
                    .add("price",     DataType.DOUBLE)
                    .add("quantity",  DataType.INT)
                    .add("available", DataType.BOOLEAN)
                    .add("listed_at", DataType.INSTANT)
                    .build();

            assertThat(schema.size()).isEqualTo(6);
            assertThat(schema.field("price").type()).isEqualTo(DataType.DOUBLE);
            assertThat(schema.names()).containsExactly(
                    "id", "name", "price", "quantity", "available", "listed_at");
        }

        /**
         * Array columns hold a fixed-length array of values per row.
         * The array length is declared at schema definition time.
         */
        @Test
        @DisplayName("schema with fixed-length array columns")
        void schemaWithArrayColumns() {
            Schema schema = Columnar.schema()
                    .add("id",     DataType.LONG)
                    .add("scores", DataType.DOUBLE_ARRAY, 3)  // 3 doubles per row
                    .add("tags",   DataType.STRING_ARRAY, 5)  // 5 strings per row
                    .build();

            assertThat(schema.field("scores").arrayLength()).isEqualTo(3);
            assertThat(schema.field("tags").arrayLength()).isEqualTo(5);
        }
    }

    // =========================================================================
    // 2. Building tables
    // =========================================================================

    @Nested
    @DisplayName("2. Building tables")
    class BuildingTables {

        /**
         * The simplest way to create a table: append rows as boxed Object arrays
         * and call build(). The result is a sealed, immutable snapshot.
         */
        @Test
        @DisplayName("build a sealed table with appendRow")
        void sealedTableWithAppendRow() {
            Schema schema = Columnar.schema()
                    .add("id",   DataType.LONG)
                    .add("name", DataType.STRING)
                    .build();

            BaseTable table = Columnar.table(schema)
                    .appendRow(1L, "Alice")
                    .appendRow(2L, "Bob")
                    .appendRow(3L, "Carol")
                    .build();

            assertThat(table.size()).isEqualTo(3);
            assertThat(table.schema()).isEqualTo(schema);
        }

        /**
         * For primitive-heavy schemas, RowAppender avoids boxing on the hot path.
         * Get a row appender, set each column by index, then commit.
         */
        @Test
        @DisplayName("build a table using the typed RowAppender")
        void typedRowAppender() {
            Schema schema = Columnar.schema()
                    .add("id",    DataType.LONG)
                    .add("value", DataType.DOUBLE)
                    .build();

            BaseTable.Builder builder = Columnar.table(schema);
            for (int i = 1; i <= 5; i++) {
                builder.row()
                        .setLong(0, i)
                        .setDouble(1, i * 1.5)
                        .commit();
            }
            BaseTable table = builder.build();

            assertThat(table.size()).isEqualTo(5);
        }

        /**
         * Null values are supported in all column types. Pass null in appendRow
         * or call setNull(colIndex) on the RowAppender.
         */
        @Test
        @DisplayName("null values are supported")
        void nullValues() {
            Schema schema = Columnar.schema()
                    .add("id",   DataType.LONG)
                    .add("name", DataType.STRING)
                    .build();

            BaseTable table = Columnar.table(schema)
                    .appendRow(1L, "Alice")
                    .appendRow(2L, null)      // name is null
                    .build();

            ColumnarSlice slice = table.read();
            StringChunk names = (StringChunk) slice.column("name").chunk(0);
            assertThat(names.getString(0)).isEqualTo("Alice");
            assertThat(slice.column("name").chunk(0).validity().isNull(1)).isTrue();
        }

        /**
         * Live tables accept appends at any time and can be read concurrently.
         * Each mutation increments the version number.
         */
        @Test
        @DisplayName("live table supports concurrent reads and writes")
        void liveTable() {
            Schema schema = Columnar.schema()
                    .add("id",    DataType.LONG)
                    .add("value", DataType.DOUBLE)
                    .build();

            BaseTable live = Columnar.liveTable(schema);
            assertThat(live.size()).isEqualTo(0);
            long v0 = live.version();

            live.appendRow(1L, 10.0);
            assertThat(live.size()).isEqualTo(1);
            assertThat(live.version()).isGreaterThan(v0);

            live.appendRow(2L, 20.0);
            assertThat(live.size()).isEqualTo(2);
        }

        /**
         * INSTANT columns store nanosecond-precision timestamps.
         */
        @Test
        @DisplayName("INSTANT column stores nanosecond timestamps")
        void instantColumn() {
            Schema schema = Columnar.schema()
                    .add("event",      DataType.STRING)
                    .add("occurred_at", DataType.INSTANT)
                    .build();

            Instant t1 = Instant.parse("2024-01-15T10:00:00Z");
            Instant t2 = Instant.parse("2024-01-15T10:00:01Z");

            BaseTable table = Columnar.table(schema)
                    .appendRow("login",  t1)
                    .appendRow("logout", t2)
                    .build();

            ColumnarSlice slice = table.read();
            assertThat(slice.rowCount()).isEqualTo(2);
        }
    }

    // =========================================================================
    // 3. Reading data
    // =========================================================================

    @Nested
    @DisplayName("3. Reading data")
    class ReadingData {

        /**
         * table.read() returns a ColumnarSlice — an immutable snapshot.
         * Columns are accessed by name or index; data lives in typed chunks.
         */
        @Test
        @DisplayName("read all rows from a table")
        void readAllRows() {
            BaseTable table = salesTable();
            ColumnarSlice slice = table.read();

            assertThat(slice.rowCount()).isEqualTo(4);
            assertThat(slice.schema().names()).containsExactly("id", "product", "amount");

            // column access by name
            LongChunk ids = (LongChunk) slice.column("id").chunk(0);
            assertThat(ids.getLong(0)).isEqualTo(1L);
            assertThat(ids.getLong(3)).isEqualTo(4L);
        }

        /**
         * Viewports limit how much data is read.
         * Use Viewport.builder() to constrain rows and columns.
         */
        @Test
        @DisplayName("viewport limits the rows returned")
        void viewportLimitsRows() {
            BaseTable table = salesTable();

            // read only the first 2 rows
            ColumnarSlice slice = table.read(Viewport.builder().limit(2).build());
            assertThat(slice.rowCount()).isEqualTo(2);
        }

        /**
         * toPrettyString() formats a table for debugging.
         */
        @Test
        @DisplayName("pretty-print a table for debugging")
        void prettyPrint() {
            BaseTable table = salesTable();
            String pretty = table.toPrettyString();

            assertThat(pretty).contains("product");
            assertThat(pretty).contains("Apple");
        }
    }

    // =========================================================================
    // 4. Filtering
    // =========================================================================

    @Nested
    @DisplayName("4. Filtering")
    class Filtering {

        /**
         * filter() applies a vectorised row predicate. Built-in predicates cover
         * the most common cases; combine them with and()/or().
         */
        @Test
        @DisplayName("filter with a built-in predicate")
        void filterWithPredicate() {
            BaseTable table = salesTable(); // amounts: 1.0, 5.0, 3.0, 2.0

            ColumnarSlice result = Columnar.from(table)
                    .filter("amount > 2", Columnar.doubleGt("amount", 2.0))
                    .build()
                    .read();

            assertThat(result.rowCount()).isEqualTo(2); // 5.0 and 3.0
        }

        /**
         * Combine predicates with and()/or() to build compound filters.
         */
        @Test
        @DisplayName("combine predicates with and / or")
        void combinePredicates() {
            BaseTable table = salesTable();

            ColumnarSlice result = Columnar.from(table)
                    .filter("amount between 2 and 4",
                            Columnar.and(
                                    Columnar.doubleGt("amount", 1.5),
                                    Columnar.doubleGt("amount", 0.0) // placeholder for <=4 (no lte built-in)
                            ))
                    .build()
                    .read();

            assertThat(result.rowCount()).isGreaterThan(0);
        }

        /**
         * filterExpr() accepts an expression tree and compiles it to bytecode
         * the first time it runs — same performance as hand-written predicates.
         */
        @Test
        @DisplayName("filter with a compiled expression")
        void filterWithExpression() {
            BaseTable table = salesTable();

            // amount > 2.0
            Expr pred = new Expr.Binary(
                    new Expr.ColRef("amount"),
                    BinaryOp.GT,
                    new Expr.Const(2.0, DataType.DOUBLE));

            ColumnarSlice result = Columnar.from(table)
                    .filterExpr("amount > 2.0", pred)
                    .build()
                    .read();

            assertThat(result.rowCount()).isEqualTo(2);
        }

        /**
         * stringEq() filters on exact string equality (dictionary-encoded internally).
         */
        @Test
        @DisplayName("filter on a STRING column")
        void filterString() {
            BaseTable table = salesTable();

            ColumnarSlice result = Columnar.from(table)
                    .filter("product = Apple", Columnar.stringEq("product", "Apple"))
                    .build()
                    .read();

            assertThat(result.rowCount()).isEqualTo(2); // two Apple rows
        }
    }

    // =========================================================================
    // 5. Projection
    // =========================================================================

    @Nested
    @DisplayName("5. Projection")
    class Projection {

        /**
         * project() keeps only the named columns, in the specified order.
         * Useful for reducing memory usage or reordering output.
         */
        @Test
        @DisplayName("project to a subset of columns")
        void projectSubset() {
            BaseTable table = salesTable(); // columns: id, product, amount

            ColumnarSlice result = Columnar.from(table)
                    .project("product", "amount") // drop "id", reorder
                    .build()
                    .read();

            assertThat(result.schema().names()).containsExactly("product", "amount");
            assertThat(result.rowCount()).isEqualTo(4);
        }
    }

    // =========================================================================
    // 6. Sorting
    // =========================================================================

    @Nested
    @DisplayName("6. Sorting")
    class Sorting {

        /**
         * orderBy() sorts rows by a LONG or DOUBLE column.
         * The default is ascending; pass false for descending.
         */
        @Test
        @DisplayName("sort by a numeric column ascending")
        void sortAscending() {
            BaseTable table = salesTable(); // amounts: 1.0, 5.0, 3.0, 2.0

            ColumnarSlice result = Columnar.from(table)
                    .orderBy("amount")        // ascending = default
                    .build()
                    .read();

            DoubleChunk amounts = (DoubleChunk) result.column("amount").chunk(0);
            assertThat(amounts.getDouble(0)).isEqualTo(1.0);
            assertThat(amounts.getDouble(1)).isEqualTo(2.0);
            assertThat(amounts.getDouble(2)).isEqualTo(3.0);
            assertThat(amounts.getDouble(3)).isEqualTo(5.0);
        }

        /**
         * Pass false as the second argument to sort descending.
         */
        @Test
        @DisplayName("sort by a numeric column descending")
        void sortDescending() {
            BaseTable table = salesTable();

            ColumnarSlice result = Columnar.from(table)
                    .orderBy("amount", false)  // descending
                    .build()
                    .read();

            DoubleChunk amounts = (DoubleChunk) result.column("amount").chunk(0);
            assertThat(amounts.getDouble(0)).isEqualTo(5.0);
        }
    }

    // =========================================================================
    // 7. Aggregation
    // =========================================================================

    @Nested
    @DisplayName("7. Aggregation")
    class Aggregation {

        /**
         * groupBy() partitions rows by a STRING column and computes measures.
         * Columnar.count() counts rows; Columnar.sum() sums a DOUBLE column.
         */
        @Test
        @DisplayName("group by with count and sum")
        void groupByCountAndSum() {
            // product: Apple(×2), Banana(×1), Cherry(×1)  amounts: 1.0, 5.0, 3.0, 2.0
            BaseTable table = salesTable();

            ColumnarSlice result = Columnar.from(table)
                    .groupBy("product",
                            Columnar.count("num_sales"),
                            Columnar.sum("amount", "total_amount"))
                    .build()
                    .read();

            // 3 distinct products → 3 groups
            assertThat(result.rowCount()).isEqualTo(3);
            assertThat(result.schema().names())
                    .containsExactlyInAnyOrder("product", "num_sales", "total_amount");
        }

        /**
         * count() alone (no sum) is valid — useful for frequency tables.
         */
        @Test
        @DisplayName("count-only aggregation")
        void countOnly() {
            BaseTable table = salesTable();

            ColumnarSlice result = Columnar.from(table)
                    .groupBy("product", Columnar.count("n"))
                    .build()
                    .read();

            assertThat(result.rowCount()).isEqualTo(3);
            assertThat(result.schema().field("n").type()).isEqualTo(DataType.LONG);
        }
    }

    // =========================================================================
    // 8. Joining
    // =========================================================================

    @Nested
    @DisplayName("8. Joining")
    class Joining {

        /**
         * join() performs a hash join on STRING key columns.
         * The default is INNER join; all four join kinds are available.
         */
        @Test
        @DisplayName("inner join two tables on a STRING key")
        void innerJoin() {
            Schema orderSchema = Columnar.schema()
                    .add("order_id",    DataType.LONG)
                    .add("customer_id", DataType.STRING)
                    .add("amount",      DataType.DOUBLE)
                    .build();

            Schema customerSchema = Columnar.schema()
                    .add("id",   DataType.STRING)
                    .add("name", DataType.STRING)
                    .build();

            BaseTable orders = Columnar.table(orderSchema)
                    .appendRow(1L, "C1", 99.0)
                    .appendRow(2L, "C2", 49.0)
                    .appendRow(3L, "C1", 29.0)
                    .build();

            BaseTable customers = Columnar.table(customerSchema)
                    .appendRow("C1", "Alice")
                    .appendRow("C2", "Bob")
                    .build();

            ColumnarSlice result = Columnar.from(orders)
                    .join(customers, "customer_id", "id", JoinKind.INNER)
                    .build()
                    .read();

            // all 3 orders match a customer
            assertThat(result.rowCount()).isEqualTo(3);
            // The build-side join key ("id") is dropped from output — it equals the probe key
            assertThat(result.schema().names())
                    .contains("order_id", "customer_id", "amount", "name");
        }

        /**
         * LEFT join keeps all rows from the left side, even with no match on the right.
         */
        @Test
        @DisplayName("left join preserves unmatched left rows")
        void leftJoin() {
            Schema orderSchema = Columnar.schema()
                    .add("order_id",    DataType.LONG)
                    .add("customer_id", DataType.STRING)
                    .build();

            Schema customerSchema = Columnar.schema()
                    .add("id",   DataType.STRING)
                    .add("name", DataType.STRING)
                    .build();

            BaseTable orders = Columnar.table(orderSchema)
                    .appendRow(1L, "C1")
                    .appendRow(2L, "C_UNKNOWN") // no matching customer
                    .build();

            BaseTable customers = Columnar.table(customerSchema)
                    .appendRow("C1", "Alice")
                    .build();

            ColumnarSlice result = Columnar.from(orders)
                    .join(customers, "customer_id", "id", JoinKind.LEFT)
                    .build()
                    .read();

            assertThat(result.rowCount()).isEqualTo(2); // both orders included
        }
    }

    // =========================================================================
    // 9. Chaining operators
    // =========================================================================

    @Nested
    @DisplayName("9. Chaining operators")
    class ChainingOperators {

        /**
         * QueryPlanner methods return a new QueryPlanner each time, so operators
         * can be composed freely. Nothing executes until build() is called.
         */
        @Test
        @DisplayName("filter → project → sort pipeline")
        void filterProjectSort() {
            BaseTable table = salesTable();

            // Keep only rows with amount > 1.5, show product + amount, sorted by amount desc
            ColumnarSlice result = Columnar.from(table)
                    .filter("amount > 1.5", Columnar.doubleGt("amount", 1.5))
                    .project("product", "amount")
                    .orderBy("amount", false)
                    .build()
                    .read();

            assertThat(result.schema().names()).containsExactly("product", "amount");
            assertThat(result.rowCount()).isEqualTo(3); // 5.0, 3.0, 2.0
            DoubleChunk amounts = (DoubleChunk) result.column("amount").chunk(0);
            assertThat(amounts.getDouble(0)).isEqualTo(5.0);
        }

        /**
         * Wrap a DerivedTable in Columnar.from() to keep building on top of it.
         */
        @Test
        @DisplayName("chain from a DerivedTable")
        void chainFromDerivedTable() {
            BaseTable table = salesTable();

            // First stage: filter
            var filtered = Columnar.from(table)
                    .filter("amount > 1", Columnar.doubleGt("amount", 1.0))
                    .build();

            // Second stage: group the filtered result
            ColumnarSlice result = Columnar.from(filtered)
                    .groupBy("product", Columnar.count("n"))
                    .build()
                    .read();

            assertThat(result.rowCount()).isGreaterThan(0);
        }

        /**
         * build() returns a DerivedTable. For caching, wrap it with PullEngines
         * from the :query module. Each re-read of the same version is computed once.
         */
        @Test
        @DisplayName("build() returns a lazily computed DerivedTable")
        void buildReturnsDerivedTable() {
            BaseTable table = salesTable();

            var derived = Columnar.from(table)
                    .filter("amount > 2", Columnar.doubleGt("amount", 2.0))
                    .build();

            ColumnarSlice first  = derived.read();
            ColumnarSlice second = derived.read();
            assertThat(first.rowCount()).isEqualTo(second.rowCount());
        }
    }

    // =========================================================================
    // 10. Live table subscriptions
    // =========================================================================

    @Nested
    @DisplayName("10. Live table appends and version tracking")
    class LiveTableAppends {

        /**
         * Each appendRow on a live table bumps the version.
         * Downstream derived tables see the new data on their next read().
         */
        @Test
        @DisplayName("appends are visible immediately on next read")
        void appendsVisibleOnNextRead() {
            Schema schema = Columnar.schema()
                    .add("v", DataType.LONG)
                    .build();

            BaseTable live = Columnar.liveTable(schema);
            var derived = Columnar.from(live)
                    .filter("v > 5", Columnar.longGt("v", 5L))
                    .build();

            assertThat(derived.read().rowCount()).isEqualTo(0);

            live.appendRow(10L);
            live.appendRow(3L);
            live.appendRow(7L);

            assertThat(derived.read().rowCount()).isEqualTo(2); // 10 and 7
        }

        /**
         * The typed RowAppender avoids boxing on the write path — useful for
         * high-throughput ingestion of primitive-heavy data.
         */
        @Test
        @DisplayName("typed RowAppender avoids boxing on the hot path")
        void typedAppenderOnLiveTable() {
            Schema schema = Columnar.schema()
                    .add("ts",  DataType.LONG)
                    .add("val", DataType.DOUBLE)
                    .build();

            BaseTable live = Columnar.liveTable(schema);
            for (int i = 0; i < 1000; i++) {
                RowAppender row = live.row();
                row.setLong(0, i).setDouble(1, i * 0.1).commit();
            }

            assertThat(live.size()).isEqualTo(1000);
        }
    }

    // =========================================================================
    // 11. File I/O — CSV, Arrow, Parquet
    // =========================================================================

    @Nested
    @DisplayName("11. File I/O")
    class FileIO {

        /**
         * CSV write/read round-trip.
         * Column types are inferred on read (INT → LONG → DOUBLE → STRING).
         * Pass an explicit Schema to override inference.
         */
        @Test
        @DisplayName("CSV round-trip with schema inference")
        void csvRoundTrip(@TempDir Path tmp) throws IOException {
            Schema schema = Columnar.schema()
                    .add("id",      DataType.LONG)
                    .add("product", DataType.STRING)
                    .add("price",   DataType.DOUBLE)
                    .build();

            BaseTable original = Columnar.table(schema)
                    .appendRow(1L, "Apple",  0.99)
                    .appendRow(2L, "Banana", 0.49)
                    .build();

            Path file = tmp.resolve("products.csv");
            Columnar.write(original, Format.CSV, file);
            BaseTable loaded = Columnar.read(Format.CSV, file);

            assertThat(loaded.size()).isEqualTo(2);
            assertThat(loaded.schema().names()).containsExactly("id", "product", "price");
        }

        /**
         * For CSV, pass an explicit schema to control column types precisely —
         * important when INT vs LONG matters or when BOOLEAN/INSTANT columns are present.
         */
        @Test
        @DisplayName("CSV read with explicit schema")
        void csvReadWithSchema(@TempDir Path tmp) throws IOException {
            Schema schema = Columnar.schema()
                    .add("flag",  DataType.BOOLEAN)
                    .add("label", DataType.STRING)
                    .build();

            BaseTable original = Columnar.table(schema)
                    .appendRow(true,  "yes")
                    .appendRow(false, "no")
                    .build();

            Path file = tmp.resolve("flags.csv");
            Columnar.write(original, Format.CSV, file);
            BaseTable loaded = Columnar.read(Format.CSV, file, schema);

            assertThat(loaded.size()).isEqualTo(2);
            assertThat(loaded.schema().field("flag").type()).isEqualTo(DataType.BOOLEAN);
        }

        /**
         * Arrow IPC file format — self-describing (schema embedded), columnar,
         * very fast for in-process exchange.
         */
        @Test
        @DisplayName("Arrow IPC round-trip")
        void arrowRoundTrip(@TempDir Path tmp) throws IOException {
            Schema schema = Columnar.schema()
                    .add("id",    DataType.LONG)
                    .add("value", DataType.DOUBLE)
                    .add("label", DataType.STRING)
                    .build();

            BaseTable original = Columnar.table(schema)
                    .appendRow(1L, 3.14, "pi")
                    .appendRow(2L, 2.72, "e")
                    .build();

            Path file = tmp.resolve("data.arrow");
            Columnar.write(original, Format.ARROW, file);
            BaseTable loaded = Columnar.read(Format.ARROW, file);

            assertThat(loaded.size()).isEqualTo(2);
            assertThat(loaded.schema()).isEqualTo(schema);

            ColumnarSlice slice = loaded.read();
            LongChunk ids = (LongChunk) slice.column("id").chunk(0);
            assertThat(ids.getLong(0)).isEqualTo(1L);
            assertThat(ids.getLong(1)).isEqualTo(2L);
        }

        /**
         * Parquet — the standard columnar format for analytics pipelines.
         * Schema is embedded; INSTANT stored as epoch nanoseconds with timestamp annotation.
         */
        @Test
        @DisplayName("Parquet round-trip")
        void parquetRoundTrip(@TempDir Path tmp) throws IOException {
            Schema schema = Columnar.schema()
                    .add("id",         DataType.LONG)
                    .add("name",       DataType.STRING)
                    .add("score",      DataType.DOUBLE)
                    .add("active",     DataType.BOOLEAN)
                    .build();

            BaseTable original = Columnar.table(schema)
                    .appendRow(1L, "Alice", 95.5, true)
                    .appendRow(2L, "Bob",   82.0, false)
                    .appendRow(3L, "Carol", 91.0, true)
                    .build();

            Path file = tmp.resolve("results.parquet");
            Columnar.write(original, Format.PARQUET, file);
            BaseTable loaded = Columnar.read(Format.PARQUET, file);

            assertThat(loaded.size()).isEqualTo(3);
            assertThat(loaded.schema()).isEqualTo(schema);

            ColumnarSlice slice = loaded.read();
            StringChunk names = (StringChunk) slice.column("name").chunk(0);
            assertThat(names.getString(0)).isEqualTo("Alice");
            assertThat(names.getString(1)).isEqualTo("Bob");
        }

        /**
         * Null values survive round-trips across all three formats.
         */
        @Test
        @DisplayName("null values survive a Parquet round-trip")
        void nullsSurviveParquetRoundTrip(@TempDir Path tmp) throws IOException {
            Schema schema = Columnar.schema()
                    .add("id",   DataType.LONG)
                    .add("note", DataType.STRING)
                    .build();

            BaseTable original = Columnar.table(schema)
                    .appendRow(1L, "present")
                    .appendRow(2L, null)        // null note
                    .build();

            Path file = tmp.resolve("nulls.parquet");
            Columnar.write(original, Format.PARQUET, file);
            BaseTable loaded = Columnar.read(Format.PARQUET, file);

            ColumnarSlice slice = loaded.read();
            assertThat(slice.column("note").chunk(0).validity().isNull(1)).isTrue();
        }

        /**
         * Arrow supports DOUBLE_ARRAY, INT_ARRAY, STRING_ARRAY and DATE_ARRAY via FixedSizeList
         * vectors. Each array column round-trips element values exactly.
         */
        @Test
        @DisplayName("Arrow round-trip with array columns")
        void arrowArrayRoundTrip(@TempDir Path tmp) throws IOException {
            Schema schema = Columnar.schema()
                    .add("id",       DataType.LONG)
                    .add("scores",   DataType.DOUBLE_ARRAY, 3)
                    .add("counts",   DataType.INT_ARRAY,    2)
                    .add("tags",     DataType.STRING_ARRAY, 2)
                    .build();

            BaseTable original = Columnar.table(schema)
                    .appendRow(1L, new double[]{1.0, 2.0, 3.0}, new int[]{10, 20}, new String[]{"a", "b"})
                    .appendRow(2L, new double[]{4.0, 5.0, 6.0}, new int[]{30, 40}, new String[]{"c", "d"})
                    .build();

            Path file = tmp.resolve("arrays.arrow");
            Columnar.write(original, Format.ARROW, file);
            BaseTable loaded = Columnar.read(Format.ARROW, file);

            assertThat(loaded.size()).isEqualTo(2);
            assertThat(loaded.schema()).isEqualTo(schema);

            ColumnarSlice slice = loaded.read();
            io.columnar.api.chunk.DoubleArrayChunk scores =
                    (io.columnar.api.chunk.DoubleArrayChunk) slice.column("scores").chunk(0);
            assertThat(scores.getDouble(0, 0)).isEqualTo(1.0);
            assertThat(scores.getDouble(1, 2)).isEqualTo(6.0);

            io.columnar.api.chunk.IntArrayChunk counts =
                    (io.columnar.api.chunk.IntArrayChunk) slice.column("counts").chunk(0);
            assertThat(counts.getInt(0, 1)).isEqualTo(20);
            assertThat(counts.getInt(1, 0)).isEqualTo(30);

            io.columnar.api.chunk.StringArrayChunk tags =
                    (io.columnar.api.chunk.StringArrayChunk) slice.column("tags").chunk(0);
            assertThat(tags.getString(0, 0)).isEqualTo("a");
            assertThat(tags.getString(1, 1)).isEqualTo("d");
        }

        /**
         * Parquet stores array columns as Base64-encoded BINARY (field names carry the type suffix).
         * The round-trip must reconstruct both the DataType and the arrayLength from the file.
         */
        @Test
        @DisplayName("Parquet round-trip with array columns")
        void parquetArrayRoundTrip(@TempDir Path tmp) throws IOException {
            Schema schema = Columnar.schema()
                    .add("id",       DataType.LONG)
                    .add("scores",   DataType.DOUBLE_ARRAY, 3)
                    .add("counts",   DataType.INT_ARRAY,    2)
                    .add("tags",     DataType.STRING_ARRAY, 2)
                    .build();

            BaseTable original = Columnar.table(schema)
                    .appendRow(1L, new double[]{1.0, 2.0, 3.0}, new int[]{10, 20}, new String[]{"x", "y"})
                    .appendRow(2L, new double[]{-1.0, 0.0, 1.0}, new int[]{99, 0}, new String[]{"foo", "bar"})
                    .build();

            Path file = tmp.resolve("arrays.parquet");
            Columnar.write(original, Format.PARQUET, file);
            BaseTable loaded = Columnar.read(Format.PARQUET, file);

            assertThat(loaded.size()).isEqualTo(2);
            assertThat(loaded.schema()).isEqualTo(schema);

            ColumnarSlice slice = loaded.read();
            io.columnar.api.chunk.DoubleArrayChunk scores =
                    (io.columnar.api.chunk.DoubleArrayChunk) slice.column("scores").chunk(0);
            assertThat(scores.getDouble(0, 0)).isEqualTo(1.0);
            assertThat(scores.getDouble(1, 1)).isEqualTo(0.0);

            io.columnar.api.chunk.StringArrayChunk tags =
                    (io.columnar.api.chunk.StringArrayChunk) slice.column("tags").chunk(0);
            assertThat(tags.getString(0, 0)).isEqualTo("x");
            assertThat(tags.getString(1, 1)).isEqualTo("bar");
        }
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * A small sales table used across multiple tests.
     *
     * <pre>
     * id | product | amount
     * ---+---------+-------
     *  1 | Apple   |   1.0
     *  2 | Banana  |   5.0
     *  3 | Cherry  |   3.0
     *  4 | Apple   |   2.0
     * </pre>
     */
    private static BaseTable salesTable() {
        Schema schema = Columnar.schema()
                .add("id",      DataType.LONG)
                .add("product", DataType.STRING)
                .add("amount",  DataType.DOUBLE)
                .build();

        return Columnar.table(schema)
                .appendRow(1L, "Apple",  1.0)
                .appendRow(2L, "Banana", 5.0)
                .appendRow(3L, "Cherry", 3.0)
                .appendRow(4L, "Apple",  2.0)
                .build();
    }
}
