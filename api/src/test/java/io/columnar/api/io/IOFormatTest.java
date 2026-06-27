package io.columnar.api.io;

import io.columnar.api.BaseTable;
import io.columnar.api.Column;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.DataType;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.api.chunk.BooleanChunk;
import io.columnar.api.chunk.DateArrayChunk;
import io.columnar.api.chunk.DoubleArrayChunk;
import io.columnar.api.chunk.DoubleChunk;
import io.columnar.api.chunk.FloatChunk;
import io.columnar.api.chunk.InstantChunk;
import io.columnar.api.chunk.IntArrayChunk;
import io.columnar.api.chunk.IntChunk;
import io.columnar.api.chunk.LongChunk;
import io.columnar.api.chunk.StringArrayChunk;
import io.columnar.api.chunk.StringChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Comprehensive read/write tests for Arrow IPC and Parquet formats covering every DataType.
 *
 * <p>Each test writes a table to a temp file then reads it back and verifies:
 * <ul>
 *   <li>Row count matches</li>
 *   <li>Schema (field names, types, array lengths) round-trips exactly</li>
 *   <li>All cell values survive serialization unchanged</li>
 *   <li>Null values are preserved</li>
 * </ul>
 */
@DisplayName("Arrow and Parquet I/O — all DataTypes")
class IOFormatTest {

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static BaseTable roundTrip(BaseTable original, Format format, Path dir, String name)
            throws IOException {
        Path file = dir.resolve(name);
        TableIO.write(original, format, file);
        return TableIO.read(format, file);
    }

    private static ColumnarSlice slice(BaseTable t) {
        return t.read();
    }

    // =========================================================================
    // Arrow IPC tests
    // =========================================================================

    @Nested
    @DisplayName("Arrow IPC")
    class ArrowTests {

