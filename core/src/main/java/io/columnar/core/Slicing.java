package io.columnar.core;

import io.columnar.core.chunk.BooleanChunk;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.FloatChunk;
import io.columnar.core.chunk.HotBooleanChunk;
import io.columnar.core.chunk.HotDoubleChunk;
import io.columnar.core.chunk.HotFloatChunk;
import io.columnar.core.chunk.HotInstantChunk;
import io.columnar.core.chunk.HotIntChunk;
import io.columnar.core.chunk.HotLongChunk;
import io.columnar.core.chunk.HotStringChunk;
import io.columnar.core.chunk.InstantChunk;
import io.columnar.core.chunk.IntChunk;
import io.columnar.core.chunk.LongChunk;
import io.columnar.core.chunk.StringChunk;
import io.columnar.core.chunk.StringDictionary;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for slicing a list of {@link Column}s down to a {@link Viewport}'s
 * row range and column subset.
 *
 * <p>Slicing is logical-physical: chunk boundaries are preserved when a chunk
 * fits entirely inside the requested range; partial chunks at the boundaries
 * are copied into a new {@link io.columnar.core.Residency#HOT HOT} chunk
 * containing only the requested rows.
 */
public final class Slicing {

    private Slicing() {}

    /**
     * Slice a single column to {@code [rowFrom, rowTo)}. Returns a new
     * {@link Column} that may share whole chunks with the input or include
     * freshly allocated boundary chunks.
     */
    public static Column slice(Column column, long rowFrom, long rowTo) {
        if (rowFrom < 0 || rowTo < rowFrom) {
            throw new IllegalArgumentException("invalid range [" + rowFrom + ", " + rowTo + ")");
        }
        if (rowFrom == 0 && rowTo >= column.size()) {
            return column;
        }
        ObjectArrayList<ColumnChunk> picked = new ObjectArrayList<>();
        long pos = 0;
        for (ColumnChunk chunk : column.chunks()) {
            int chunkSize = chunk.size();
            long chunkStart = pos;
            long chunkEnd = pos + chunkSize;
            pos = chunkEnd;
            if (chunkEnd <= rowFrom) continue;
            if (chunkStart >= rowTo) break;

            long localFromL = Math.max(rowFrom - chunkStart, 0L);
            long localToL = Math.min(rowTo - chunkStart, (long) chunkSize);
            int localFrom = (int) localFromL;
            int localTo = (int) localToL;
            if (localFrom == 0 && localTo == chunkSize) {
                picked.add(chunk);
            } else {
                picked.add(sliceChunk(chunk, localFrom, localTo));
            }
        }
        return Column.of(column.name(), column.type(), picked);
    }

    /** Slice a list of aligned columns. */
    public static List<Column> slice(List<Column> columns, long rowFrom, long rowTo) {
        List<Column> out = new ArrayList<>(columns.size());
        for (Column c : columns) {
            out.add(slice(c, rowFrom, rowTo));
        }
        return out;
    }

    /** Project a list of columns down to a name subset (preserving the requested order). */
    public static List<Column> project(List<Column> columns, List<String> names) {
        List<Column> out = new ArrayList<>(names.size());
        for (String n : names) {
            Column c = findByName(columns, n);
            out.add(c);
        }
        return out;
    }

    private static Column findByName(List<Column> columns, String name) {
        for (Column c : columns) {
            if (c.name().equals(name)) return c;
        }
        throw new IllegalArgumentException("no such column in slice: " + name);
    }

    /**
     * Copy a sub-range of one chunk into a fresh HOT chunk. Avoids leaking the
     * full source array into a small slice. Currently supports the built-in
     * primitive types; objects fall back to per-row copy.
     */
    public static ColumnChunk sliceChunk(ColumnChunk chunk, int from, int to) {
        if (from < 0 || to < from || to > chunk.size()) {
            throw new IllegalArgumentException(
                    "invalid local range [" + from + ", " + to + ") size=" + chunk.size());
        }
        int len = to - from;
        if (len == chunk.size()) return chunk;
        return switch (chunk) {
            case IntChunk ic -> {
                int[] vals = new int[len];
                for (int i = 0; i < len; i++) vals[i] = ic.getInt(from + i);
                yield new HotIntChunk(vals, len, sliceValidity(chunk.validity(), from, to));
            }
            case LongChunk lc -> {
                long[] vals = new long[len];
                for (int i = 0; i < len; i++) vals[i] = lc.getLong(from + i);
                yield new HotLongChunk(vals, len, sliceValidity(chunk.validity(), from, to));
            }
            case DoubleChunk dc -> {
                double[] vals = new double[len];
                for (int i = 0; i < len; i++) vals[i] = dc.getDouble(from + i);
                yield new HotDoubleChunk(vals, len, sliceValidity(chunk.validity(), from, to));
            }
            case FloatChunk fc -> {
                float[] vals = new float[len];
                for (int i = 0; i < len; i++) vals[i] = fc.getFloat(from + i);
                yield new HotFloatChunk(vals, len, sliceValidity(chunk.validity(), from, to));
            }
            case BooleanChunk bc -> {
                long[] words = new long[(len + 63) >>> 6];
                for (int i = 0; i < len; i++) {
                    if (bc.getBoolean(from + i)) {
                        words[i >>> 6] |= 1L << (i & 63);
                    }
                }
                yield new HotBooleanChunk(words, len, sliceValidity(chunk.validity(), from, to));
            }
            case InstantChunk ic -> {
                long[] vals = new long[len];
                for (int i = 0; i < len; i++) vals[i] = ic.getEpochNano(from + i);
                yield new HotInstantChunk(vals, len, sliceValidity(chunk.validity(), from, to));
            }
            case StringChunk sc -> {
                int[] codes = new int[len];
                for (int i = 0; i < len; i++) codes[i] = sc.getCode(from + i);
                StringDictionary dict = (sc instanceof HotStringChunk hsc) ? hsc.dictionary() : null;
                if (dict == null) {
                    throw new UnsupportedOperationException(
                            "slicing string chunk without dictionary access not yet supported");
                }
                yield new HotStringChunk(codes, len, sliceValidity(chunk.validity(), from, to), dict);
            }
            default -> throw new UnsupportedOperationException(
                    "slicing of chunk type " + chunk.getClass().getSimpleName() + " not implemented");
        };
    }

    private static Validity sliceValidity(Validity src, int from, int to) {
        int len = to - from;
        long[] srcWords = src.words();
        long[] dst = new long[(len + 63) >>> 6];
        // Bit-by-bit copy. A faster shifted copy is possible, but this is correct and rarely hot.
        for (int i = 0; i < len; i++) {
            int srcBit = from + i;
            if ((srcWords[srcBit >>> 6] & (1L << (srcBit & 63))) != 0) {
                dst[i >>> 6] |= 1L << (i & 63);
            }
        }
        return Validity.fromWords(dst, len);
    }
}
