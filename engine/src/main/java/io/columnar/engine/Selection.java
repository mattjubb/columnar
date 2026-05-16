package io.columnar.engine;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.Validity;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a per-chunk row-bitmap "selection" to a column, producing a new
 * column containing only the rows whose bits are set.
 *
 * <p>The output is a single HOT chunk per input chunk (compaction). Empty
 * outputs (no bits set) are omitted from the result column entirely.
 */
public final class Selection {

    private Selection() {}

    /**
     * Build a selected column. {@code maskWords} has one element per chunk
     * in {@code source}; each is a packed bitmap with chunk-size bits.
     * {@code popCounts} carries pre-computed popcounts so the caller can
     * batch-allocate the output and so we don't recompute per column.
     */
    public static Column apply(Column source, long[][] maskWords, int[] popCounts) {
        if (maskWords.length != source.chunkCount()) {
            throw new IllegalArgumentException(
                    "maskWords.length=" + maskWords.length
                            + " != chunks=" + source.chunkCount());
        }
        List<ColumnChunk> out = new ArrayList<>(source.chunkCount());
        for (int ci = 0; ci < source.chunkCount(); ci++) {
            int pop = popCounts[ci];
            if (pop == 0) continue;
            out.add(applyChunk(source.chunk(ci), maskWords[ci], pop));
        }
        return Column.of(source.name(), source.type(), out);
    }

    public static int popCount(long[] words, int rowCount) {
        int total = 0;
        int fullWords = rowCount >>> 6;
        for (int i = 0; i < fullWords; i++) total += Long.bitCount(words[i]);
        int tail = rowCount & 63;
        if (tail != 0) {
            long mask = (1L << tail) - 1L;
            total += Long.bitCount(words[fullWords] & mask);
        }
        return total;
    }

    private static ColumnChunk applyChunk(ColumnChunk chunk, long[] mask, int pop) {
        Validity srcValidity = chunk.validity();
        return switch (chunk) {
            case IntChunk ic -> {
                int[] out = new int[pop];
                int[] vIn = (ic instanceof HotIntChunk h) ? h.values() : null;
                long[] outValid = new long[(pop + 63) >>> 6];
                int o = 0;
                for (int i = 0; i < ic.size(); i++) {
                    if ((mask[i >>> 6] & (1L << (i & 63))) == 0) continue;
                    out[o] = vIn != null ? vIn[i] : ic.getInt(i);
                    if (srcValidity.isValid(i)) outValid[o >>> 6] |= 1L << (o & 63);
                    o++;
                }
                yield new HotIntChunk(out, pop, Validity.fromWords(outValid, pop));
            }
            case LongChunk lc -> {
                long[] out = new long[pop];
                long[] vIn = (lc instanceof HotLongChunk h) ? h.values() : null;
                long[] outValid = new long[(pop + 63) >>> 6];
                int o = 0;
                for (int i = 0; i < lc.size(); i++) {
                    if ((mask[i >>> 6] & (1L << (i & 63))) == 0) continue;
                    out[o] = vIn != null ? vIn[i] : lc.getLong(i);
                    if (srcValidity.isValid(i)) outValid[o >>> 6] |= 1L << (o & 63);
                    o++;
                }
                yield new HotLongChunk(out, pop, Validity.fromWords(outValid, pop));
            }
            case DoubleChunk dc -> {
                double[] out = new double[pop];
                double[] vIn = (dc instanceof HotDoubleChunk h) ? h.values() : null;
                long[] outValid = new long[(pop + 63) >>> 6];
                int o = 0;
                for (int i = 0; i < dc.size(); i++) {
                    if ((mask[i >>> 6] & (1L << (i & 63))) == 0) continue;
                    out[o] = vIn != null ? vIn[i] : dc.getDouble(i);
                    if (srcValidity.isValid(i)) outValid[o >>> 6] |= 1L << (o & 63);
                    o++;
                }
                yield new HotDoubleChunk(out, pop, Validity.fromWords(outValid, pop));
            }
            case FloatChunk fc -> {
                float[] out = new float[pop];
                float[] vIn = (fc instanceof HotFloatChunk h) ? h.values() : null;
                long[] outValid = new long[(pop + 63) >>> 6];
                int o = 0;
                for (int i = 0; i < fc.size(); i++) {
                    if ((mask[i >>> 6] & (1L << (i & 63))) == 0) continue;
                    out[o] = vIn != null ? vIn[i] : fc.getFloat(i);
                    if (srcValidity.isValid(i)) outValid[o >>> 6] |= 1L << (o & 63);
                    o++;
                }
                yield new HotFloatChunk(out, pop, Validity.fromWords(outValid, pop));
            }
            case BooleanChunk bc -> {
                long[] outBits = new long[(pop + 63) >>> 6];
                long[] outValid = new long[(pop + 63) >>> 6];
                int o = 0;
                for (int i = 0; i < bc.size(); i++) {
                    if ((mask[i >>> 6] & (1L << (i & 63))) == 0) continue;
                    if (bc.getBoolean(i)) outBits[o >>> 6] |= 1L << (o & 63);
                    if (srcValidity.isValid(i)) outValid[o >>> 6] |= 1L << (o & 63);
                    o++;
                }
                yield new HotBooleanChunk(outBits, pop, Validity.fromWords(outValid, pop));
            }
            case InstantChunk ic -> {
                long[] out = new long[pop];
                long[] vIn = (ic instanceof HotInstantChunk h) ? h.epochNanos() : null;
                long[] outValid = new long[(pop + 63) >>> 6];
                int o = 0;
                for (int i = 0; i < ic.size(); i++) {
                    if ((mask[i >>> 6] & (1L << (i & 63))) == 0) continue;
                    out[o] = vIn != null ? vIn[i] : ic.getEpochNano(i);
                    if (srcValidity.isValid(i)) outValid[o >>> 6] |= 1L << (o & 63);
                    o++;
                }
                yield new HotInstantChunk(out, pop, Validity.fromWords(outValid, pop));
            }
            case StringChunk sc -> {
                int[] out = new int[pop];
                long[] outValid = new long[(pop + 63) >>> 6];
                int o = 0;
                for (int i = 0; i < sc.size(); i++) {
                    if ((mask[i >>> 6] & (1L << (i & 63))) == 0) continue;
                    out[o] = sc.getCode(i);
                    if (srcValidity.isValid(i)) outValid[o >>> 6] |= 1L << (o & 63);
                    o++;
                }
                StringDictionary dict = (sc instanceof HotStringChunk h) ? h.dictionary() : null;
                if (dict == null) {
                    throw new UnsupportedOperationException(
                            "selection on string chunk without dictionary access not yet supported");
                }
                yield new HotStringChunk(out, pop, Validity.fromWords(outValid, pop), dict);
            }
            default -> throw new UnsupportedOperationException(
                    "selection on chunk type " + chunk.getClass().getSimpleName() + " not implemented");
        };
    }
}
