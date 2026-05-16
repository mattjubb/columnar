package io.columnar.query;

import io.columnar.core.ColumnarSlice;
import io.columnar.core.Viewport;
import io.columnar.engine.Operator;
import io.columnar.engine.PullContext;

/** Entry points for pull-based evaluation with caching. */
public final class PullEngines {

    private PullEngines() {}

    /**
     * Materialize {@code viewport} via {@link Operator#compute} using a caching pull context that
     * memoizes upstream source reads keyed by viewport + {@link io.columnar.core.Table#version()}.
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
