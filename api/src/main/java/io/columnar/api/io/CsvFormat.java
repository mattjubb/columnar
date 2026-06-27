package io.columnar.api.io;

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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** RFC 4180 CSV reader/writer. Null values are represented as empty fields. */
final class CsvFormat implements TableReader, TableWriter {

    // ---- write ---------------------------------------------------------------

    @Override
    public void write(Table table, Path path) throws IOException {
        ColumnarSlice slice = table.read();
        Schema schema = slice.schema();
        int numCols = schema.size();
        validateScalarOnly(schema);

        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            // header
            for (int c = 0; c < numCols; c++) {
                if (c > 0) w.write(',');
                writeField(w, schema.field(c).name());
            }
            w.newLine();

            // rows — iterate chunk by chunk
            int chunkCount = slice.chunkCount();
            for (int ci = 0; ci < chunkCount; ci++) {
                ColumnChunk[] chunks = new ColumnChunk[numCols];
                for (int c = 0; c < numCols; c++) {
                    chunks[c] = slice.column(c).chunk(ci);
                }
                int chunkSize = chunks[0].size();
                for (int row = 0; row < chunkSize; row++) {
                    for (int c = 0; c < numCols; c++) {
                        if (c > 0) w.write(',');
                        if (chunks[c].validity().isNull(row)) continue;
                        writeField(w, cellToString(chunks[c], row));
                    }
                    w.newLine();
                }
            }
        }
    }

    private static String cellToString(ColumnChunk chunk, int row) {
        return switch (chunk) {
            case IntChunk ic     -> Integer.toString(ic.getInt(row));
            case LongChunk lc    -> Long.toString(lc.getLong(row));
            case FloatChunk fc   -> Float.toString(fc.getFloat(row));
            case DoubleChunk dc  -> Double.toString(dc.getDouble(row));
            case BooleanChunk bc -> Boolean.toString(bc.getBoolean(row));
            case StringChunk sc  -> sc.getString(row) == null ? "" : sc.getString(row);
            case InstantChunk ic -> ic.getInstant(row).toString();
            default -> throw new UnsupportedOperationException(
                    "CSV does not support chunk type: " + chunk.getClass().getSimpleName());
        };
    }

    /** RFC 4180: quote fields containing comma, double-quote, or newline. */
    private static void writeField(BufferedWriter w, String value) throws IOException {
        boolean needsQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            w.write(value);
        } else {
            w.write('"');
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '"') w.write('"');
                w.write(ch);
            }
            w.write('"');
        }
    }

    // ---- read ----------------------------------------------------------------

    @Override
    public BaseTable read(Path path) throws IOException {
        List<String[]> rows = readAllRows(path);
        if (rows.isEmpty()) {
            throw new IOException("CSV file is empty: " + path);
        }
        String[] header = rows.get(0);
        List<String[]> data = rows.subList(1, rows.size());
        Schema schema = inferSchema(header, data);
        return buildTable(schema, header, data);
    }

    @Override
    public BaseTable read(Path path, Schema schema) throws IOException {
        List<String[]> rows = readAllRows(path);
        if (rows.isEmpty()) {
            throw new IOException("CSV file is empty: " + path);
        }
        String[] header = rows.get(0);
        List<String[]> data = rows.subList(1, rows.size());
        return buildTable(schema, header, data);
    }

    private static List<String[]> readAllRows(Path path) throws IOException {
        List<String[]> result = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    result.add(parseLine(line));
                }
            }
        }
        return result;
    }

    /** Infer column types by scanning all data rows. */
    private static Schema inferSchema(String[] header, List<String[]> data) {
        int cols = header.length;
        DataType[] types = new DataType[cols];
        for (int c = 0; c < cols; c++) {
            types[c] = inferColumnType(data, c);
        }
        Schema.Builder b = Schema.builder();
        for (int c = 0; c < cols; c++) {
            b.add(header[c], types[c]);
        }
        return b.build();
    }

    private static DataType inferColumnType(List<String[]> data, int col) {
        boolean canBeInt = true, canBeLong = true, canBeDouble = true;
        for (String[] row : data) {
            String v = col < row.length ? row[col] : "";
            if (v.isEmpty()) continue; // null — doesn't constrain type
            if (canBeInt) {
                try { Integer.parseInt(v); } catch (NumberFormatException e) { canBeInt = false; }
            }
            if (canBeLong && !canBeInt) {
                try { Long.parseLong(v); } catch (NumberFormatException e) { canBeLong = false; }
            }
            if (canBeDouble && !canBeLong) {
                try { Double.parseDouble(v); } catch (NumberFormatException e) { canBeDouble = false; }
            }
        }
        if (canBeInt)    return DataType.INT;
        if (canBeLong)   return DataType.LONG;
        if (canBeDouble) return DataType.DOUBLE;
        return DataType.STRING;
    }

    private static BaseTable buildTable(Schema schema, String[] header, List<String[]> data) {
        // Build a column-index map from schema name → position in header
        int[] colMap = new int[schema.size()];
        for (int c = 0; c < schema.size(); c++) {
            String name = schema.field(c).name();
            colMap[c] = -1;
            for (int h = 0; h < header.length; h++) {
                if (header[h].equals(name)) { colMap[c] = h; break; }
            }
        }

        BaseTable.Builder builder = Table.builder(schema);
        for (String[] row : data) {
            Object[] values = new Object[schema.size()];
            for (int c = 0; c < schema.size(); c++) {
                int h = colMap[c];
                String raw = (h >= 0 && h < row.length) ? row[h] : "";
                values[c] = raw.isEmpty() ? null : parseValue(raw, schema.field(c).type());
            }
            builder.appendRow(values);
        }
        return builder.build();
    }

    private static Object parseValue(String raw, DataType type) {
        return switch (type) {
            case INT     -> Integer.parseInt(raw);
            case LONG    -> Long.parseLong(raw);
            case FLOAT   -> Float.parseFloat(raw);
            case DOUBLE  -> Double.parseDouble(raw);
            case BOOLEAN -> Boolean.parseBoolean(raw);
            case STRING  -> raw;
            case INSTANT -> Instant.parse(raw);
            default -> throw new UnsupportedOperationException("CSV cannot parse type: " + type);
        };
    }

    /** Minimal RFC 4180 single-line parser. Does not handle multi-line quoted fields. */
    private static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        int len = line.length();
        do {
            if (i < len && line.charAt(i) == '"') {
                // quoted field
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < len) {
                    char ch = line.charAt(i++);
                    if (ch == '"') {
                        if (i < len && line.charAt(i) == '"') {
                            sb.append('"');
                            i++;
                        } else {
                            break; // closing quote
                        }
                    } else {
                        sb.append(ch);
                    }
                }
                fields.add(sb.toString());
                if (i < len && line.charAt(i) == ',') i++;
            } else {
                int start = i;
                while (i < len && line.charAt(i) != ',') i++;
                fields.add(line.substring(start, i));
                if (i < len) i++; // skip comma — only re-enter loop if more data follows
                else break;       // no trailing comma: we're done
            }
        } while (i < len);
        return fields.toArray(String[]::new);
    }

    private static void validateScalarOnly(Schema schema) {
        for (Schema.Field f : schema.fields()) {
            switch (f.type()) {
                case INT, LONG, FLOAT, DOUBLE, BOOLEAN, STRING, INSTANT -> {}
                default -> throw new UnsupportedOperationException(
                        "CSV does not support column type " + f.type() + " (column '" + f.name() + "')");
            }
        }
    }
}
