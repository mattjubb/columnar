package io.columnar.api.io;

import io.columnar.api.BaseTable;
import io.columnar.api.Column;
import io.columnar.api.ColumnChunk;
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
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.ipc.SeekableReadChannel;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Apache Arrow IPC file format reader/writer.
 *
 * <p>Fixed-length array types ({@code DOUBLE_ARRAY}, {@code INT_ARRAY},
 * {@code STRING_ARRAY}, {@code DATE_ARRAY}) map to Arrow {@code FixedSizeList}
 * vectors with an appropriately-typed child vector.
 */
final class ArrowFormat implements TableReader, TableWriter {

    private static final String CHILD_FIELD_NAME = "$data$";

    // ---- write ---------------------------------------------------------------

    @Override
    public void write(Table table, Path path) throws IOException {
        var slice = table.read();
        var schema = slice.schema();
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
                for (int c = 0; c < numCols; c++) chunks[c] = slice.column(c).chunk(ci);
                int size = chunks[0].size();
                root.allocateNew();
                for (int c = 0; c < numCols; c++) fillVector(root.getVector(c), chunks[c]);
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
                for (int r = 0; r < size; r++)
                    if (!chunk.validity().isNull(r)) v.setSafe(r, ic.getInt(r));
            }
            case LongChunk lc -> {
                BigIntVector v = (BigIntVector) fv;
                for (int r = 0; r < size; r++)
                    if (!chunk.validity().isNull(r)) v.setSafe(r, lc.getLong(r));
            }
            case FloatChunk fc -> {
                Float4Vector v = (Float4Vector) fv;
                for (int r = 0; r < size; r++)
                    if (!chunk.validity().isNull(r)) v.setSafe(r, fc.getFloat(r));
            }
            case DoubleChunk dc -> {
                Float8Vector v = (Float8Vector) fv;
                for (int r = 0; r < size; r++)
                    if (!chunk.validity().isNull(r)) v.setSafe(r, dc.getDouble(r));
            }
            case BooleanChunk bc -> {
                BitVector v = (BitVector) fv;
                for (int r = 0; r < size; r++)
                    if (!chunk.validity().isNull(r)) v.setSafe(r, bc.getBoolean(r) ? 1 : 0);
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
                for (int r = 0; r < size; r++)
                    if (!chunk.validity().isNull(r)) v.setSafe(r, ic.getEpochNano(r));
            }
            case DoubleArrayChunk dac -> {
                FixedSizeListVector flv = (FixedSizeListVector) fv;
                Float8Vector child = (Float8Vector) flv.getDataVector();
                int n = dac.elementsPerRow();
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) {
                        flv.setNotNull(r);
                        int base = r * n;
                        for (int e = 0; e < n; e++) child.setSafe(base + e, dac.getDouble(r, e));
                    }
                }
            }
            case IntArrayChunk iac -> {
                FixedSizeListVector flv = (FixedSizeListVector) fv;
                IntVector child = (IntVector) flv.getDataVector();
                int n = iac.elementsPerRow();
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) {
                        flv.setNotNull(r);
                        int base = r * n;
                        for (int e = 0; e < n; e++) child.setSafe(base + e, iac.getInt(r, e));
                    }
                }
            }
            case StringArrayChunk sac -> {
                FixedSizeListVector flv = (FixedSizeListVector) fv;
                VarCharVector child = (VarCharVector) flv.getDataVector();
                int n = sac.elementsPerRow();
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) {
                        flv.setNotNull(r);
                        int base = r * n;
                        for (int e = 0; e < n; e++) {
                            String s = sac.getString(r, e);
                            if (s != null) child.setSafe(base + e, s.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            }
            case DateArrayChunk dac -> {
                FixedSizeListVector flv = (FixedSizeListVector) fv;
                TimeStampNanoTZVector child = (TimeStampNanoTZVector) flv.getDataVector();
                int n = dac.elementsPerRow();
                for (int r = 0; r < size; r++) {
                    if (!chunk.validity().isNull(r)) {
                        flv.setNotNull(r);
                        int base = r * n;
                        for (int e = 0; e < n; e++) child.setSafe(base + e, dac.getEpochNano(r, e));
                    }
                }
            }
            default -> throw new UnsupportedOperationException(
                    "Arrow: unsupported chunk type " + chunk.getClass().getSimpleName());
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
                        values[c] = readValue(fv, r, schema.field(c));
                    }
                    builder.appendRow(values);
                }
            }
            return builder.build();
        }
    }

    private static Object readValue(FieldVector fv, int r, Schema.Field field) {
        if (fv.isNull(r)) return null;
        return switch (fv) {
            case IntVector iv            -> iv.get(r);
            case BigIntVector bv         -> bv.get(r);
            case Float4Vector f4         -> f4.get(r);
            case Float8Vector f8         -> f8.get(r);
            case BitVector bv            -> bv.get(r) == 1;
            case VarCharVector vcv       -> new String(vcv.get(r), StandardCharsets.UTF_8);
            case TimeStampNanoTZVector t -> epochNanoToInstant(t.get(r));
            case FixedSizeListVector flv -> readFixedSizeList(flv, r, field.type());
            default -> throw new UnsupportedOperationException(
                    "Arrow: unsupported vector type " + fv.getClass().getSimpleName());
        };
    }

    private static Object readFixedSizeList(FixedSizeListVector flv, int r, DataType type) {
        int n = flv.getListSize();
        int base = r * n;
        FieldVector child = flv.getDataVector();
        return switch (type) {
            case DOUBLE_ARRAY -> {
                Float8Vector c = (Float8Vector) child;
                double[] arr = new double[n];
                for (int e = 0; e < n; e++) arr[e] = c.isNull(base + e) ? 0.0 : c.get(base + e);
                yield arr;
            }
            case INT_ARRAY -> {
                IntVector c = (IntVector) child;
                int[] arr = new int[n];
                for (int e = 0; e < n; e++) arr[e] = c.isNull(base + e) ? 0 : c.get(base + e);
                yield arr;
            }
            case STRING_ARRAY -> {
                VarCharVector c = (VarCharVector) child;
                String[] arr = new String[n];
                for (int e = 0; e < n; e++)
                    if (!c.isNull(base + e)) arr[e] = new String(c.get(base + e), StandardCharsets.UTF_8);
                yield arr;
            }
            case DATE_ARRAY -> {
                TimeStampNanoTZVector c = (TimeStampNanoTZVector) child;
                long[] arr = new long[n];
                for (int e = 0; e < n; e++) arr[e] = c.isNull(base + e) ? 0L : c.get(base + e);
                yield arr;
            }
            default -> throw new UnsupportedOperationException(
                    "Arrow: unexpected FixedSizeList column type " + type);
        };
    }

    // ---- schema mapping ------------------------------------------------------

    private static org.apache.arrow.vector.types.pojo.Schema toArrowSchema(Schema schema) {
        List<Field> fields = new ArrayList<>(schema.size());
        for (Schema.Field f : schema.fields()) fields.add(toArrowField(f));
        return new org.apache.arrow.vector.types.pojo.Schema(fields);
    }

    private static Field toArrowField(Schema.Field f) {
        return switch (f.type()) {
            case INT     -> Field.nullable(f.name(), new ArrowType.Int(32, true));
            case LONG    -> Field.nullable(f.name(), new ArrowType.Int(64, true));
            case FLOAT   -> Field.nullable(f.name(), new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE));
            case DOUBLE  -> Field.nullable(f.name(), new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE));
            case BOOLEAN -> Field.nullable(f.name(), new ArrowType.Bool());
            case STRING  -> Field.nullable(f.name(), new ArrowType.Utf8());
            case INSTANT -> Field.nullable(f.name(), new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC"));
            case DOUBLE_ARRAY -> fixedList(f.name(), f.arrayLength(),
                    new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE));
            case INT_ARRAY -> fixedList(f.name(), f.arrayLength(),
                    new ArrowType.Int(32, true));
            case STRING_ARRAY -> fixedList(f.name(), f.arrayLength(),
                    new ArrowType.Utf8());
            case DATE_ARRAY -> fixedList(f.name(), f.arrayLength(),
                    new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC"));
            default -> throw new UnsupportedOperationException(
                    "Arrow: unsupported column type " + f.type() + " ('" + f.name() + "')");
        };
    }

    private static Field fixedList(String name, int listSize, ArrowType childType) {
        Field child = Field.nullable(CHILD_FIELD_NAME, childType);
        return new Field(name, FieldType.nullable(new ArrowType.FixedSizeList(listSize)), List.of(child));
    }

    private static Schema fromArrowSchema(org.apache.arrow.vector.types.pojo.Schema arrowSchema) {
        Schema.Builder b = Schema.builder();
        for (Field f : arrowSchema.getFields()) {
            if (f.getType() instanceof ArrowType.FixedSizeList fsl) {
                int arrayLen = fsl.getListSize();
                DataType dt = arrayDataType(f.getChildren().get(0).getType());
                b.add(f.getName(), dt, arrayLen);
            } else {
                b.add(f.getName(), scalarDataType(f.getType()));
            }
        }
        return b.build();
    }

    private static DataType scalarDataType(ArrowType t) {
        return switch (t) {
            case ArrowType.Int i when i.getBitWidth() == 32 -> DataType.INT;
            case ArrowType.Int i                             -> DataType.LONG;
            case ArrowType.FloatingPoint fp when
                    fp.getPrecision() == FloatingPointPrecision.SINGLE -> DataType.FLOAT;
            case ArrowType.FloatingPoint fp                 -> DataType.DOUBLE;
            case ArrowType.Bool ignored                      -> DataType.BOOLEAN;
            case ArrowType.Utf8 ignored                      -> DataType.STRING;
            case ArrowType.Timestamp ignored                 -> DataType.INSTANT;
            default -> throw new UnsupportedOperationException(
                    "Arrow: unsupported scalar type " + t.getClass().getSimpleName());
        };
    }

    private static DataType arrayDataType(ArrowType childType) {
        return switch (childType) {
            case ArrowType.FloatingPoint fp -> DataType.DOUBLE_ARRAY;
            case ArrowType.Int i when i.getBitWidth() == 32 -> DataType.INT_ARRAY;
            case ArrowType.Utf8 ignored -> DataType.STRING_ARRAY;
            case ArrowType.Timestamp ignored -> DataType.DATE_ARRAY;
            default -> throw new UnsupportedOperationException(
                    "Arrow: unsupported FixedSizeList element type " + childType.getClass().getSimpleName());
        };
    }

    private static Instant epochNanoToInstant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                Math.floorMod(nanos, 1_000_000_000L));
    }
}
