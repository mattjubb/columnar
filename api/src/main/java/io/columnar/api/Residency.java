package io.columnar.api;

/**
 * Where a {@link ColumnChunk}'s storage currently lives.
 * <ul>
 *   <li>{@link #HOT} — on-heap primitive arrays. Fastest access; counts against GC heap.</li>
 *   <li>{@link #WARM} — off-heap {@code MemorySegment}. Frees the heap; slightly slower
 *       random access; FFM API supports vectorized bulk reads at near-array speed.</li>
 * </ul>
 *
 * The runtime can transparently promote WARM → HOT on access (via pin) and demote
 * HOT → WARM when the heap budget is under pressure.
 */
public enum Residency {
    HOT,
    WARM
}
