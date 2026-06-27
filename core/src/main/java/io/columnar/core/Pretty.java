package io.columnar.core;

import io.columnar.core.chunk.BooleanChunk;
import io.columnar.core.chunk.DateArrayChunk;
import io.columnar.core.chunk.DoubleArrayChunk;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.FloatChunk;
import io.columnar.core.chunk.InstantChunk;
import io.columnar.core.chunk.IntArrayChunk;
import io.columnar.core.chunk.IntChunk;
import io.columnar.core.chunk.LongChunk;
import io.columnar.core.chunk.StringArrayChunk;
import io.columnar.core.chunk.StringChunk;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * Formats {@link ColumnarSlice}, {@link Column}, and {@link Table} contents
 * as human-readable text grids. Used by the {@code prettyPrint()} convenience
 * methods scattered across the public API.
 *
 * <p>The output is deliberately pandas-ish: a header row with column names, a
 * type sub-header, a separator, then data rows. Truncation is applied to both
 * rows and columns and is annotated in the footer so the reader can tell at a
 * glance what was elided.
 */
public final class Pretty {

    public static final int DEFAULT_MAX_ROWS = 20;
    public static final int DEFAULT_MAX_COLS = 10;
    public static final int DEFAULT_MAX_CELL_WIDTH = 40;
    public static final String NULL_REPR = "null";
    public static final String ELLIPSIS = "...";

    private Pretty() {}

    public static String format(ColumnarSlice slice) {
        return format(slice, DEFAULT_MAX_ROWS, DEFAULT_MAX_COLS, DEFAULT_MAX_CELL_WIDTH);
    }

