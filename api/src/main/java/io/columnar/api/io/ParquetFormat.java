package io.columnar.api.io;

import blue.strategic.parquet.Dehydrator;
import blue.strategic.parquet.Hydrator;
import blue.strategic.parquet.HydratorSupplier;
import blue.strategic.parquet.ParquetReader;
import blue.strategic.parquet.ParquetWriter;
import io.columnar.core.BaseTable;
import io.columnar.core.ColumnChunk;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.DataType;
import io.columnar.core.Schema;
import io.columnar.core.Table;
import io.columnar.core.chunk.BooleanChunk;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.FloatChunk;
import io.columnar.core.chunk.InstantChunk;
import io.columnar.core.chunk.IntChunk;
import io.columnar.core.chunk.LongChunk;
import io.columnar.core.chunk.StringChunk;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type.Repetition;
import org.apache.parquet.schema.Types;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Apache Parquet reader/writer via parquet-floor (no Hadoop dependency at runtime).
 * INSTANT columns are stored as INT64 epoch nanoseconds with a timestamp logical type.
 */
final class ParquetFormat implements TableReader, TableWriter {

    // ---- write ---------------------------------------------------------------

    @Override
    public void write(Table table, Path path) throws IOException {
        ColumnarSlice slice = table.read();
        Schema schema = slice.schema();
        MessageType messageType = toParquetSchema(schema);
        int numCols = schema.size();
        String[] names = new String[numCols];
        for (int c = 0; c < numCols; c++) names[c] = schema.field(c).name();

        Dehydrator<Object[]> dehydrator = (row, valueWriter) -> {
            for (int c = 0; c < numCols; c++) {
                if (row[c] != null) {
                    valueWriter.write(names[c], row[c]);
                }
            }
        };

        try (ParquetWriter<Object[]> writer = ParquetWriter.writeFile(messageType, path.toFile(), dehydrator)) {
            int chunkCount = slice.chunkCount();
            for (int ci = 0; ci < chunkCount; ci++) {
                ColumnChunk[] chunks = new ColumnChunk[numCols];
                for (int c = 0; c < numCols; c++) {
                    chunks[c] = slice.column(c).chunk(ci);
                }
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
            case IntChunk ic     -> ic.getInt(row);
            case LongChunk lc    -> lc.getLong(row);
            case FloatChunk fc   -> fc.getFloat(row);
            case DoubleChunk dc  -> dc.getDouble(row);
            case BooleanChunk bc -> bc.getBoolean(row);
            case StringChunk sc  -> sc.getString(row);
            case InstantChunk ic -> ic.getEpochNano(row); // written as INT64
            default -> throw new UnsupportedOperationException(
                    "Parquet does not support chunk type: " + chunk.getClass().getSimpleName());
        };
    }

    // ---- read ----------------------------------------------------------------

    @Override
    public BaseTable read(Path path) throws IOException {
        ParquetMetadata meta = ParquetReader.readMetadata(path.toFile());
        MessageType messageType = meta.getFileMetaData().getSchema();
        Schema schema = fromParquetSchema(messageType);
        int numCols = schema.size();

        Map<String, Integer> colIndex = new HashMap<>(numCols * 2);
        for (int c = 0; c < numCols; c++) colIndex.put(schema.field(c).name(), c);

        DataType[] types = new DataType[numCols];
        for (int c = 0; c < numCols; c++) types[c] = schema.field(c).type();

        Hydrator<Object[], Object[]> hydrator = new Hydrator<>() {
            @Override public Object[] start() { return new Object[numCols]; }

            @Override
            public Object[] add(Object[] target, String field, Object value) {
                Integer idx = colIndex.get(field);
                if (idx != null) {
                    target[idx] = value == null ? null : convertReadValue(value, types[idx]);
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

    /** INT64-stored INSTANT needs converting to Instant; all other types pass through. */
    private static Object convertReadValue(Object value, DataType type) {
        if (type == DataType.INSTANT) {
            long nanos = (Long) value;
            return Instant.ofEpochSecond(
                    Math.floorDiv(nanos, 1_000_000_000L),
                    Math.floorMod(nanos, 1_000_000_000L));
        }
        return value;
    }

    // ---- schema mapping ------------------------------------------------------

    private static MessageType toParquetSchema(Schema schema) {
        List<org.apache.parquet.schema.Type> fields = new ArrayList<>(schema.size());
        for (Schema.Field f : schema.fields()) fields.add(toParquetField(f));
        return new MessageType("table", fields);
    }

    private static org.apache.parquet.schema.Type toParquetField(Schema.Field f) {
        return switch (f.type()) {
            case INT     -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.INT32,   f.name());
            case LONG    -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.INT64,   f.name());
            case FLOAT   -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.FLOAT,   f.name());
            case DOUBLE  -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.DOUBLE,  f.name());
            case BOOLEAN -> new PrimitiveType(Repetition.OPTIONAL, PrimitiveTypeName.BOOLEAN, f.name());
            case STRING  -> Types.optional(PrimitiveTypeName.BINARY)
                    .as(LogicalTypeAnnotation.stringType()).named(f.name());
            case INSTANT -> Types.optional(PrimitiveTypeName.INT64)
                    .as(LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS))
                    .named(f.name());
            default -> throw new UnsupportedOperationException(
                    "Parquet does not support column type " + f.type()
                            + " (column '" + f.name() + "')");
        };
    }

    private static Schema fromParquetSchema(MessageType messageType) {
        Schema.Builder b = Schema.builder();
        for (org.apache.parquet.schema.Type t : messageType.getFields()) {
            if (!(t instanceof PrimitiveType pt)) {
                throw new UnsupportedOperationException(
                        "Parquet GROUP fields are not supported (field '" + t.getName() + "')");
            }
            b.add(pt.getName(), fromParquetType(pt));
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
                    "Unsupported Parquet primitive type: " + pt.getPrimitiveTypeName());
        };
    }
}
