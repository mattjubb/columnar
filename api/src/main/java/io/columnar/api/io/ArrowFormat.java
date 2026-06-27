package io.columnar.api.io;

import io.columnar.core.BaseTable;
import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
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
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.ipc.SeekableReadChannel;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Apache Arrow IPC file format reader/writer. */
final class ArrowFormat implements TableReader, TableWriter {

    // ---- write ---------------------------------------------------------------

    @Override
    public void write(Table table, Path path) throws IOException {
        var slice = table.read();
        var schema = slice.schema();
        validateScalarOnly(schema);
        var arrowSchema = toArrowSchema(schema);

        try (BufferAllocator alloc = new RootAllocator();
             VectorSchemaRoot root = VectorSchemaRoot.create(arrowSchema, alloc);
             FileChannel ch = FileChannel.open(path,
                     StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             ArrowFileWriter writer = new ArrowFileWriter(root, null, ch)) {

            writer.start();
            int numCols = schema.size();
            int chunkCount = slice.chunkCount();

            for (int ci = 0; ci < chunkCount; ci++) {
                ColumnChunk[] chunks = new ColumnChunk[numCols];
                for (int c = 0; c < numCols; c++) {
                    chunks[c] = slice.column(c).chunk(ci);
                }
                int size = chunks[0].size();
                root.allocateNew();
                for (int c = 0; c < numCols; c++) {
                    fillVector(root.getVector(c), chunks[c]);
                }
                root.setRowCount(size);
                writer.writeBatch();
                root.clear();
            }
            writer.end();
        }
    }

    private static void fillVector(FieldVector fv, ColumnChunk chunk) {
        int size = chunk.size();
        switch (chunk) {
            case IntChunk ic -> {
                IntVector v = (IntVector) fv;
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) v.setSafe(r, ic.getInt(r));
                }
            }
            case LongChunk lc -> {
                BigIntVector v = (BigIntVector) fv;
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) v.setSafe(r, lc.getLong(r));
                }
            }
            case FloatChunk fc -> {
                Float4Vector v = (Float4Vector) fv;
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) v.setSafe(r, fc.getFloat(r));
                }
            }
            case DoubleChunk dc -> {
                Float8Vector v = (Float8Vector) fv;
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) v.setSafe(r, dc.getDouble(r));
                }
            }
            case BooleanChunk bc -> {
                BitVector v = (BitVector) fv;
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) v.setSafe(r, bc.getBoolean(r) ? 1 : 0);
                }
            }
            case StringChunk sc -> {
                VarCharVector v = (VarCharVector) fv;
                for (int r = 0; r < size; r++) {
                    String s = sc.getString(r);
                    if (s != null) v.setSafe(r, s.getBytes(StandardCharsets.UTF_8));
                }
            }
            case InstantChunk ic -> {
                TimeStampNanoTZVector v = (TimeStampNanoTZVector) fv;
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) v.setSafe(r, ic.getEpochNano(r));
                }
            }
            default -> throw new UnsupportedOperationException(
                    "Arrow does not support chunk type: " + chunk.getClass().getSimpleName());
        }
    }

    // ---- read ----------------------------------------------------------------

    @Override
    public BaseTable read(Path path) throws IOException {
        try (BufferAllocator alloc = new RootAllocator();
             FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
             ArrowFileReader reader = new ArrowFileReader(new SeekableReadChannel(ch), alloc)) {

            var root = reader.getVectorSchemaRoot();
            Schema schema = fromArrowSchema(root.getSchema());
            BaseTable.Builder builder = Table.builder(schema);

            while (reader.loadNextBatch()) {
                int rows = root.getRowCount();
                int cols = schema.size();
                for (int r = 0; r < rows; r++) {
                    Object[] values = new Object[cols];
                    for (int c = 0; c < cols; c++) {
                        FieldVector fv = root.getVector(c);
                        values[c] = fv.isNull(r) ? null : readValue(fv, r);
                    }
                    builder.appendRow(values);
                }
            }
            return builder.build();
        }
    }

    private static Object readValue(FieldVector fv, int r) {
        return switch (fv) {
            case IntVector iv            -> iv.get(r);
            case BigIntVector bv         -> bv.get(r);
            case Float4Vector f4         -> f4.get(r);
            case Float8Vector f8         -> f8.get(r);
            case BitVector bv            -> bv.get(r) == 1;
            case VarCharVector vcv       -> new String(vcv.get(r), StandardCharsets.UTF_8);
            case TimeStampNanoTZVector t -> epochNanoToInstant(t.get(r));
            default -> throw new UnsupportedOperationException(
                    "Unsupported Arrow vector type: " + fv.getClass().getSimpleName());
        };
    }

    // ---- schema mapping ------------------------------------------------------

    private static org.apache.arrow.vector.types.pojo.Schema toArrowSchema(Schema schema) {
        List<Field> fields = new ArrayList<>(schema.size());
        for (Schema.Field f : schema.fields()) {
            fields.add(Field.nullable(f.name(), toArrowType(f.type())));
        }
        return new org.apache.arrow.vector.types.pojo.Schema(fields);
    }

    private static ArrowType toArrowType(DataType type) {
        return switch (type) {
            case INT     -> new ArrowType.Int(32, true);
            case LONG    -> new ArrowType.Int(64, true);
            case FLOAT   -> new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
            case DOUBLE  -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            case BOOLEAN -> new ArrowType.Bool();
            case STRING  -> new ArrowType.Utf8();
            case INSTANT -> new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC");
            default      -> throw new UnsupportedOperationException(
                    "Arrow does not support column type: " + type);
        };
    }

    private static Schema fromArrowSchema(org.apache.arrow.vector.types.pojo.Schema arrowSchema) {
        Schema.Builder b = Schema.builder();
        for (Field f : arrowSchema.getFields()) {
            b.add(f.getName(), fromArrowType(f.getType()));
        }
        return b.build();
    }

    private static DataType fromArrowType(ArrowType t) {
        return switch (t) {
            case ArrowType.Int i when i.getBitWidth() == 32  -> DataType.INT;
            case ArrowType.Int i                              -> DataType.LONG;
            case ArrowType.FloatingPoint fp when
                    fp.getPrecision() == FloatingPointPrecision.SINGLE -> DataType.FLOAT;
            case ArrowType.FloatingPoint fp                  -> DataType.DOUBLE;
            case ArrowType.Bool ignored                       -> DataType.BOOLEAN;
            case ArrowType.Utf8 ignored                       -> DataType.STRING;
            case ArrowType.Timestamp ignored                  -> DataType.INSTANT;
            default -> throw new UnsupportedOperationException(
                    "Unsupported Arrow type: " + t.getClass().getSimpleName());
        };
    }

    private static Instant epochNanoToInstant(long epochNanos) {
        return Instant.ofEpochSecond(
                Math.floorDiv(epochNanos, 1_000_000_000L),
                Math.floorMod(epochNanos, 1_000_000_000L));
    }

    private static void validateScalarOnly(Schema schema) {
        for (Schema.Field f : schema.fields()) {
            toArrowType(f.type()); // throws if unsupported
        }
    }
}
