package io.columnar.viz;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.ColumnarSlice;
import io.columnar.core.DataType;
import io.columnar.core.Schema;
import io.columnar.core.chunk.BooleanChunk;
import io.columnar.core.chunk.DoubleArrayChunk;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.FloatChunk;
import io.columnar.core.chunk.InstantChunk;
import io.columnar.core.chunk.IntChunk;
import io.columnar.core.chunk.LongChunk;
import io.columnar.core.chunk.StringChunk;

/**
 * Compact JSON for AG Grid: {@code { "columnDefs": [...], "rowData": [...] }}.
 * Numeric cells are JSON numbers; strings are quoted; nulls use {@code null}.
 */
public final class SliceJson {

    private SliceJson() {}

    public static String toGridPayload(ColumnarSlice slice) {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("{\"columnDefs\":");
        appendColumnDefs(sb, slice.schema());
        sb.append(",\"rowData\":");
        appendRowData(sb, slice);
        sb.append(",\"version\":").append(slice.version());
        sb.append(",\"rowCount\":").append(slice.rowCount());
        sb.append('}');
        return sb.toString();
    }

    /** {@code GET /api/meta} body: column defs plus total logical row count. */
    public static String metaJson(Schema schema, long totalRows) {
        StringBuilder sb = new StringBuilder(512 + schema.size() * 48);
        sb.append("{\"columnDefs\":");
        appendColumnDefs(sb, schema);
        sb.append(",\"lastRow\":").append(totalRows);
        sb.append('}');
        return sb.toString();
    }

    /**
     * One infinite-datasource response: AG Grid expects {@code lastRow} as the exclusive
     * end index (= total rows) when row indices start at zero.
     */
    public static String infiniteBlock(ColumnarSlice slice, long logicalLastExclusive) {
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("{\"rows\":");
        appendRowData(sb, slice);
        sb.append(",\"version\":").append(slice.version());
        sb.append(",\"lastRow\":").append(logicalLastExclusive);
        sb.append('}');
        return sb.toString();
    }

    private static void appendColumnDefs(StringBuilder sb, Schema schema) {
        sb.append('[');
        for (int c = 0; c < schema.size(); c++) {
            if (c > 0) sb.append(',');
            Schema.Field f = schema.field(c);
            sb.append('{');
            sb.append("\"headerName\":");
            jsonString(sb, f.name());
            sb.append(",\"field\":");
            jsonString(sb, f.name());
            sb.append(",\"filter\":true,\"sortable\":true,\"resizable\":true");
            if (f.type() == DataType.DOUBLE
                    || f.type() == DataType.FLOAT
                    || f.type() == DataType.INT
                    || f.type() == DataType.LONG) {
                sb.append(",\"cellDataType\":\"number\"");
            } else if (f.type() == DataType.DOUBLE_ARRAY) {
                sb.append(",\"cellDataType\":\"numberArray\"");
            }
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendRowData(StringBuilder sb, ColumnarSlice slice) {
        Schema schema = slice.schema();
        int cols = schema.size();
        long rows = slice.rowCount();
        sb.append('[');
        for (long r = 0; r < rows; r++) {
            if (r > 0) sb.append(',');
            sb.append('{');
            for (int c = 0; c < cols; c++) {
                if (c > 0) sb.append(',');
                Column col = slice.column(c);
                jsonString(sb, col.name());
                sb.append(':');
                appendCellValue(sb, col, r);
            }
            sb.append('}');
        }
        sb.append(']');
    }

    /** Row index {@code globalRow} across all chunks of {@code column}. */
    private static void appendCellValue(StringBuilder sb, Column column, long globalRow) {
        long skip = globalRow;
        int n = column.chunkCount();
        for (int ci = 0; ci < n; ci++) {
            ColumnChunk chunk = column.chunk(ci);
            long sz = chunk.size();
            if (skip >= sz) {
                skip -= sz;
                continue;
            }
            int i = (int) skip;
            if (chunk.validity().isNull(i)) {
                sb.append("null");
                return;
            }
            switch (chunk) {
                case IntChunk ic -> sb.append(ic.getInt(i));
                case LongChunk lc -> sb.append(lc.getLong(i));
                case DoubleChunk dc -> {
                    double v = dc.getDouble(i);
                    if (Double.isNaN(v) || Double.isInfinite(v)) {
                        sb.append("null");
                    } else {
                        sb.append(Double.toString(v));
                    }
                }
                case FloatChunk fc -> {
                    float v = fc.getFloat(i);
                    if (Float.isNaN(v) || Float.isInfinite(v)) {
                        sb.append("null");
                    } else {
                        sb.append(Float.toString(v));
                    }
                }
                case BooleanChunk bc -> sb.append(bc.getBoolean(i));
                case InstantChunk ic -> {
                    sb.append('"').append(ic.getInstant(i).toString()).append('"');
                }
                case StringChunk sc -> {
                    String s = sc.getString(i);
                    if (s == null) sb.append("null");
                    else jsonString(sb, s);
                }
                case DoubleArrayChunk dac -> appendDoubleArrayJson(sb, dac, i);
                default -> {
                    sb.append('"');
                    sb.append(chunk.getClass().getSimpleName());
                    sb.append('"');
                }
            }
            return;
        }
        sb.append("null");
    }

    private static void appendDoubleArrayJson(StringBuilder sb, DoubleArrayChunk chunk, int row) {
        sb.append('[');
        int n = chunk.elementsPerRow();
        for (int e = 0; e < n; e++) {
            if (e > 0) {
                sb.append(',');
            }
            double v = chunk.getDouble(row, e);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                sb.append("null");
            } else {
                sb.append(Double.toString(v));
            }
        }
        sb.append(']');
    }

    private static void jsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < ' ') {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }
}
