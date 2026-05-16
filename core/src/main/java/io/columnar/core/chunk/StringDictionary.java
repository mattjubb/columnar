package io.columnar.core.chunk;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * Append-only dictionary of distinct strings shared by all chunks of a single
 * string column. Codes are dense {@code int}s assigned in insertion order.
 *
 * <p>Backed by fastutil structures: {@link Object2IntOpenHashMap} avoids
 * {@code Integer} boxing on the lookup hot-path.
 *
 * <p>Thread-safety: dictionaries are append-only and protected by intrinsic
 * monitors of the owning column. The current implementation is not concurrent;
 * callers must serialize {@link #intern(String)} during the append path.
 */
public final class StringDictionary {

    private static final int NOT_PRESENT = -1;

    private final ObjectArrayList<String> codeToValue = new ObjectArrayList<>();
    private final Object2IntOpenHashMap<String> valueToCode = new Object2IntOpenHashMap<>();

    public StringDictionary() {
        valueToCode.defaultReturnValue(NOT_PRESENT);
    }

    public int intern(String value) {
        int code = valueToCode.getInt(value);
        if (code != NOT_PRESENT) {
            return code;
        }
        int newCode = codeToValue.size();
        codeToValue.add(value);
        valueToCode.put(value, newCode);
        return newCode;
    }

    /** Look up an existing code without inserting. Returns -1 if not present. */
    public int codeOf(String value) {
        return valueToCode.getInt(value);
    }

    public String resolve(int code) {
        return codeToValue.get(code);
    }

    public int size() {
        return codeToValue.size();
    }
}