    public static String format(ColumnarSlice slice, int maxRows, int maxCols, int maxCellWidth) {
        if (slice == null) return "<null slice>";
        Schema schema = slice.schema();
        int totalCols = schema.size();
        boolean colTrunc = totalCols > maxCols;
        int shownCols = Math.min(totalCols, maxCols);

        long totalRows = slice.rowCount();
        boolean rowTrunc = totalRows > maxRows;
        int shownRows = (int) Math.min(totalRows, maxRows);

        // Build header & type rows.
        String[] headers = new String[shownCols + 1];
        String[] typeRow = new String[shownCols + 1];
        headers[0] = "#";
        typeRow[0] = "";
        for (int c = 0; c < shownCols; c++) {
            Schema.Field f = schema.field(c);
            headers[c + 1] = truncate(f.name(), maxCellWidth);
            typeRow[c + 1] = typeLabel(f);
        }

        // Materialize visible cells.
        String[][] cells = new String[shownRows][shownCols + 1];
        for (int r = 0; r < shownRows; r++) {
            cells[r][0] = Integer.toString(r);
        }
        for (int c = 0; c < shownCols; c++) {
            Column col = slice.column(c);
            int rowIdx = 0;
            outer:
            for (int chunkIdx = 0; chunkIdx < col.chunkCount(); chunkIdx++) {
                ColumnChunk chunk = col.chunk(chunkIdx);
                int chunkSize = chunk.size();
                for (int i = 0; i < chunkSize; i++) {
                    if (rowIdx >= shownRows) break outer;
                    cells[rowIdx][c + 1] = formatCell(chunk, i, maxCellWidth);
                    rowIdx++;
                }
            }
            // Pad short tables (rowCount < shownRows shouldn't happen but be safe).
            while (rowIdx < shownRows) {
                cells[rowIdx][c + 1] = "";
                rowIdx++;
            }
        }

        // Compute per-column widths.
        int[] widths = new int[shownCols + 1];
        for (int c = 0; c < shownCols + 1; c++) {
            widths[c] = Math.max(headers[c].length(), typeRow[c].length());
            for (int r = 0; r < shownRows; r++) {
                widths[c] = Math.max(widths[c], cells[r][c].length());
            }
        }

        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers, widths, colTrunc);
        appendRow(sb, typeRow, widths, colTrunc);
        appendSeparator(sb, widths, colTrunc);
        for (int r = 0; r < shownRows; r++) {
            appendRow(sb, cells[r], widths, colTrunc);
        }
        if (rowTrunc) {
            long remaining = totalRows - shownRows;
            sb.append("... (").append(remaining).append(" more row").append(remaining == 1 ? "" : "s")
                    .append(")\n");
        }
        sb.append("[").append(totalRows).append(" row").append(totalRows == 1 ? "" : "s")
                .append(" × ").append(totalCols).append(" column").append(totalCols == 1 ? "" : "s");
        if (colTrunc) {
            sb.append(", showing first ").append(shownCols);
        }
        sb.append(", version=").append(slice.version()).append("]\n");
        return sb.toString();
    }

    public static String formatColumn(Column column, int maxRows, int maxCellWidth) {
        if (column == null) return "<null column>";
        long totalRows = column.size();
        boolean rowTrunc = totalRows > maxRows;
        int shownRows = (int) Math.min(totalRows, maxRows);

        String header = column.name();
        String typeRow = column.type().name();

        String[] values = new String[shownRows];
        int rowIdx = 0;
        outer:
        for (int chunkIdx = 0; chunkIdx < column.chunkCount(); chunkIdx++) {
            ColumnChunk chunk = column.chunk(chunkIdx);
            int chunkSize = chunk.size();
            for (int i = 0; i < chunkSize; i++) {
                if (rowIdx >= shownRows) break outer;
                values[rowIdx++] = formatCell(chunk, i, maxCellWidth);
            }
        }
        while (rowIdx < shownRows) values[rowIdx++] = "";

        int idxWidth = Math.max(1, Integer.toString(Math.max(0, shownRows - 1)).length());
        int valWidth = Math.max(header.length(), typeRow.length());
        for (String v : values) valWidth = Math.max(valWidth, v.length());

        StringBuilder sb = new StringBuilder();
        String[] hdr = {"#", header};
        String[] typ = {"", typeRow};
        int[] widths = {idxWidth, valWidth};
        appendRow(sb, hdr, widths, false);
        appendRow(sb, typ, widths, false);
        appendSeparator(sb, widths, false);
        for (int r = 0; r < shownRows; r++) {
            appendRow(sb, new String[]{Integer.toString(r), values[r]}, widths, false);
        }
        if (rowTrunc) {
            long remaining = totalRows - shownRows;
            sb.append("... (").append(remaining).append(" more)\n");
        }
        sb.append("[").append(totalRows).append(" row").append(totalRows == 1 ? "" : "s")
                .append("]\n");
        return sb.toString();
    }

    public static String formatColumn(Column column) {
        return formatColumn(column, DEFAULT_MAX_ROWS, DEFAULT_MAX_CELL_WIDTH);
    }

    public static void print(ColumnarSlice slice) {
        print(slice, System.out);
    }

    public static void print(ColumnarSlice slice, PrintStream out) {
        out.print(format(slice));
    }

    public static void print(Column column) {
        print(column, System.out);
    }

    public static void print(Column column, PrintStream out) {
        out.print(formatColumn(column));
    }

    // ---- internals ------------------------------------------------------------

    private static void appendRow(StringBuilder sb, String[] cells, int[] widths, boolean colTrunc) {
        for (int c = 0; c < cells.length; c++) {
            if (c > 0) sb.append(" | ");
            sb.append(padRight(cells[c] == null ? "" : cells[c], widths[c]));
        }
        if (colTrunc) sb.append(" | ").append(ELLIPSIS);
        sb.append('\n');
    }

    private static void appendSeparator(StringBuilder sb, int[] widths, boolean colTrunc) {
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) sb.append("-+-");
            sb.append(repeat('-', widths[c]));
        }
        if (colTrunc) sb.append("-+-").append("---");
        sb.append('\n');
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        char[] pad = new char[width - s.length()];
        Arrays.fill(pad, ' ');
        return s + new String(pad);
    }

    private static String repeat(char ch, int n) {
        char[] cs = new char[n];
        Arrays.fill(cs, ch);
        return new String(cs);
    }

    private static String typeLabel(Schema.Field f) {
        if (f.arrayLength() > 0) {
            return f.type().name() + '[' + f.arrayLength() + ']';
        }
        return f.type().name();
    }

    private static String formatDoubleArrayRow(DoubleArrayChunk chunk, int row) {
        int n = chunk.elementsPerRow();
        StringBuilder sb = new StringBuilder(n * 8 + 2);
        sb.append('[');
        for (int e = 0; e < n; e++) {
            if (e > 0) sb.append(',');
            sb.append(chunk.getDouble(row, e));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String formatIntArrayRow(IntArrayChunk chunk, int row) {
        int n = chunk.elementsPerRow();
        StringBuilder sb = new StringBuilder(n * 4 + 2);
        sb.append('[');
        for (int e = 0; e < n; e++) {
            if (e > 0) sb.append(',');
            sb.append(chunk.getInt(row, e));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String formatStringArrayRow(StringArrayChunk chunk, int row) {
        int n = chunk.elementsPerRow();
        StringBuilder sb = new StringBuilder(n * 8 + 2);
        sb.append('[');
        for (int e = 0; e < n; e++) {
            if (e > 0) sb.append(',');
            String v = chunk.getString(row, e);
            sb.append(v == null ? NULL_REPR : v);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String formatDateArrayRow(DateArrayChunk chunk, int row) {
        int n = chunk.elementsPerRow();
        StringBuilder sb = new StringBuilder(n * 30 + 2);
        sb.append('[');
        for (int e = 0; e < n; e++) {
            if (e > 0) sb.append(',');
            sb.append(chunk.getInstant(row, e));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String truncate(String s, int maxWidth) {
        if (s == null) return NULL_REPR;
        if (s.length() <= maxWidth) return s;
        if (maxWidth < ELLIPSIS.length()) return s.substring(0, maxWidth);
        return s.substring(0, maxWidth - ELLIPSIS.length()) + ELLIPSIS;
    }

    private static String formatCell(ColumnChunk chunk, int row, int maxWidth) {
        if (chunk.validity().isNull(row)) return NULL_REPR;
        String s = switch (chunk) {
            case IntChunk ic -> Integer.toString(ic.getInt(row));
            case LongChunk lc -> Long.toString(lc.getLong(row));
            case DoubleChunk dc -> Double.toString(dc.getDouble(row));
            case FloatChunk fc -> Float.toString(fc.getFloat(row));
            case BooleanChunk bc -> Boolean.toString(bc.getBoolean(row));
            case InstantChunk ic -> ic.getInstant(row).toString();
            case StringChunk sc -> {
                String v = sc.getString(row);
                yield v == null ? NULL_REPR : v;
            }
            case DoubleArrayChunk dac -> formatDoubleArrayRow(dac, row);
            case IntArrayChunk iac -> formatIntArrayRow(iac, row);
            case StringArrayChunk sac -> formatStringArrayRow(sac, row);
            case DateArrayChunk dac -> formatDateArrayRow(dac, row);
            default -> "<" + chunk.getClass().getSimpleName() + ">";
        };
        return truncate(s, maxWidth);
    }
}