        @Test
        @DisplayName("INT column — positive, negative, zero, Integer extremes")
        void intColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.INT).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(0)
                    .appendRow(1)
                    .appendRow(-1)
                    .appendRow(Integer.MAX_VALUE)
                    .appendRow(Integer.MIN_VALUE)
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "int.arrow");

            assertThat(loaded.size()).isEqualTo(5);
            assertThat(loaded.schema()).isEqualTo(schema);
            IntChunk c = (IntChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getInt(0)).isEqualTo(0);
            assertThat(c.getInt(2)).isEqualTo(-1);
            assertThat(c.getInt(3)).isEqualTo(Integer.MAX_VALUE);
            assertThat(c.getInt(4)).isEqualTo(Integer.MIN_VALUE);
        }

        @Test
        @DisplayName("LONG column — full int64 range")
        void longColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.LONG).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(0L)
                    .appendRow(Long.MAX_VALUE)
                    .appendRow(Long.MIN_VALUE)
                    .appendRow(-9_999_999_999L)
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "long.arrow");

            LongChunk c = (LongChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getLong(1)).isEqualTo(Long.MAX_VALUE);
            assertThat(c.getLong(2)).isEqualTo(Long.MIN_VALUE);
            assertThat(c.getLong(3)).isEqualTo(-9_999_999_999L);
        }

        @Test
        @DisplayName("FLOAT column — including NaN and infinite values")
        void floatColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.FLOAT).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(0.0f)
                    .appendRow(1.5f)
                    .appendRow(-1.5f)
                    .appendRow(Float.MAX_VALUE)
                    .appendRow(Float.MIN_VALUE)
                    .appendRow(Float.NaN)
                    .appendRow(Float.POSITIVE_INFINITY)
                    .appendRow(Float.NEGATIVE_INFINITY)
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "float.arrow");

            assertThat(loaded.size()).isEqualTo(8);
            FloatChunk c = (FloatChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getFloat(0)).isEqualTo(0.0f);
            assertThat(c.getFloat(1)).isEqualTo(1.5f);
            assertThat(c.getFloat(5)).isNaN();
            assertThat(c.getFloat(6)).isEqualTo(Float.POSITIVE_INFINITY);
            assertThat(c.getFloat(7)).isEqualTo(Float.NEGATIVE_INFINITY);
        }

        @Test
        @DisplayName("DOUBLE column — high-precision values and special floats")
        void doubleColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.DOUBLE).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(Math.PI)
                    .appendRow(Math.E)
                    .appendRow(Double.MAX_VALUE)
                    .appendRow(Double.MIN_VALUE)
                    .appendRow(Double.NaN)
                    .appendRow(Double.POSITIVE_INFINITY)
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "double.arrow");

            DoubleChunk c = (DoubleChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getDouble(0)).isCloseTo(Math.PI, within(1e-15));
            assertThat(c.getDouble(1)).isCloseTo(Math.E, within(1e-15));
            assertThat(c.getDouble(2)).isEqualTo(Double.MAX_VALUE);
            assertThat(c.getDouble(4)).isNaN();
            assertThat(c.getDouble(5)).isEqualTo(Double.POSITIVE_INFINITY);
        }

        @Test
        @DisplayName("BOOLEAN column")
        void booleanColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.BOOLEAN).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(true)
                    .appendRow(false)
                    .appendRow(true)
                    .appendRow(true)
                    .appendRow(false)
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "bool.arrow");

            BooleanChunk c = (BooleanChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getBoolean(0)).isTrue();
            assertThat(c.getBoolean(1)).isFalse();
            assertThat(c.getBoolean(4)).isFalse();
        }

        @Test
        @DisplayName("STRING column — empty string, Unicode, special characters")
        void stringColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.STRING).build();
            BaseTable original = Table.builder(schema)
                    .appendRow("hello")
                    .appendRow("")               // empty string
                    .appendRow("こんにちは")      // Japanese
                    .appendRow("café")           // accented
                    .appendRow("line1\nline2")   // embedded newline
                    .appendRow("a,b,c")          // comma (CSV-like)
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "string.arrow");

            StringChunk c = (StringChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getString(0)).isEqualTo("hello");
            assertThat(c.getString(1)).isEqualTo("");
            assertThat(c.getString(2)).isEqualTo("こんにちは");
            assertThat(c.getString(3)).isEqualTo("café");
            assertThat(c.getString(4)).isEqualTo("line1\nline2");
            assertThat(c.getString(5)).isEqualTo("a,b,c");
        }

        @Test
        @DisplayName("INSTANT column — nanosecond precision")
        void instantColumn(@TempDir Path tmp) throws IOException {
            Instant t0 = Instant.EPOCH;
            Instant t1 = Instant.parse("2024-01-15T10:30:00.123456789Z");
            Instant t2 = Instant.parse("1970-01-01T00:00:00.000000001Z"); // 1 nanosecond
            Instant t3 = Instant.parse("2099-12-31T23:59:59.999999999Z");

            Schema schema = Schema.builder().add("v", DataType.INSTANT).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(t0)
                    .appendRow(t1)
                    .appendRow(t2)
                    .appendRow(t3)
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "instant.arrow");

            InstantChunk c = (InstantChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getInstant(0)).isEqualTo(t0);
            assertThat(c.getInstant(1)).isEqualTo(t1);
            assertThat(c.getInstant(2)).isEqualTo(t2);
            assertThat(c.getInstant(3)).isEqualTo(t3);
        }

        @Test
        @DisplayName("DOUBLE_ARRAY column — fixed-length double arrays per row")
        void doubleArrayColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.DOUBLE_ARRAY, 3).build();
            BaseTable original = Table.builder(schema)
                    .appendRow((Object) new double[]{1.0, 2.0, 3.0})
                    .appendRow((Object) new double[]{-1.0, 0.0, Double.NaN})
                    .appendRow((Object) new double[]{Double.MAX_VALUE, Double.MIN_VALUE, Math.PI})
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "darr.arrow");

            assertThat(loaded.schema()).isEqualTo(schema);
            DoubleArrayChunk c = (DoubleArrayChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.elementsPerRow()).isEqualTo(3);
            assertThat(c.getDouble(0, 0)).isEqualTo(1.0);
            assertThat(c.getDouble(0, 2)).isEqualTo(3.0);
            assertThat(c.getDouble(1, 1)).isEqualTo(0.0);
            assertThat(c.getDouble(1, 2)).isNaN();
            assertThat(c.getDouble(2, 0)).isEqualTo(Double.MAX_VALUE);
            assertThat(c.getDouble(2, 2)).isCloseTo(Math.PI, within(1e-15));
        }

        @Test
        @DisplayName("INT_ARRAY column — fixed-length int arrays per row")
        void intArrayColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.INT_ARRAY, 4).build();
            BaseTable original = Table.builder(schema)
                    .appendRow((Object) new int[]{1, 2, 3, 4})
                    .appendRow((Object) new int[]{Integer.MIN_VALUE, 0, -1, Integer.MAX_VALUE})
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "iarr.arrow");

            IntArrayChunk c = (IntArrayChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.elementsPerRow()).isEqualTo(4);
            assertThat(c.getInt(0, 0)).isEqualTo(1);
            assertThat(c.getInt(0, 3)).isEqualTo(4);
            assertThat(c.getInt(1, 0)).isEqualTo(Integer.MIN_VALUE);
            assertThat(c.getInt(1, 3)).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("STRING_ARRAY column — fixed-length string arrays including null elements")
        void stringArrayColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.STRING_ARRAY, 3).build();
            BaseTable original = Table.builder(schema)
                    .appendRow((Object) new String[]{"a", "b", "c"})
                    .appendRow((Object) new String[]{"こんにちは", "", "café"})
                    .appendRow((Object) new String[]{null, "x", null}) // null elements
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "sarr.arrow");

            StringArrayChunk c = (StringArrayChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.elementsPerRow()).isEqualTo(3);
            assertThat(c.getString(0, 0)).isEqualTo("a");
            assertThat(c.getString(1, 0)).isEqualTo("こんにちは");
            assertThat(c.getString(1, 1)).isEqualTo("");
            assertThat(c.getString(2, 0)).isNull();
            assertThat(c.getString(2, 1)).isEqualTo("x");
            assertThat(c.getString(2, 2)).isNull();
        }

        @Test
        @DisplayName("DATE_ARRAY column — fixed-length timestamp arrays (epoch nanos)")
        void dateArrayColumn(@TempDir Path tmp) throws IOException {
            long epoch  = 0L;
            long oneSec = 1_000_000_000L;
            long nano1  = 1L;

            Schema schema = Schema.builder().add("v", DataType.DATE_ARRAY, 2).build();
            BaseTable original = Table.builder(schema)
                    .appendRow((Object) new long[]{epoch, oneSec})
                    .appendRow((Object) new long[]{nano1, Long.MAX_VALUE})
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "tarr.arrow");

            DateArrayChunk c = (DateArrayChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.elementsPerRow()).isEqualTo(2);
            assertThat(c.getEpochNano(0, 0)).isEqualTo(epoch);
            assertThat(c.getEpochNano(0, 1)).isEqualTo(oneSec);
            assertThat(c.getEpochNano(1, 0)).isEqualTo(nano1);
            assertThat(c.getEpochNano(1, 1)).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("null values — all nullable scalar types preserve nulls")
        void nullsAllScalarTypes(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("i",  DataType.INT)
                    .add("l",  DataType.LONG)
                    .add("f",  DataType.FLOAT)
                    .add("d",  DataType.DOUBLE)
                    .add("b",  DataType.BOOLEAN)
                    .add("s",  DataType.STRING)
                    .add("ts", DataType.INSTANT)
                    .build();

            BaseTable original = Table.builder(schema)
                    .appendRow(null, null, null, null, null, null, null) // all null
                    .appendRow(1,    2L,   3.0f, 4.0,  true, "x", Instant.EPOCH) // all set
                    .appendRow(null, null, null, null, null, null, null) // all null again
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "nulls.arrow");

            assertThat(loaded.size()).isEqualTo(3);
            ColumnarSlice s = slice(loaded);
            for (String col : schema.names()) {
                assertThat(s.column(col).chunk(0).validity().isNull(0))
                        .as("row 0, col %s should be null", col).isTrue();
                assertThat(s.column(col).chunk(0).validity().isNull(1))
                        .as("row 1, col %s should not be null", col).isFalse();
                assertThat(s.column(col).chunk(0).validity().isNull(2))
                        .as("row 2, col %s should be null", col).isTrue();
            }
        }

        @Test
        @DisplayName("null array rows — full row null vs non-null array")
        void nullArrayRows(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("id", DataType.LONG)
                    .add("v",  DataType.DOUBLE_ARRAY, 2)
                    .build();

            BaseTable original = Table.builder(schema)
                    .appendRow(1L, new double[]{1.0, 2.0})
                    .appendRow(2L, null)                  // null array row
                    .appendRow(3L, new double[]{3.0, 4.0})
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "nullarr.arrow");

            ColumnarSlice s = slice(loaded);
            assertThat(s.column("v").chunk(0).validity().isNull(0)).isFalse();
            assertThat(s.column("v").chunk(0).validity().isNull(1)).isTrue();
            assertThat(s.column("v").chunk(0).validity().isNull(2)).isFalse();
            DoubleArrayChunk v = (DoubleArrayChunk) s.column("v").chunk(0);
            assertThat(v.getDouble(0, 0)).isEqualTo(1.0);
            assertThat(v.getDouble(2, 1)).isEqualTo(4.0);
        }

        @Test
        @DisplayName("mixed types — all scalar DataTypes in one table")
        void mixedScalarTypes(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("i",  DataType.INT)
                    .add("l",  DataType.LONG)
                    .add("f",  DataType.FLOAT)
                    .add("d",  DataType.DOUBLE)
                    .add("b",  DataType.BOOLEAN)
                    .add("s",  DataType.STRING)
                    .add("ts", DataType.INSTANT)
                    .build();

            Instant now = Instant.parse("2024-06-01T12:00:00Z");
            BaseTable original = Table.builder(schema)
                    .appendRow(42, 99L, 1.5f, 3.14, true, "hello", now)
                    .appendRow(-7, 0L,  0.0f, 0.0,  false, "world", Instant.EPOCH)
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "mixed.arrow");

            assertThat(loaded.schema()).isEqualTo(schema);
            assertThat(loaded.size()).isEqualTo(2);
            ColumnarSlice s = slice(loaded);
            assertThat(((IntChunk)     s.column("i").chunk(0)).getInt(0)).isEqualTo(42);
            assertThat(((LongChunk)    s.column("l").chunk(0)).getLong(0)).isEqualTo(99L);
            assertThat(((FloatChunk)   s.column("f").chunk(0)).getFloat(0)).isEqualTo(1.5f);
            assertThat(((DoubleChunk)  s.column("d").chunk(0)).getDouble(0)).isEqualTo(3.14);
            assertThat(((BooleanChunk) s.column("b").chunk(0)).getBoolean(0)).isTrue();
            assertThat(((StringChunk)  s.column("s").chunk(0)).getString(0)).isEqualTo("hello");
            assertThat(((InstantChunk) s.column("ts").chunk(0)).getInstant(0)).isEqualTo(now);
        }

        @Test
        @DisplayName("mixed array types — all four array DataTypes in one table")
        void mixedArrayTypes(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("da", DataType.DOUBLE_ARRAY, 2)
                    .add("ia", DataType.INT_ARRAY,    3)
                    .add("sa", DataType.STRING_ARRAY, 2)
                    .add("ta", DataType.DATE_ARRAY,   2)
                    .build();

            long n1 = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli() * 1_000_000L;
            long n2 = Instant.parse("2024-06-01T00:00:00Z").toEpochMilli() * 1_000_000L;

            BaseTable original = Table.builder(schema)
                    .appendRow(
                            new double[]{1.1, 2.2},
                            new int[]{10, 20, 30},
                            new String[]{"x", "y"},
                            new long[]{n1, n2})
                    .build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "mixedarr.arrow");

            assertThat(loaded.schema()).isEqualTo(schema);
            ColumnarSlice s = slice(loaded);
            assertThat(((DoubleArrayChunk) s.column("da").chunk(0)).getDouble(0, 1)).isEqualTo(2.2);
            assertThat(((IntArrayChunk)    s.column("ia").chunk(0)).getInt(0, 2)).isEqualTo(30);
            assertThat(((StringArrayChunk) s.column("sa").chunk(0)).getString(0, 0)).isEqualTo("x");
            assertThat(((DateArrayChunk)   s.column("ta").chunk(0)).getEpochNano(0, 0)).isEqualTo(n1);
        }

        @Test
        @DisplayName("large table — spans multiple chunks to test batch boundaries")
        void largeTable(@TempDir Path tmp) throws IOException {
            int rowCount = 200_000; // well above the default 65536 chunk size
            Schema schema = Schema.builder()
                    .add("id",  DataType.LONG)
                    .add("val", DataType.DOUBLE)
                    .build();

            BaseTable.Builder b = Table.builder(schema);
            for (long i = 0; i < rowCount; i++) b.appendRow(i, i * 0.5);
            BaseTable original = b.build();

            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "large.arrow");

            assertThat(loaded.size()).isEqualTo(rowCount);
            // Spot-check last row (must be in a later chunk)
            ColumnarSlice s = slice(loaded);
            long totalRows = s.rowCount();
            assertThat(totalRows).isEqualTo(rowCount);
        }

        @Test
        @DisplayName("empty table — zero rows, schema preserved")
        void emptyTable(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("id",  DataType.LONG)
                    .add("name", DataType.STRING)
                    .build();

            BaseTable original = Table.builder(schema).build();
            BaseTable loaded = roundTrip(original, Format.ARROW, tmp, "empty.arrow");

            assertThat(loaded.size()).isEqualTo(0);
            assertThat(loaded.schema()).isEqualTo(schema);
        }
    }

    // =========================================================================
    // Parquet tests
    // =========================================================================

    @Nested
    @DisplayName("Parquet")
    class ParquetTests {

        @Test
        @DisplayName("INT column — positive, negative, zero, extremes")
        void intColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.INT).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(0)
                    .appendRow(1)
                    .appendRow(-1)
                    .appendRow(Integer.MAX_VALUE)
                    .appendRow(Integer.MIN_VALUE)
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "int.parquet");

            assertThat(loaded.size()).isEqualTo(5);
            assertThat(loaded.schema()).isEqualTo(schema);
            IntChunk c = (IntChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getInt(0)).isEqualTo(0);
            assertThat(c.getInt(3)).isEqualTo(Integer.MAX_VALUE);
            assertThat(c.getInt(4)).isEqualTo(Integer.MIN_VALUE);
        }

        @Test
        @DisplayName("LONG column — full int64 range")
        void longColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.LONG).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(0L)
                    .appendRow(Long.MAX_VALUE)
                    .appendRow(Long.MIN_VALUE)
                    .appendRow(-9_999_999_999L)
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "long.parquet");

            LongChunk c = (LongChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getLong(1)).isEqualTo(Long.MAX_VALUE);
            assertThat(c.getLong(2)).isEqualTo(Long.MIN_VALUE);
            assertThat(c.getLong(3)).isEqualTo(-9_999_999_999L);
        }

        @Test
        @DisplayName("FLOAT column")
        void floatColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.FLOAT).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(0.0f)
                    .appendRow(1.5f)
                    .appendRow(-1.5f)
                    .appendRow(Float.MAX_VALUE)
                    .appendRow(Float.MIN_VALUE)
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "float.parquet");

            FloatChunk c = (FloatChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getFloat(0)).isEqualTo(0.0f);
            assertThat(c.getFloat(1)).isEqualTo(1.5f);
            assertThat(c.getFloat(2)).isEqualTo(-1.5f);
            assertThat(c.getFloat(3)).isEqualTo(Float.MAX_VALUE);
        }

        @Test
        @DisplayName("DOUBLE column — high precision round-trip")
        void doubleColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.DOUBLE).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(Math.PI)
                    .appendRow(Math.E)
                    .appendRow(Double.MAX_VALUE)
                    .appendRow(Double.MIN_VALUE)
                    .appendRow(-0.0)
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "double.parquet");

            DoubleChunk c = (DoubleChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getDouble(0)).isCloseTo(Math.PI, within(1e-15));
            assertThat(c.getDouble(2)).isEqualTo(Double.MAX_VALUE);
            assertThat(c.getDouble(3)).isEqualTo(Double.MIN_VALUE);
        }

        @Test
        @DisplayName("BOOLEAN column")
        void booleanColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.BOOLEAN).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(true).appendRow(false).appendRow(true).build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "bool.parquet");

            BooleanChunk c = (BooleanChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getBoolean(0)).isTrue();
            assertThat(c.getBoolean(1)).isFalse();
            assertThat(c.getBoolean(2)).isTrue();
        }

        @Test
        @DisplayName("STRING column — Unicode, empty string, special characters")
        void stringColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.STRING).build();
            BaseTable original = Table.builder(schema)
                    .appendRow("hello")
                    .appendRow("")
                    .appendRow("こんにちは")
                    .appendRow("café ☕")
                    .appendRow("tab\there")
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "string.parquet");

            StringChunk c = (StringChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getString(0)).isEqualTo("hello");
            assertThat(c.getString(1)).isEqualTo("");
            assertThat(c.getString(2)).isEqualTo("こんにちは");
            assertThat(c.getString(3)).isEqualTo("café ☕");
            assertThat(c.getString(4)).isEqualTo("tab\there");
        }

        @Test
        @DisplayName("INSTANT column — nanosecond precision round-trip")
        void instantColumn(@TempDir Path tmp) throws IOException {
            Instant t1 = Instant.parse("2024-01-15T10:30:00.123456789Z");
            Instant t2 = Instant.EPOCH;
            Instant t3 = Instant.parse("1999-12-31T23:59:59.999999999Z");

            Schema schema = Schema.builder().add("v", DataType.INSTANT).build();
            BaseTable original = Table.builder(schema)
                    .appendRow(t1).appendRow(t2).appendRow(t3).build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "instant.parquet");

            InstantChunk c = (InstantChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getInstant(0)).isEqualTo(t1);
            assertThat(c.getInstant(1)).isEqualTo(t2);
            assertThat(c.getInstant(2)).isEqualTo(t3);
        }

        @Test
        @DisplayName("DOUBLE_ARRAY column — Base64-encoded round-trip")
        void doubleArrayColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.DOUBLE_ARRAY, 3).build();
            BaseTable original = Table.builder(schema)
                    .appendRow((Object) new double[]{1.0, 2.0, 3.0})
                    .appendRow((Object) new double[]{-1.0, 0.0, Math.PI})
                    .appendRow((Object) new double[]{Double.MAX_VALUE, Double.MIN_VALUE, 0.0})
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "darr.parquet");

            assertThat(loaded.schema()).isEqualTo(schema);
            DoubleArrayChunk c = (DoubleArrayChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.elementsPerRow()).isEqualTo(3);
            assertThat(c.getDouble(0, 0)).isEqualTo(1.0);
            assertThat(c.getDouble(0, 2)).isEqualTo(3.0);
            assertThat(c.getDouble(1, 2)).isCloseTo(Math.PI, within(1e-15));
            assertThat(c.getDouble(2, 0)).isEqualTo(Double.MAX_VALUE);
        }

        @Test
        @DisplayName("INT_ARRAY column — Base64-encoded round-trip")
        void intArrayColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.INT_ARRAY, 4).build();
            BaseTable original = Table.builder(schema)
                    .appendRow((Object) new int[]{1, 2, 3, 4})
                    .appendRow((Object) new int[]{Integer.MIN_VALUE, 0, -1, Integer.MAX_VALUE})
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "iarr.parquet");

            IntArrayChunk c = (IntArrayChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getInt(0, 0)).isEqualTo(1);
            assertThat(c.getInt(0, 3)).isEqualTo(4);
            assertThat(c.getInt(1, 0)).isEqualTo(Integer.MIN_VALUE);
            assertThat(c.getInt(1, 3)).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("STRING_ARRAY column — Unicode, empty strings, null elements")
        void stringArrayColumn(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder().add("v", DataType.STRING_ARRAY, 3).build();
            BaseTable original = Table.builder(schema)
                    .appendRow((Object) new String[]{"alpha", "beta", "gamma"})
                    .appendRow((Object) new String[]{"", "こんにちは", "café"})
                    .appendRow((Object) new String[]{null, "x", null})
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "sarr.parquet");

            StringArrayChunk c = (StringArrayChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.elementsPerRow()).isEqualTo(3);
            assertThat(c.getString(0, 0)).isEqualTo("alpha");
            assertThat(c.getString(1, 0)).isEqualTo("");
            assertThat(c.getString(1, 1)).isEqualTo("こんにちは");
            assertThat(c.getString(2, 0)).isNull();
            assertThat(c.getString(2, 1)).isEqualTo("x");
            assertThat(c.getString(2, 2)).isNull();
        }

        @Test
        @DisplayName("DATE_ARRAY column — epoch nanos round-trip")
        void dateArrayColumn(@TempDir Path tmp) throws IOException {
            long n1 = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli() * 1_000_000L;
            long n2 = Instant.parse("2024-06-01T12:30:00.123456789Z")
                    .getEpochSecond() * 1_000_000_000L
                    + Instant.parse("2024-06-01T12:30:00.123456789Z").getNano();

            Schema schema = Schema.builder().add("v", DataType.DATE_ARRAY, 2).build();
            BaseTable original = Table.builder(schema)
                    .appendRow((Object) new long[]{n1, n2})
                    .appendRow((Object) new long[]{0L, Long.MAX_VALUE})
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "tarr.parquet");

            DateArrayChunk c = (DateArrayChunk) slice(loaded).column("v").chunk(0);
            assertThat(c.getEpochNano(0, 0)).isEqualTo(n1);
            assertThat(c.getEpochNano(0, 1)).isEqualTo(n2);
            assertThat(c.getEpochNano(1, 0)).isEqualTo(0L);
            assertThat(c.getEpochNano(1, 1)).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("null values — all scalar types preserve nulls")
        void nullsAllScalarTypes(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("i",  DataType.INT)
                    .add("l",  DataType.LONG)
                    .add("f",  DataType.FLOAT)
                    .add("d",  DataType.DOUBLE)
                    .add("b",  DataType.BOOLEAN)
                    .add("s",  DataType.STRING)
                    .add("ts", DataType.INSTANT)
                    .build();

            BaseTable original = Table.builder(schema)
                    .appendRow(null, null, null, null, null, null, null)
                    .appendRow(1, 2L, 3.0f, 4.0, true, "set", Instant.EPOCH)
                    .appendRow(null, null, null, null, null, null, null)
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "nulls.parquet");

            assertThat(loaded.size()).isEqualTo(3);
            ColumnarSlice s = slice(loaded);
            for (String col : schema.names()) {
                assertThat(s.column(col).chunk(0).validity().isNull(0))
                        .as("row 0, col %s null", col).isTrue();
                assertThat(s.column(col).chunk(0).validity().isNull(1))
                        .as("row 1, col %s not null", col).isFalse();
                assertThat(s.column(col).chunk(0).validity().isNull(2))
                        .as("row 2, col %s null", col).isTrue();
            }
        }

        @Test
        @DisplayName("null array rows — full row null vs non-null array")
        void nullArrayRows(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("id", DataType.LONG)
                    .add("v",  DataType.DOUBLE_ARRAY, 2)
                    .build();

            BaseTable original = Table.builder(schema)
                    .appendRow(1L, new double[]{1.0, 2.0})
                    .appendRow(2L, null)
                    .appendRow(3L, new double[]{5.0, 6.0})
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "nullarr.parquet");

            ColumnarSlice s = slice(loaded);
            assertThat(s.column("v").chunk(0).validity().isNull(0)).isFalse();
            assertThat(s.column("v").chunk(0).validity().isNull(1)).isTrue();
            assertThat(s.column("v").chunk(0).validity().isNull(2)).isFalse();
            DoubleArrayChunk v = (DoubleArrayChunk) s.column("v").chunk(0);
            assertThat(v.getDouble(0, 0)).isEqualTo(1.0);
            assertThat(v.getDouble(2, 1)).isEqualTo(6.0);
        }

        @Test
        @DisplayName("mixed scalar types — all DataTypes in one table")
        void mixedScalarTypes(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("i",  DataType.INT)
                    .add("l",  DataType.LONG)
                    .add("f",  DataType.FLOAT)
                    .add("d",  DataType.DOUBLE)
                    .add("b",  DataType.BOOLEAN)
                    .add("s",  DataType.STRING)
                    .add("ts", DataType.INSTANT)
                    .build();

            Instant now = Instant.parse("2024-06-01T12:00:00Z");
            BaseTable original = Table.builder(schema)
                    .appendRow(42, 99L, 1.5f, 3.14, true, "hello", now)
                    .appendRow(-7, 0L, 0.0f, 0.0, false, "world", Instant.EPOCH)
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "mixed.parquet");

            assertThat(loaded.schema()).isEqualTo(schema);
            ColumnarSlice s = slice(loaded);
            assertThat(((IntChunk)     s.column("i").chunk(0)).getInt(0)).isEqualTo(42);
            assertThat(((LongChunk)    s.column("l").chunk(0)).getLong(0)).isEqualTo(99L);
            assertThat(((FloatChunk)   s.column("f").chunk(0)).getFloat(0)).isEqualTo(1.5f);
            assertThat(((DoubleChunk)  s.column("d").chunk(0)).getDouble(0)).isEqualTo(3.14);
            assertThat(((BooleanChunk) s.column("b").chunk(0)).getBoolean(0)).isTrue();
            assertThat(((StringChunk)  s.column("s").chunk(0)).getString(0)).isEqualTo("hello");
            assertThat(((InstantChunk) s.column("ts").chunk(0)).getInstant(0)).isEqualTo(now);
        }

        @Test
        @DisplayName("mixed array types — all four array DataTypes in one table")
        void mixedArrayTypes(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("da", DataType.DOUBLE_ARRAY, 2)
                    .add("ia", DataType.INT_ARRAY,    3)
                    .add("sa", DataType.STRING_ARRAY, 2)
                    .add("ta", DataType.DATE_ARRAY,   2)
                    .build();

            long n1 = 1_000_000_000L;
            long n2 = 2_000_000_000L;

            BaseTable original = Table.builder(schema)
                    .appendRow(
                            new double[]{1.1, 2.2},
                            new int[]{10, 20, 30},
                            new String[]{"p", "q"},
                            new long[]{n1, n2})
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "mixedarr.parquet");

            assertThat(loaded.schema()).isEqualTo(schema);
            ColumnarSlice s = slice(loaded);
            assertThat(((DoubleArrayChunk) s.column("da").chunk(0)).getDouble(0, 1)).isEqualTo(2.2);
            assertThat(((IntArrayChunk)    s.column("ia").chunk(0)).getInt(0, 2)).isEqualTo(30);
            assertThat(((StringArrayChunk) s.column("sa").chunk(0)).getString(0, 0)).isEqualTo("p");
            assertThat(((DateArrayChunk)   s.column("ta").chunk(0)).getEpochNano(0, 1)).isEqualTo(n2);
        }

        @Test
        @DisplayName("field name with array suffix — original name survives round-trip")
        void fieldNamePreserved(@TempDir Path tmp) throws IOException {
            // Column named "my_scores" must come back as "my_scores", not "my_scores__DARR3"
            Schema schema = Schema.builder()
                    .add("my_scores", DataType.DOUBLE_ARRAY, 3)
                    .add("label",     DataType.STRING)
                    .build();

            BaseTable original = Table.builder(schema)
                    .appendRow(new double[]{9.5, 8.0, 7.5}, "A")
                    .build();

            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "fieldnames.parquet");

            assertThat(loaded.schema().field("my_scores").type()).isEqualTo(DataType.DOUBLE_ARRAY);
            assertThat(loaded.schema().field("my_scores").arrayLength()).isEqualTo(3);
            assertThat(loaded.schema().field("label").type()).isEqualTo(DataType.STRING);
        }

        @Test
        @DisplayName("empty table — zero rows, schema preserved")
        void emptyTable(@TempDir Path tmp) throws IOException {
            Schema schema = Schema.builder()
                    .add("id",   DataType.LONG)
                    .add("name", DataType.STRING)
                    .build();

            BaseTable original = Table.builder(schema).build();
            BaseTable loaded = roundTrip(original, Format.PARQUET, tmp, "empty.parquet");

            assertThat(loaded.size()).isEqualTo(0);
            assertThat(loaded.schema()).isEqualTo(schema);
        }
    }
}
