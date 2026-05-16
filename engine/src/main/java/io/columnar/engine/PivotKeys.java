package io.columnar.engine;

import io.columnar.core.Column;
import io.columnar.core.chunk.StringChunk;

import java.util.LinkedHashSet;
import java.util.List;

/** Utilities for planning pivot/group-by operations over dictionary-encoded STRING columns. */
public final class PivotKeys {

    private PivotKeys() {}

    /**
     * Discover distinct pivot keys in encountered order (deterministic traversal order across
     * chunks left-to-right, top-to-bottom). Null codes are skipped.
     */
    public static List<String> discoverStringKeys(Column pivotColumn) {
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        int chunks = pivotColumn.chunkCount();
        for (int ci = 0; ci < chunks; ci++) {
            StringChunk sc = (StringChunk) pivotColumn.chunk(ci);
            int rows = sc.size();
            long[] valid = sc.validity().words();
            for (int i = 0; i < rows; i++) {
                if ((valid[i >>> 6] & (1L << (i & 63))) == 0) {
                    continue;
                }
                uniq.add(sc.getString(i));
            }
        }
        return List.copyOf(uniq);
    }
}
