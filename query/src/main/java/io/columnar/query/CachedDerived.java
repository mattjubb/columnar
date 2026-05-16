package io.columnar.query;

import io.columnar.engine.DerivedTable;
import io.columnar.engine.Operator;

/**
 * A {@link DerivedTable} paired with the {@link MaterializationCache} wired into its pull
 * context so callers can invalidate memoized upstream reads independently of DerivedTable's
 * last-viewport slice.
 */
public record CachedDerived(DerivedTable table, MaterializationCache cache) {

    /** Clear both pull-cache entries and DerivedTable single-slice bookkeeping. */
    public void invalidateAll() {
        cache.clear();
        table.invalidate();
    }

    /** Preferred factory — cache + derived table share the same {@link MaterializationCache}. */
    public static CachedDerived of(Operator operator) {
        MaterializationCache cache = new MaterializationCache();
        DerivedTable dt = new DerivedTable(operator, cache);
        return new CachedDerived(dt, cache);
    }
}
