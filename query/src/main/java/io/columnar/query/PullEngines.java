package io.columnar.query;

import io.columnar.api.ColumnarSlice;
import io.columnar.api.Viewport;
import io.columnar.core.Operator;
import io.columnar.core.PullContext;

/** Entry points for pull-based evaluation with caching. */
public final class PullEngines {

    private PullEngines() {}

    /**
     * Materialize {@code viewport} via {@link Operator#compute} using a caching pull context that
     * memoizes upstream source reads keyed by viewport + {@link io.columnar.api.Table#version()}.
     */
    public static ColumnarSlice pullCaching(Operator root, Viewport viewport) {
        CachedDerived cd = CachedDerived.of(root);
        return cd.table().read(viewport);
    }

    /** @see CachedDerived#of(Operator) */
    public static CachedDerived cachingDerived(Operator root) {
        return CachedDerived.of(root);
    }

    /** No-cache executor — forwards to {@link PullContext#direct()}. */
    public static ColumnarSlice pullDirect(Operator root, Viewport viewport) {
        return root.compute(viewport, PullContext.direct());
    }
}
