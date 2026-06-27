package io.columnar.api.io;

import blue.strategic.parquet.Dehydrator;
import blue.strategic.parquet.Hydrator;
import blue.strategic.parquet.HydratorSupplier;
import blue.strategic.parquet.ParquetReader;
import blue.strategic.parquet.ParquetWriter;
import io.columnar.api.BaseTable;
import io.columnar.api.ColumnChunk;
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
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type.Repetition;
import org.apache.parquet.schema.Types;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Apache Parquet reader/writer via parquet-floor (no Hadoop at runtime).
 *
 * <h3>Scalar types</h3>
 * Mapped to native Parquet primitives (INT32, INT64, FLOAT, DOUBLE, BOOLEAN, BINARY+string,
 * INT64+timestamp).
 *
 * <h3>Array types</h3>
 * Stored as Base64-encoded BINARY (string logical type). The fixed array length and element type
 * are embedded in the Parquet field name using a suffix:
 * <ul>
 *   <li>{@code __DARR<N>} — DOUBLE_ARRAY of length N (big-endian IEEE-754 doubles)</li>
 *   <li>{@code __IARR<N>} — INT_ARRAY of length N (big-endian int32s)</li>
 *   <li>{@code __SARR<N>} — STRING_ARRAY of length N (length-prefixed UTF-8 elements)</li>
 *   <li>{@code __TARR<N>} — DATE_ARRAY of length N (big-endian int64 epoch nanos)</li>
 * </ul>
 * The original column name is preserved as the field name prefix, so the file remains
 * queryable by other Parquet tools.
 */
final class ParquetFormat implements TableReader, TableWriter {

    // Suffix patterns for array columns embedded in the Parquet field name.
    private static final Pattern ARRAY_SUFFIX = Pattern.compile("^(.+)__(DARR|IARR|SARR|TARR)(\\d+)$");

    // ---- write ---------------------------------------------------------------

    @Override
    public void write(Table table, Path path) throws IOException {
        ColumnarSlice slice = table.read();
        Schema schema = slice.schema();
        MessageType messageType = toParquetSchema(schema);
        int numCols = schema.size();

        // Use the Parquet field names (may have array suffix) for writing.
        String[] parquetNames = new String[numCols];
        for (int c = 0; c < numCols; c++) {
            parquetNames[c] = toParquetFieldName(schema.field(c));
        }

        Dehydrator<Object[]> dehydrator = (row, valueWriter) -> {
            for (int c = 0; c < numCols; c++) {
                if (row[c] != null) valueWriter.write(parquetNames[c], row[c]);
            }
        };

        try (ParquetWriter<Object[]> writer = ParquetWriter.writeFile(messageType, path.toFile(), dehydrator)) {
            int chunkCount = slice.chunkCount();
            for (int ci = 0; ci < chunkCount; ci++) {
                ColumnChunk[] chunks = new ColumnChunk[numCols];
                for (int c = 0; c < numCols; c++) chunks[c] = slice.column(c).chunk(ci);
                int size = chunks[0].size();
                for (int rowIdx = 0; rowIdx < size; rowIdx++) {
                    Object[] rowValues = new Object[numCols];
                    for (int c = 0; c < numCols; c++) {
                        if (!chunks[c].validity().isNull(rowIdx)) {
                            rowValues[c] = extractValue(chunks[c], rowIdx);
                        }
                    }
                    writer.write(rowValues);
                }
            }
        }
    }

    private static Object extractValue(ColumnChunk chunk, int row) {
        return switch (chunk) {
            case IntChunk ic       -> ic.getInt(row);
            case LongChunk lc      -> lc.getLong(row);
            case FloatChunk fc     -> fc.getFloat(row);
            case DoubleChunk dc    -> dc.getDouble(row);
            case BooleanChunk bc   -> bc.getBoolean(row);
            case StringChunk sc    -> sc.getString(row);
            case InstantChunk ic   -> ic.getEpochNano(row);
            case DoubleArrayChunk dac -> encodeDoubleArray(dac, row);
            case IntArrayChunk iac    -> encodeIntArray(iac, row);
            case StringArrayChunk sac -> encodeStringArray(sac, row);
            case DateArrayChunk dac   -> encodeDateArray(dac, row);
            default -> throw new UnsupportedOperationException(
                    "Parquet: unsupported chunk type " + chunk.getClass().getSimpleName());
        };
    }

    // Each encode method returns a Base64 String — parquet-floor writes it as BINARY+string.

    private static String encodeDoubleArray(DoubleArrayChunk chunk, int row) {
        int n = chunk.elementsPerRow();
        ByteBuffer buf = ByteBuffer.allocate(n * Double.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (int e = 0; e < n; e++) buf.putDouble(chunk.getDouble(row, e));
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static String encodeIntArray(IntArrayChunk chunk, int row) {
        int n = chunk.elementsPerRow();
        ByteBuffer buf = ByteBuffer.allocate(n * Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (int e = 0; e < n; e++) buf.putInt(chunk.getInt(row, e));
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static String encodeStringArray(StringArrayChunk chunk, int row) {
        int n = chunk.elementsPerRow();
        // Format: [4-byte element count] then for each element: [4-byte length, -1 = null] + [UTF-8 bytes]
        List<byte[]> parts = new ArrayList<>(n);
        int total = 4; // element count header
        for (int e = 0; e < n; e++) {
            String s = chunk.getString(row, e);
            byte[] b = s == null ? null : s.getBytes(StandardCharsets.UTF_8);
            parts.add(b);
            total += 4 + (b == null ? 0 : b.length);
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(n);
        for (byte[] b : parts) {
            if (b == null) {
                buf.putInt(-1);
            } else {
                buf.putInt(b.length);
                buf.put(b);
            }
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static String encodeDateArray(DateArrayChunk chunk, int row) {
        int n = chunk.elementsPerRow();
        ByteBuffer buf = ByteBuffer.allocate(n * Long.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (int e = 0; e < n; e++) buf.putLong(chunk.getEpochNano(row, e));
        return Base64.getEncoder().encodeToString(buf.array());
    }

    // ---- read ----------------------------------------------------------------

    @Override
    public BaseTable read(Path path) throws IOException {
        ParquetMetadata meta = ParquetReader.readMetadata(path.toFile());
        MessageType messageType = meta.getFileMetaData().getSchema();
        Schema schema = fromParquetSchema(messageType);
        int numCols = schema.size();

        // Map from Parquet field name (with possible suffix) → column index.
        Map<String, Integer> colIndex = new HashMap<>(numCols * 2);
        for (org.apache.parquet.schema.Type t : messageType.getFields()) {
            String parquetName = t.getName();
            String logicalName = stripArraySuffix(parquetName);
            for (int c = 0; c < numCols; c++) {
                if (schema.field(c).name().equals(logicalName)) {
                    colIndex.put(parquetName, c);
                    break;
                }
            }
        }

        DataType[] types = new DataType[numCols];
        int[] arrayLengths = new int[numCols];
        for (int c = 0; c < numCols; c++) {
            types[c] = schema.field(c).type();
            arrayLengths[c] = schema.field(c).arrayLength();
        }

        Hydrator<Object[], Object[]> hydrator = new Hydrator<>() {
            @Override public Object[] start() { return new Object[numCols]; }

            @Override
            public Object[] add(Object[] target, String field, Object value) {
                Integer idx = colIndex.get(field);
                if (idx != null && value != null) {
                    target[idx] = convertReadValue(value, types[idx], arrayLengths[idx]);
                }
                return target;
            }

            @Override public Object[] finish(Object[] target) { return target; }
        };

        BaseTable.Builder builder = Table.builder(schema);
        try (Stream<Object[]> rows = ParquetReader.streamContent(
                path.toFile(), HydratorSupplier.constantly(hydrator))) {
            rows.forEach(builder::appendRow);
        }
        return builder.build();
    }

    private static Object convertReadValue(Object value, DataType type, int arrayLength) {
        return switch (type) {
            case INSTANT -> {
                long nanos = (Long) value;
                yield Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                        Math.floorMod(nanos, 1_000_000_000L));
            }
            case DOUBLE_ARRAY -> decodeDoubleArray((String) value, arrayLength);
            case INT_ARRAY    -> decodeIntArray((String) value, arrayLength);
            case STRING_ARRAY -> decodeStringArray((String) value);
            case DATE_ARRAY   -> decodeDateArray((String) value, arrayLength);
            default -> value; // scalar types pass through
        };
    }

    private static double[] decodeDoubleArray(String encoded, int n) {
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).order(ByteOrder.BIG_ENDIAN);
        double[] arr = new double[n];
        for (int e = 0; e < n; e++) arr[e] = buf.getDouble();
        return arr;
    }

    private static int[] decodeIntArray(String encoded, int n) {
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).order(ByteOrder.BIG_ENDIAN);
        int[] arr = new int[n];
        for (int e = 0; e < n; e++) arr[e] = buf.getInt();
        return arr;
    }

    private static String[] decodeStringArray(String encoded) {
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).order(ByteOrder.BIG_ENDIAN);
        int n = buf.getInt();
        String[] arr = new String[n];
        for (int e = 0; e < n; e++) {
            int len = buf.getInt();
            if (len >= 0) {
                byte[] bytes = new byte[len];
                buf.get(bytes);
                arr[e] = new String(bytes, StandardCharsets.UTF_8);
            }
        }
        return arr;
    }

    private static long[] decodeDateArray(String encoded, int n) {
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).order(ByteOrder.BIG_ENDIAN);
        long[] arr = new long[n];
        for (int e = 0; e < n; e++) arr[e] = buf.getLong();
        return arr;
    }

    // ---- Parquet field name helpers ------------------------------------------

    /** Parquet field name: appends type suffix for array columns. */
    private static String toParquetFieldName(Schema.Field f) {
        return switch (f.type()) {
            case DOUBLE_ARRAY -> f.name() + "__DARR" + f.arrayLength();
            case INT_ARRAY    -> f.name() + "__IARR" + f.arrayLength();
            case STRING_ARRAY -> f.name() + "__SARR" + f.arrayLength();
            case DATE_ARRAY   -> f.name() + "__TARR" + f.arrayLength();
            default -> f.name();
        };
    }

    private static String stripArraySuffix(String parquetName) {
        Matcher m = ARRAY_SUFFIX.matcher(parquetName);
        return m.matches() ? m.group(1) : parquetName;
    }

    // ---- Parquet schema mapping -----------------------------------------------

    private static MessageType toParquetSchema(Schema schema) {
        List<org.apache.parquet.schema.Type> fields = new ArrayList<>(schema.size());
        for (Schema.Field f : schema.fields()) fields.add(toParquetField(f));
        return new MessageType("table", fields);
    }

    private static org.apache.parquet.schema.Type toParquetField(Schema.Field f) {
        String pName = toParquetFieldName(f);
        return switch (f.type()) {
            case INT     -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.INT32,   pName);
            case LONG    -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.INT64,   pName);
            case FLOAT   -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.FLOAT,   pName);
            case DOUBLE  -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.DOUBLE,  pName);
            case BOOLEAN -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.BOOLEAN, pName);
            case STRING  -> Types.optional(PrimitiveTypeName.BINARY)
                    .as(LogicalTypeAnnotation.stringType()).named(pName);
            case INSTANT -> Types.optional(PrimitiveTypeName.INT64)
                    .as(LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS))
                    .named(pName);
            // Array types → BINARY+string so parquet-floor delivers them as String (Base64 encoded).
            case DOUBLE_ARRAY, INT_ARRAY, STRING_ARRAY, DATE_ARRAY ->
                    Types.optional(PrimitiveTypeName.BINARY)
                            .as(LogicalTypeAnnotation.stringType()).named(pName);
            default -> throw new UnsupportedOperationException(
                    "Parquet: unsupported column type " + f.type() + " ('" + f.name() + "')");
        };
    }

    private static Schema fromParquetSchema(MessageType messageType) {
        Schema.Builder b = Schema.builder();
        for (org.apache.parquet.schema.Type t : messageType.getFields()) {
            if (!(t instanceof PrimitiveType pt)) {
                throw new UnsupportedOperationException(
                        "Parquet GROUP fields not supported (field '" + t.getName() + "')");
            }
            String parquetName = pt.getName();
            Matcher m = ARRAY_SUFFIX.matcher(parquetName);
            if (m.matches()) {
                String logicalName = m.group(1);
                String tag = m.group(2);
                int arrayLen = Integer.parseInt(m.group(3));
                DataType dt = switch (tag) {
                    case "DARR" -> DataType.DOUBLE_ARRAY;
                    case "IARR" -> DataType.INT_ARRAY;
                    case "SARR" -> DataType.STRING_ARRAY;
                    case "TARR" -> DataType.DATE_ARRAY;
                    default -> throw new UnsupportedOperationException("Unknown array tag: " + tag);
                };
                b.add(logicalName, dt, arrayLen);
            } else {
                b.add(parquetName, fromParquetType(pt));
            }
        }
        return b.build();
    }

    private static DataType fromParquetType(PrimitiveType pt) {
        LogicalTypeAnnotation annotation = pt.getLogicalTypeAnnotation();
        return switch (pt.getPrimitiveTypeName()) {
            case INT32   -> DataType.INT;
            case INT64   -> annotation instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation
                    ? DataType.INSTANT : DataType.LONG;
            case FLOAT   -> DataType.FLOAT;
            case DOUBLE  -> DataType.DOUBLE;
            case BOOLEAN -> DataType.BOOLEAN;
            case BINARY, FIXED_LEN_BYTE_ARRAY -> DataType.STRING;
            default -> throw new UnsupportedOperationException(
                    "Parquet: unsupported primitive type " + pt.getPrimitiveTypeName());
        };
    }
}
